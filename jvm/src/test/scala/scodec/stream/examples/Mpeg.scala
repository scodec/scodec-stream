/*
 * Copyright (c) 2013, Scodec
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package scodec.stream.examples

import scala.concurrent.duration._

import cats.effect.{Blocker, ContextShift, IO}

import scodec.bits._
import scodec._
import scodec.stream._

import fs2._

import java.nio.file.Paths

object Mpeg extends App {
  import PcapCodec._
  import MpegCodecs._

  val streamThroughRecordsOnly: StreamDecoder[MpegPacket] = {
    val pcapRecordStreamDecoder: StreamDecoder[PcapRecord] =
      StreamDecoder.once(pcapHeader).flatMap { header =>
        StreamDecoder.many(pcapRecord(header.ordering))
      }

    val mpegPcapDecoder: StreamDecoder[MpegPacket] = pcapRecordStreamDecoder.flatMap { record =>
      // Drop 22 byte ethernet frame header and 20 byte IPv4/udp header
      val datagramPayloadBits = record.data.drop(22 * 8).drop(20 * 8)
      val packets = codecs.vector(Codec[MpegPacket]).decode(datagramPayloadBits).map(_.value)
      packets.fold(e => StreamDecoder.raiseError(CodecError(e)), StreamDecoder.emits(_))
    }

    mpegPcapDecoder
  }

  val streamier: StreamDecoder[MpegPacket] = for {
    header <- StreamDecoder.once(pcapHeader)
    packet <- StreamDecoder.many(pcapRecordHeader(header.ordering)).flatMap { recordHeader =>
      StreamDecoder.isolate(recordHeader.includedLength * 8) {
        // Drop 22 byte ethernet frame header and 20 byte IPv4/udp header
        StreamDecoder.ignore((22 + 20) * 8) ++
          // bail on this record if we fail to parse an `MpegPacket` from it
          StreamDecoder.tryMany(mpegPacket)
      }
    }
  } yield packet

  def time[A](label: String)(a: => A): A = {
    val start = System.currentTimeMillis
    val result = a
    val stop = System.currentTimeMillis
    println(s"$label took ${(stop.toDouble - start) / 1000.0} s")
    result
  }

  implicit val csIO: ContextShift[IO] =
    IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)

  val filePath = Paths.get("path/to/file")

  def countElements(decoder: StreamDecoder[_]): IO[Int] =
    Stream
      .resource(Blocker[IO])
      .flatMap { blocker =>
        fs2.io.file
          .readAll[IO](filePath, blocker, 4096)
          .chunks
          .map(c => BitVector.view(c.toArray))
          .through(decoder.toPipe)
      }
      .compile
      .fold(0)((acc, _) => acc + 1)

  val result2 = time("coarse-grained")(countElements(streamThroughRecordsOnly).unsafeRunSync())
  val result1 = time("fine-grained")(countElements(streamier).unsafeRunSync())

  println("fine-grained stream packet count: " + result1)
  println("coarse-grained stream packet count: " + result2)
}

/**
  * Processes libpcap files.
  *
  * @see http://wiki.wireshark.org/Development/LibpcapFileFormat
  */
object PcapCodec {
  import scodec.codecs._

  sealed trait ByteOrdering
  case object BigEndian extends ByteOrdering
  case object LittleEndian extends ByteOrdering

  private val magicNumber = 0x000000a1b2c3d4L
  val byteOrdering = "magic_number" | Codec[ByteOrdering](
    (bo: ByteOrdering) =>
      if (bo == BigEndian) uint32.encode(magicNumber) else uint32L.encode(magicNumber),
    (buf: BitVector) =>
      uint32.decode(buf).map {
        _.map { mn =>
          if (mn == magicNumber) BigEndian else LittleEndian
        }
      }
  )

  def gint16(implicit ordering: ByteOrdering): Codec[Int] =
    if (ordering == BigEndian) int16 else int16L
  def guint16(implicit ordering: ByteOrdering): Codec[Int] =
    if (ordering == BigEndian) uint16 else uint16L
  def gint32(implicit ordering: ByteOrdering): Codec[Int] =
    if (ordering == BigEndian) int32 else int32L
  def guint32(implicit ordering: ByteOrdering): Codec[Long] =
    if (ordering == BigEndian) uint32 else uint32L

  case class PcapHeader(
      ordering: ByteOrdering,
      versionMajor: Int,
      versionMinor: Int,
      thiszone: Int,
      sigfigs: Long,
      snaplen: Long,
      network: Long
  )

