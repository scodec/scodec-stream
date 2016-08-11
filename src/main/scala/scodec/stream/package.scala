package scodec

import fs2._
import fs2.async.mutable.Queue

import scodec.bits.BitVector

package object stream {
  type StreamDecoder[+A] = scodec.stream.decode.StreamDecoder[A]
  val StreamDecoder = scodec.stream.decode.StreamDecoder

  type StreamEncoder[A] = scodec.stream.encode.StreamEncoder[A]
  val StreamEncoder = scodec.stream.encode.StreamEncoder

  type StreamCodec[A] = scodec.stream.codec.StreamCodec[A]
  val StreamCodec = scodec.stream.codec.StreamCodec

  /** Constructs a lazy `BitVector` by continuously reading from the supplied stream until it halts. */
  def toLazyBitVector(in: Stream[Task, BitVector], bufferSize: Int = 100)(implicit strategy: Strategy): BitVector = {
    val queue = Queue.bounded[Task, Option[BitVector]](bufferSize).unsafeRun

    val fill: Task[Unit] = in.mask.noneTerminate.evalMap(queue.enqueue1).run
    fill.async.unsafeRunAsync(_ => ())

    BitVector.unfold(()) { _ => queue.dequeue1.unsafeRun.map { b => (b, ()) } }
  }
}
