package scodec.stream.encode

import language.higherKinds

import fs2._
import Step.#:
import fs2.util.{ Catchable, Sub1, Task }
import scodec.bits.BitVector

/**
 * A streaming encoding process, represented as a `Pull[Pure, BitVector, StreamEncoder[A]]`.
 */
trait StreamEncoder[A] {
  import StreamEncoder._

  def step: StreamEncoder.Step[A]

  /**
   * Encode the given sequence of `A` values to a `BitVector`, raising an exception
   * in the event of an encoding error.
   */
  def encodeAllValid(in: Seq[A]): BitVector =
    encode(Process.emits(in)).covary[Task].runFold(BitVector.empty)(_ ++ _).run.run

  /** Encode the input stream of `A` values using this `StreamEncoder`. */
  final def encode[F[_]](in: Stream[F, A]): Stream[F, BitVector] = {
    def substStep(s: Step[A]): Stream.Handle[F, A] => Pull[F, BitVector, (Stream.Handle[F, A], StreamEncoder[A])] =
      Sub1.subst[({type f[g[_],x] = Stream.Handle[g,x] => Pull[g, BitVector, (Stream.Handle[g,x], StreamEncoder[A])]})#f, Pure, F, A](s)

    def go(h: Stream.Handle[F, A], encoder: StreamEncoder[A]): Pull[F, BitVector, (Stream.Handle[F, A], Option[StreamEncoder[A]])] = {
      substStep(encoder.step)(h) flatMap { case (h1, next) => go(h1, next) }
    }
    in.open.flatMap(h => go(h, this)).run
  }

  /** Modify the `Pull` backing this `StreamEncoder`. */
  def edit[B](f: StreamEncoder.Step[A] => StreamEncoder.Step[B]): StreamEncoder[B] =
    StreamEncoder.instance { f(step) }

  def or(other: StreamEncoder[A]): StreamEncoder[A] =
    edit[A] { o => h => o(h) or other.step(h) }

  /** Encode values as long as there are more inputs. */
  def many: StreamEncoder[A] = this ++ many

  /** Run this `StreamEncoder`, followed by `e`. */
  def ++(e: => StreamEncoder[A]): StreamEncoder[A] =
    edit { o => h => o(h).map { case (h1, next) => (h1, next or e) }}

  /** Transform the input type of this `StreamEncoder`. */
  final def xmapc[B](f: A => B)(g: B => A): StreamEncoder[B] =
    edit { o => h => o(h.map(g)).map { case (h1, e1) => h1.map(f) -> e1.xmap(f)(g) }}

  // /** Transform the input type of this `StreamEncoder`. */
  // final def contramap[A0](f0: A0 => A): StreamEncoder[A0] =
  //   contrapipe(process1.lift(f0))

  // /** Transform the input type of this `StreamEncoder` using the given transducer. */
  // final def contrapipe[A0](p: Process1[A0, A]): StreamEncoder[A0] =
  //   edit { p andThen _ }

  // /** Transform the output `BitVector` values produced by this encoder. */
  // def mapBits(f: BitVector => BitVector): StreamEncoder[A] =
  //   pipeBits(process1.lift(f))

  // /** Statefully transform the output `BitVector` values produced by this encoder. */
  // def pipeBits(f: Process1[BitVector, BitVector]): StreamEncoder[A] =
  //   edit { _ andThen f }

  // /** Encode at most `n` values. */
  // def take(n: Long): StreamEncoder[A] =
  //   contrapipe { process1.take(n) }

  // /**
  //  * Convert this `StreamEncoder` to output bits in the given chunk size.
  //  * Only the last chunk may have fewer than `bitsPerChunk` bits.
  //  */
  // def chunk(bitsPerChunk: Long): StreamEncoder[A] = {
  //   def chunker(acc: BitVector): Process1[BitVector,BitVector] = {
  //     if (acc.size >= bitsPerChunk)
  //       Process.emit(acc.take(bitsPerChunk)) ++ chunker(acc.drop(bitsPerChunk))
  //     else
  //       Process.receive1Or[BitVector, BitVector](Process.emit(acc))(bits => chunker(acc ++ bits))
  //   }
  //   this pipeBits { chunker(BitVector.empty) }
  // }
}

object StreamEncoder {

  type Step[A] = Stream.Handle[Pure, A] => Pull[Pure, BitVector, (Stream.Handle[Pure, A], StreamEncoder[A])]

  def instance[A](step: Step[A]): StreamEncoder[A] = {
    val s = step
    new StreamEncoder[A] { val step = s }
  }

  /** Conjure up a `StreamEncoder[A]` from implicit scope. */
  def apply[A](implicit A: StreamEncoder[A]): StreamEncoder[A] = A
}