  implicit val pcapHeader: Codec[PcapHeader] =
    ("magic_number" | byteOrdering)
      .flatPrepend { implicit ordering =>
        ("version_major" | guint16) ::
          ("version_minor" | guint16) ::
          ("thiszone" | gint32) ::
          ("sigfigs" | guint32) ::
          ("snaplen" | guint32) ::
          ("network" | guint32)
      }
      .as[PcapHeader]

  case class PcapRecordHeader(
      timestampSeconds: Long,
      timestampMicros: Long,
      includedLength: Long,
      originalLength: Long
  ) {
    def timestamp: Double = timestampSeconds + (timestampMicros / (1.second.toMicros.toDouble))
  }

  implicit def pcapRecordHeader(implicit ordering: ByteOrdering): Codec[PcapRecordHeader] = {
    ("ts_sec" | guint32) ::
      ("ts_usec" | guint32) ::
      ("incl_len" | guint32) ::
      ("orig_len" | guint32)
  }.as[PcapRecordHeader]

  case class PcapRecord(header: PcapRecordHeader, data: BitVector)

  implicit def pcapRecord(implicit ordering: ByteOrdering): Codec[PcapRecord] =
    ("record_header" | pcapRecordHeader)
      .flatPrepend { hdr =>
        ("record_data" | bits(hdr.includedLength * 8)).hlist
      }
      .as[PcapRecord]

  case class PcapFile(header: PcapHeader, records: Vector[PcapRecord])

  implicit val pcapFile: Codec[PcapFile] =
    pcapHeader
      .flatPrepend { hdr =>
        vector(pcapRecord(hdr.ordering)).hlist
      }
      .as[PcapFile]
}

object MpegCodecs {
  import scodec.codecs._

  // Define case classes that describe MPEG packets and define an HList iso for each

  case class TransportStreamHeader(
      transportStringIndicator: Boolean,
      payloadUnitStartIndicator: Boolean,
      transportPriority: Boolean,
      pid: Int,
      scramblingControl: Int,
      adaptationFieldControl: Int,
      continuityCounter: Int
  ) {
    def adaptationFieldIncluded: Boolean = adaptationFieldControl >= 2
    def payloadIncluded: Boolean = adaptationFieldControl == 1 || adaptationFieldControl == 3
  }

  case class AdaptationFieldFlags(
      discontinuity: Boolean,
      randomAccess: Boolean,
      priority: Boolean,
      pcrFlag: Boolean,
      opcrFlag: Boolean,
      splicingPointFlag: Boolean,
      transportPrivateDataFlag: Boolean,
      adaptationFieldExtension: Boolean
  )

  case class AdaptationField(
      flags: AdaptationFieldFlags,
      pcr: Option[BitVector],
      opcr: Option[BitVector],
      spliceCountdown: Option[Int]
  )

  case class MpegPacket(
      header: TransportStreamHeader,
      adaptationField: Option[AdaptationField],
      payload: Option[ByteVector]
  )

  implicit val transportStreamHeader: Codec[TransportStreamHeader] = {
    ("syncByte" | constant(0x47)) ~>
      (("transportStringIndicator" | bool) ::
        ("payloadUnitStartIndicator" | bool) ::
        ("transportPriority" | bool) ::
        ("pid" | uint(13)) ::
        ("scramblingControl" | uint2) ::
        ("adaptationFieldControl" | uint2) ::
        ("continuityCounter" | uint4))
  }.as[TransportStreamHeader]

  implicit val adaptationFieldFlags: Codec[AdaptationFieldFlags] = {
    ("discontinuity" | bool) ::
      ("randomAccess" | bool) ::
      ("priority" | bool) ::
      ("pcrFlag" | bool) ::
      ("opcrFlag" | bool) ::
      ("splicingPointFlag" | bool) ::
      ("transportPrivateDataFlag" | bool) ::
      ("adaptationFieldExtension" | bool)
  }.as[AdaptationFieldFlags]

  implicit val adaptationField: Codec[AdaptationField] =
    ("adaptation_flags" | adaptationFieldFlags)
      .flatPrepend { flags =>
        ("pcr" | conditional(flags.pcrFlag, bits(48))) ::
          ("opcr" | conditional(flags.opcrFlag, bits(48))) ::
          ("spliceCountdown" | conditional(flags.splicingPointFlag, int8))
      }
      .as[AdaptationField]

  implicit val mpegPacket: Codec[MpegPacket] =
    ("header" | transportStreamHeader)
      .flatPrepend { hdr =>
        ("adaptation_field" | conditional(hdr.adaptationFieldIncluded, adaptationField)) ::
          ("payload" | conditional(hdr.payloadIncluded, bytes(184)))
      }
      .as[MpegPacket]
}
