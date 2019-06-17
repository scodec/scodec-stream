package scodec.stream.encode

import language.higherKinds

import fs2._
import scodec.bits.BitVector

/**
 * A streaming encoding process, represented as a `Stream[Pure, A] => Pull[Pure, BitVector, Option[(Stream[Pure, A], StreamEncoder[A])]]`.
 */
trait StreamEncoder[A] {

  def encoder: StreamEncoder.Step[A]

  /**
   * Encode the given sequence of `A` values to a `BitVector`, raising an exception
   * in the event of an encoding error.
   */
  def encodeAllValid(in: Seq[A]): BitVector =
    encode(Stream.emits(in)).compile.fold(BitVector.empty)(_ ++ _)

  /** Encode the input stream of `A` values using this `StreamEncoder`. */
  final def encode[F[_]](in: Stream[F, A]): Stream[F, BitVector] = {
    def go(s: Stream[F, A], encoder: StreamEncoder[A]): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] = {
      encoder.encoder.asInstanceOf[Stream[F,A] => Pull[F,BitVector,Option[(Stream[F,A],StreamEncoder[A])]]].apply(s) flatMap {
        case Some((s, next)) => go(s, next)
        case None => Pull.pure(None)
      }
    }
    go(in, this).stream
  }

  /** Modify the `Pull` backing this `StreamEncoder`. */
  def edit[B](f: StreamEncoder.Step[A] => StreamEncoder.Step[B]): StreamEncoder[B] =
    StreamEncoder.instance { f(encoder) }

  def or(other: StreamEncoder[A]): StreamEncoder[A] =
    edit[A] { o => s => o(s).flatMap { case Some(x) => Pull.pure(Some(x)); case None => other.encoder(s) } }

  /** Encode values as long as there are more inputs. */
  def many: StreamEncoder[A] = this ++ many

  /** Run this `StreamEncoder`, followed by `e`. */
  def ++(e: => StreamEncoder[A]): StreamEncoder[A] =
    edit { o => s => o(s).map { _.map { case (s1, next) => (s1, next or e) }}}

  /** Transform the input type of this `StreamEncoder`. */
  final def xmapc[B](f: A => B)(g: B => A): StreamEncoder[B] =
    edit { o => s => o(s.map(g)).map { _.map { case (s1, e1) => s1.map(f) -> e1.xmapc(f)(g) }}}

  /** Encode at most `n` values. */
  def take(n: Long): StreamEncoder[A] = edit { step => s =>
    if (n <= 0) Pull.pure(None)
    else step(s) map { _.map { case (s, s2) => (s, s2.take(n-1)) }}
  }
}

object StreamEncoder {

  type Step[A] = Stream[Pure, A] => Pull[Failable, BitVector, Option[(Stream[Pure, A], StreamEncoder[A])]]

  type Failable[A] = Either[Throwable, A]

  def instance[A](step: Step[A]): StreamEncoder[A] =
    new StreamEncoder[A] { val encoder = step }

  /** Conjure up a `StreamEncoder[A]` from implicit scope. */
  def apply[A](implicit A: StreamEncoder[A]): StreamEncoder[A] = A
}
