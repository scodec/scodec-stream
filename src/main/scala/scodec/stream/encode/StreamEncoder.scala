package scodec.stream.encode

import scalaz.stream.{Process,Process1,process1,tee,Tee}
import scodec.bits.BitVector

/**
 * A streaming encoding process, represented as a `Process1[A,BitVector]`.
 */
trait StreamEncoder[-A] {

  /**
   * The `Process1` backing this `StreamEncoder[A]`. All functions on `StreamEncoder`
   * are defined in terms of this `Process1`.
   */
  def encoder: Process1[A,BitVector]

  /**
   * Encode the given sequence of `A` values to a `BitVector`, raising an exception
   * in the event of an encoding error.
   */
  def encodeAllValid(in: Seq[A]): BitVector =
    Process.emitAll(in).toSource.pipe(encoder)
           .fold(BitVector.empty)(_ ++ _)
           .runLastOr(BitVector.empty)
           .run

  /** Modify the `Process1` backing this `StreamEncoder`. */
  final def edit[A2](f: Process1[A,BitVector] => Process1[A2,BitVector]): StreamEncoder[A2] =
    StreamEncoder.instance { f(encoder) }

  /** Encode the input stream of `A` values using this `StreamEncoder`. */
  final def encode[F[_]](in: Process[F,A]): Process[F,BitVector] =
    in pipe encoder

  /** Transform the input type of this `StreamEncoder`. */
  final def contramap[A0](f0: A0 => A): StreamEncoder[A0] =
    contrapipe (process1.lift(f0))

  /** Transform the input type of this `StreamEncoder` using the given transducer. */
  final def contrapipe[A0](p: Process1[A0,A]): StreamEncoder[A0] =
    edit { p pipe _ }

  /** Transform the output `BitVector` values produced by this encoder. */
  def mapBits(f: BitVector => BitVector): StreamEncoder[A] =
    pipeBits(process1.lift(f))

  /** Statefully transform the output `BitVector` values produced by this encoder. */
  def pipeBits(f: Process1[BitVector,BitVector]): StreamEncoder[A] =
    edit { _ pipe f }

  /** Encode values as long as there are more inputs. */
  def many: StreamEncoder[A] =
    edit { _.repeat }

  /** Encode at most `n` values. */
  def take(n: Int): StreamEncoder[A] =
    contrapipe { process1.take(n) }

  /** Run this `StreamEncoder`, followed by `e`. */
  def ++[A2 <: A](e: StreamEncoder[A2]): StreamEncoder[A2] =
    edit { _ ++ e.encoder }

  /**
   * Convert this `StreamEncoder` to output bits in the given chunk size.
   * Only the last chunk may have fewer than `bitsPerChunk` bits.
   */
  def chunk(bitsPerChunk: Long): StreamEncoder[A] = {
    def chunker(acc: BitVector): Process1[BitVector,BitVector] = {
      if (acc.size >= bitsPerChunk)
        Process.emit(acc.take(bitsPerChunk)) ++ chunker(acc.drop(bitsPerChunk))
      else
        Process.receive1Or[BitVector, BitVector](Process.emit(acc))(bits => chunker(acc ++ bits))
    }
    this pipeBits { chunker(BitVector.empty) }
  }
}

object StreamEncoder {

  /** Create a `StreamEncoder` from the given `Process1`. */
  def instance[A](p: Process1[A,BitVector]): StreamEncoder[A] = new StreamEncoder[A] {
    def encoder = p
  }

  /** Conjure up a `StreamEncoder[A]` from implicit scope. */
  def apply[A](implicit A: StreamEncoder[A]): StreamEncoder[A] = A
}
