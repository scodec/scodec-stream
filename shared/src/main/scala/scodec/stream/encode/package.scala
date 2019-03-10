package scodec.stream

import cats.implicits._
import scodec.{ Encoder, Err }
import scodec.bits.BitVector
import fs2._

import shapeless.Lazy

package object encode {

  /** The encoder that consumes no input and halts with the given error. */
  def raiseError[A](err: Throwable): StreamEncoder[A] =
    StreamEncoder.instance[A] { _ => Pull.raiseError[StreamEncoder.Failable](err) }

  /** The encoder that consumes no input and halts with the given error message. */
  def raiseError[A](err: Err): StreamEncoder[A] =
    StreamEncoder.instance[A] { _ => Pull.raiseError[StreamEncoder.Failable](EncodingError(err)) }

  /** The encoder that consumes no input and emits no values. */
  def empty[A]: StreamEncoder[A] =
    StreamEncoder.instance[A] { _ => Pull.pure(None) }

  /** A `StreamEncoder` which encodes a stream of values. */
  def many[A](implicit A: Lazy[Encoder[A]]): StreamEncoder[A] =
    once[A].many

  /** A `StreamEncoder` which encodes a single value, then halts. */
  def once[A](implicit A: Lazy[Encoder[A]]): StreamEncoder[A] = StreamEncoder.instance[A] { s =>
    s.pull.uncons1.flatMap {
      case None => Pull.pure(None)
      case Some((a, s1)) =>
        A.value.encode(a).fold(
          e => Pull.raiseError[StreamEncoder.Failable](EncodingError(e)),
          b => Pull.output1(b)
        ) >> Pull.pure(Some(s1 -> empty))
    }
  }

  /** A `StreamEncoder` that emits the given `BitVector`, then halts. */
  def emit[A](bits: BitVector): StreamEncoder[A] =
    StreamEncoder.instance[A] { s => Pull.output1(bits) >> Pull.pure(Some(s -> empty[A])) }

  /**
   * A `StreamEncoder` which encodes a single value, then halts.
   * Unlike `once`, encoding failures are converted to normal termination.
   */
  def tryOnce[A](implicit A: Lazy[Encoder[A]]): StreamEncoder[A] = StreamEncoder.instance { s =>
    s.pull.uncons1.flatMap {
      case None => Pull.pure(None)
      case Some((a, s1)) =>
        A.value.encode(a).fold(
          e => Pull.pure(Some(s1 -> empty)),
          b => Pull.output1(b) >> Pull.pure(Some(s1 -> empty))
        )
    }
  }
}
