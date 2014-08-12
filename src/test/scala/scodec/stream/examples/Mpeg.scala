package scodec.stream.examples

import scala.collection.immutable.IndexedSeq
import scala.concurrent.duration._
import scodec.bits._
import scodec._
import scodec.stream._
import scodec.stream.decode.DecodingError

import scalaz.std.AllInstances._
import scalaz.std.indexedSeq._
import scalaz.syntax.id._
import scalaz.stream.Process
import scalaz.concurrent.Task

import java.io.{ File, FileInputStream }

object Mpeg extends App {
  import PcapCodec._
  import MpegCodecs._

  val streamThroughRecordsOnly: StreamDecoder[MpegPacket] = {
    val pcapRecordStreamDecoder: StreamDecoder[PcapRecord] =
      decode.once[PcapHeader].flatMap { header =>
        decode.many(pcapRecord(header.ordering))
      }

    val mpegPcapDecoder: StreamDecoder[MpegPacket] = pcapRecordStreamDecoder flatMapP { record =>
      // Drop 22 byte ethernet frame header and 20 byte IPv4/udp header
      val datagramPayloadBits = record.data.drop(22 * 8).drop(20 * 8)
      val packets = codecs.repeated(Codec[MpegPacket]).decodeValue(datagramPayloadBits)
      Process.emitAll(packets.getOrElse(Seq.empty))
    }

    mpegPcapDecoder
  }

  val streamier: StreamDecoder[MpegPacket] = for {
    header <- decode.once[PcapHeader]
    packet <- decode.many(pcapRecordHeader(header.ordering)) flatMap { recordHeader =>
      decode.isolate(recordHeader.includedLength * 8) {
        // Drop 22 byte ethernet frame header and 20 byte IPv4/udp header
        decode.advance((22 + 20) * 8) ++
        // bail on this record if we fail to parse an `MpegPacket` from it
        decode.tryMany[MpegPacket]
      }
    }
  } yield packet

  def time[A](label: String)(a: => A): A = {
    val start = System.currentTimeMillis
    val result = a
    val stop = System.currentTimeMillis
    println(s"$label took ${(stop.toDouble-start) / 1000.0} s")
    result
  }
  print("Enter path to a large pcap mpeg file: ")
  val line = readLine()
  def channel = new FileInputStream(new File(line)).getChannel
  val result2 = time("coarse-grained") { streamThroughRecordsOnly.decodeMmap(channel).runFoldMap(_ => 1).run }
  val result1 = time("fine-grained") { streamier.decodeMmap(channel).runFoldMap(_ => 1).run }
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
    (bo: ByteOrdering) => if (bo == BigEndian) uint32.encode(magicNumber) else uint32L.encode(magicNumber),
    (buf: BitVector) => uint32.decode(buf).map { case (rest, mn) =>
      (rest, if (mn == magicNumber) BigEndian else LittleEndian)
    }
  )

  def gint16(implicit ordering: ByteOrdering): Codec[Int] = if (ordering == BigEndian) int16 else int16L
  def guint16(implicit ordering: ByteOrdering): Codec[Int] = if (ordering == BigEndian) uint16 else uint16L
  def gint32(implicit ordering: ByteOrdering): Codec[Int] = if (ordering == BigEndian) int32 else int32L
  def guint32(implicit ordering: ByteOrdering): Codec[Long] = if (ordering == BigEndian) uint32 else uint32L

  case class PcapHeader(ordering: ByteOrdering, versionMajor: Int, versionMinor: Int, thiszone: Int, sigfigs: Long, snaplen: Long, network: Long)

  implicit val pcapHeader: Codec[PcapHeader] = {
    ("magic_number"     | byteOrdering             ) >>:~ { implicit ordering =>
    ("version_major"    | guint16                  ) ::
    ("version_minor"    | guint16                  ) ::
    ("thiszone"         | gint32                   ) ::
    ("sigfigs"          | guint32                  ) ::
    ("snaplen"          | guint32                  ) ::
    ("network"          | guint32                  )
  }}.as[PcapHeader]


  case class PcapRecordHeader(timestampSeconds: Long, timestampMicros: Long, includedLength: Long, originalLength: Long) {
    def timestamp: Double = timestampSeconds + (timestampMicros / (1.second.toMicros.toDouble))
  }

  implicit def pcapRecordHeader(implicit ordering: ByteOrdering) = {
    ("ts_sec"           | guint32                  ) ::
    ("ts_usec"          | guint32                  ) ::
    ("incl_len"         | guint32                  ) ::
    ("orig_len"         | guint32                  )
  }.as[PcapRecordHeader]

  case class PcapRecord(header: PcapRecordHeader, data: BitVector)

  implicit def pcapRecord(implicit ordering: ByteOrdering) = {
    ("record_header"    | pcapRecordHeader                   ) >>:~ { hdr =>
    ("record_data"      | bits(hdr.includedLength.toInt * 8) ).hlist
  }}.as[PcapRecord]

  case class PcapFile(header: PcapHeader, records: IndexedSeq[PcapRecord])

  implicit val pcapFile = {
    pcapHeader >>:~ { hdr => repeated(pcapRecord(hdr.ordering)).hlist
  }}.as[PcapFile]
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
    adaptationFieldExtension: Boolean)

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
    ("syncByte"                  | constant(0x47)          ) :~>:
    ("transportStringIndicator"   | bool                    ) ::
    ("payloadUnitStartIndicator" | bool                    ) ::
    ("transportPriority"         | bool                    ) ::
    ("pid"                       | uint(13)                ) ::
    ("scramblingControl"         | uint2                   ) ::
    ("adaptationFieldControl"    | uint2                   ) ::
    ("continuityCounter"         | uint4                   )
  }.as[TransportStreamHeader]

  implicit val adaptationFieldFlags: Codec[AdaptationFieldFlags] = {
    ("discontinuity"             | bool                    ) ::
    ("randomAccess"              | bool                    ) ::
    ("priority"                  | bool                    ) ::
    ("pcrFlag"                   | bool                    ) ::
    ("opcrFlag"                  | bool                    ) ::
    ("splicingPointFlag"         | bool                    ) ::
    ("transportPrivateDataFlag"  | bool                    ) ::
    ("adaptationFieldExtension"  | bool                    )
  }.as[AdaptationFieldFlags]

  implicit val adaptationField: Codec[AdaptationField] = {
    ("adaptation_flags"          | adaptationFieldFlags                       ) >>:~ { flags =>
    ("pcr"                       | conditional(flags.pcrFlag, bits(48))       ) ::
    ("opcr"                      | conditional(flags.opcrFlag, bits(48))      ) ::
    ("spliceCountdown"           | conditional(flags.splicingPointFlag, int8) )
  }}.as[AdaptationField]

  implicit val mpegPacket: Codec[MpegPacket] = {
    ("header"                    | transportStreamHeader                                     ) >>:~ { hdr =>
    ("adaptation_field"          | conditional(hdr.adaptationFieldIncluded, adaptationField) ) ::
    ("payload"                   | conditional(hdr.payloadIncluded, bytes(184))              )
  }}.as[MpegPacket]
}
