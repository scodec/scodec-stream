package scodec.stream

import scodec.{ Encoder, Err }
import scodec.bits.BitVector
import fs2._
import Step.#:

import shapeless.Lazy

package object encode {

  /** The encoder that consumes no input and halts with the given error. */
  def fail[A](err: Throwable): StreamEncoder[A] =
    StreamEncoder.instance[A] { _ => Pull.fail(err) }

  /** The encoder that consumes no input and halts with the given error message. */
  def fail[A](err: Err): StreamEncoder[A] =
    StreamEncoder.instance[A] { _ => Pull.fail(EncodingError(err)) }

  /** The encoder that consumes no input and emits no values. */
  def empty[A]: StreamEncoder[A] =
    StreamEncoder.instance[A] { _ => Pull.done }

  /** A `StreamEncoder` which encodes a stream of values. */
  def many[A](implicit A: Lazy[Encoder[A]]): StreamEncoder[A] =
    once[A].many

  /** A `StreamEncoder` which encodes a single value, then halts. */
  def once[A](implicit A: Lazy[Encoder[A]]): StreamEncoder[A] = StreamEncoder.instance[A] { h =>
    h.await1.flatMap {
      case a #: h1 =>
        A.value.encode(a).fold(
          e => Pull.fail(EncodingError(e)),
          b => Pull.output1(b)
        ) >> Pull.pure(h1 -> empty)
    }
  }

  // /** A `StreamEncoder` that emits the given `BitVector`, then halts. */
  // def emit(bits: BitVector): StreamEncoder[Nothing] =
  //   StreamEncoder.instance[Nothing] { Stream.emit(bits) }

  // /**
  //  * A `StreamEncoder` which encodes a single value, then halts.
  //  * Unlike `once`, encoding failures are converted to normal termination.
  //  */
  // def tryOnce[A](implicit A: Lazy[Encoder[A]]): StreamEncoder[A] = StreamEncoder.instance {
  //   Stream.await1[A].flatMap { a => A.value.encode(a).fold(_ => Stream.empty, Stream.emit) }
  // }
}
