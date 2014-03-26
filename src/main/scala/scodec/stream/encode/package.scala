package scodec.stream

import scodec.Encoder
import scalaz.stream.{Process => P}

package object encode {

  /** The encoder that consumes no input and halts with the given error. */
  def fail(err: Throwable): StreamEncoder[Nothing] =
    StreamEncoder.instance { P.fail(err) }

  /** The encoder that consumes no input and halts with the given error message. */
  def fail(msg: String): StreamEncoder[Nothing] =
    StreamEncoder.instance { P.fail(EncodingError(msg)) }

  /** The encoder that consumes no input and emits no values. */
  val halt: StreamEncoder[Nothing] =
    StreamEncoder.instance { P.halt }

  /** A `StreamEncoder` which encodes a single value, then halts. */
  def once[A](implicit A: Encoder[A]): StreamEncoder[A] = StreamEncoder.instance {
    P.await1[A].flatMap { a => A.encode(a).fold(
      msg => P.fail(EncodingError(msg)),
      P.emit
    )}
  }

  /**
   * A `StreamEncoder` which encodes a single value, then halts.
   * Unlike `once`, encoding failures are converted to normal termination.
   */
  def tryOnce[A](implicit A: Encoder[A]): StreamEncoder[A] = StreamEncoder.instance {
    P.await1[A].flatMap { a => A.encode(a).fold(
      _ => P.halt,
      P.emit
    )}
  }
}
