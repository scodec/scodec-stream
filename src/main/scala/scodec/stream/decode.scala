package scodec
package stream

import scalaz.stream.{Process,process1}
import scalaz.stream.{Process => P}
import scalaz.concurrent.Task
import scodec.bits.BitVector

/**
 * Module containing various streaming decoding combinators.
 * Decoding errors are represented using [[scodec.stream.decode.DecodingError]].
 */
object decode {

  /**
   * Convert decoding errors in the given stream to normal termination.
   * Other errors are raised as normal within the stream.
   */
  def attempt[A](p: Process[Task,A]): Process[Task,A] =
    p.attempt().flatMap {
      _.fold(
        { case e@DecodingError(_) => P.halt
          case e: Throwable => P.fail(e)
        },
        P.emit
      )
    }

  /**
   * Raises a decoding error if the given stream is empty, otherwise
   * returns `p` unaltered.
   */
  def nonEmpty[A](messageIfEmpty: String)(p: Process[Task,A]): Process[Task,A] =
    p pipe {
      P.await1[A].flatMap(process1.init(_)).orElse(fail(messageIfEmpty))
    }

  /**
   * Parse a stream of `A` values from the input, using the given decoder.
   * The returned stream terminates normally if the final value decoded
   * exhausts `in` and leaves no trailing bits. The returned stream terminates
   * with an error if the `Decoder[A]` ever fails on the input.
   */
  def many[A](in: BitVector)(implicit A: Decoder[A]): Process[Task,A] =
    if (in.isEmpty) P.halt
    else P.eval(Task.delay(A.decode(in))).flatMap {
      _.fold(fail, { case (rem,a) => many(rem)(A) })
    }

  /**
   * Like [[scodec.stream.decode.many]], but fails with an error if no
   * elements are decoded. The returned stream will have at least one
   * element if it succeeds.
   */
  def many1[A:Decoder](in: BitVector): Process[Task,A] =
    nonEmpty("many1 given empty input")(many[A](in))

  /**
   * Like [[scodec.stream.decode.many]], but treats decoding errors as
   * normal termination of the stream. Other errors are raised as normal
   * within the stream.
   */
  def tryMany[A:Decoder](in: BitVector): Process[Task,A] =
    attempt { many[A](in) }

  /**
   * Like [[scodec.stream.decode.tryMany]], but fails with an error if no
   * elements are decoded. The returned stream will have at least one
   * element if it succeeds.
   */
  def tryMany1[A:Decoder](in: BitVector): Process[Task,A] =
    attempt { many1[A](in) }

  /**
   * Like `many`, but parses and ignores a `D` delimiter value in between
   * decoding each `A` value.
   */
  def sepBy[A:Decoder,D:Decoder](in: BitVector): Process[Task,A] =
    if (in.isEmpty) P.halt
    else Process.eval(Task.delay(Decoder[A].decode(in))).flatMap {
      _.fold(
        fail,
        { case (rem,hd) => many(rem)(Decoder[D] ~ Decoder[A])
                          .map(_._2)
                          .pipe(process1.init(hd))
        }
      )
    }

  /**
   * Like `[[scodec.stream.decode.sepBy]]`, but fails with an error if no
   * elements are decoded. The returned stream will have at least one
   * element if it succeeds.
   */
  def sepBy1[A:Decoder,D:Decoder](in: BitVector): Process[Task,A] =
    nonEmpty ("sepBy1 given empty input") { sepBy[A,D](in) }

  /**
   * Like [[scodec.stream.decode.sepBy]], but treats decoding errors as
   * normal termination of the stream. Other errors are raised as normal
   * within the stream.
   */
  def trySepBy[A:Decoder,D:Decoder](in: BitVector): Process[Task,A] =
    attempt { sepBy[A,D](in) }

  /**
   * Like [[scodec.stream.decode.trySepBy]], but fails with an error if no
   * elements are decoded. The returned stream will have at least one
   * element if it succeeds.
   */
  def trySepBy1[A:Decoder,D:Decoder](in: BitVector): Process[Task,A] =
    attempt { sepBy1[A,D](in) }

  implicit class DecoderSyntax[A](A: Decoder[A]) {
    def ~[B](B: Decoder[B]): Decoder[(A,B)] = new Decoder[(A,B)] {
      def decode(bits: BitVector) = Decoder.decodeBoth(A,B)(bits)
    }
  }

  def unchunk(in: Bitstream): BitVector = ???

  /** A stream which immediately fails with the given decoding error message. */
  def fail(msg: String): Process[Nothing,Nothing] =
    P.fail(DecodingError(msg))

  case class DecodingError(message: String) extends Exception(message)
}
