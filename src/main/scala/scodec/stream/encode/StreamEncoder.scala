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

  def encoder: StreamEncoder.Step[A]

  /**
   * Encode the given sequence of `A` values to a `BitVector`, raising an exception
   * in the event of an encoding error.
   */
  def encodeAllValid(in: Seq[A]): BitVector =
    encode(Stream.emits(in)).covary[Task].runFold(BitVector.empty)(_ ++ _).run.run

  /** Encode the input stream of `A` values using this `StreamEncoder`. */
  final def encode[F[_]](in: Stream[F, A]): Stream[F, BitVector] = {
    def substStep(s: Step[A]): Stream.Handle[F, A] => Pull[F, BitVector, (Stream.Handle[F, A], StreamEncoder[A])] =
      Sub1.subst[({type f[g[_],x] = Stream.Handle[g,x] => Pull[g, BitVector, (Stream.Handle[g,x], StreamEncoder[A])]})#f, Pure, F, A](s)

    def go(h: Stream.Handle[F, A], encoder: StreamEncoder[A]): Pull[F, BitVector, (Stream.Handle[F, A], Option[StreamEncoder[A]])] = {
      substStep(encoder.encoder)(h) flatMap { case (h1, next) => go(h1, next) }
    }
    in.open.flatMap(h => go(h, this)).run
  }

  /** Modify the `Pull` backing this `StreamEncoder`. */
  def edit[B](f: StreamEncoder.Step[A] => StreamEncoder.Step[B]): StreamEncoder[B] =
    StreamEncoder.instance { f(encoder) }

  def or(other: StreamEncoder[A]): StreamEncoder[A] =
    edit[A] { o => h => o(h) or other.encoder(h) }

  /** Encode values as long as there are more inputs. */
  def many: StreamEncoder[A] = this ++ many

  /** Run this `StreamEncoder`, followed by `e`. */
  def ++(e: => StreamEncoder[A]): StreamEncoder[A] =
    edit { o => h => o(h).map { case (h1, next) => (h1, next or e) }}

  /** Transform the input type of this `StreamEncoder`. */
  final def xmapc[B](f: A => B)(g: B => A): StreamEncoder[B] =
    edit { o => h => o(h.map(g)).map { case (h1, e1) => h1.map(f) -> e1.xmapc(f)(g) }}

  /** Encode at most `n` values. */
  def take(n: Int): StreamEncoder[A] = edit { step => h =>
    if (n <= 0) Pull.done
    else step(h) map { case (h, s2) => (h, s2.take(n-1)) }
  }
}

object StreamEncoder {

  type Step[A] = Stream.Handle[Pure, A] => Pull[Pure, BitVector, (Stream.Handle[Pure, A], StreamEncoder[A])]

  def instance[A](step: Step[A]): StreamEncoder[A] =
    new StreamEncoder[A] { val encoder = step }

  /** Conjure up a `StreamEncoder[A]` from implicit scope. */
  def apply[A](implicit A: StreamEncoder[A]): StreamEncoder[A] = A
}
