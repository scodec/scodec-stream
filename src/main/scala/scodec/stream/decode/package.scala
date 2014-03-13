package scodec
package stream

import scalaz.stream.{Process,process1}
import scalaz.stream.{Process => P, Tee}
import scalaz.concurrent.Task
import scalaz.{\/,~>,Monad,MonadPlus}
import scodec.bits.BitVector

/**
 * Module containing various streaming decoding combinators.
 * Decoding errors are represented using [[scodec.stream.decode.DecodingError]].
 */
package object decode {

  /** The decoder that consumes no input and emits no values. */
  val halt: StreamDecoder[Nothing] =
    StreamDecoder { P.halt }

  /** The decoder that consumes no input and halts with the given error. */
  def fail(err: Throwable): StreamDecoder[Nothing] =
    StreamDecoder { P.fail(err) }

  /** The decoder that consumes no input and halts with the given error message. */
  def fail(msg: String): StreamDecoder[Nothing] =
    StreamDecoder { P.fail(DecodingError(msg)) }

  /** The decoder that consumes no input, emits the given `a`, then halts. */
  def emit[A](a: A): StreamDecoder[A] =
    StreamDecoder { P.emit(a) }

  /** The decoder that consumes no input, emits the given `A` values, then halts. */
  def emitAll[A](as: Seq[A]): StreamDecoder[A] =
    StreamDecoder { Process.emitAll(as) }

  /** Obtain the current input. This stream returns a single element. */
  def ask: StreamDecoder[BitVector] =
    StreamDecoder { Process.eval(Cursor.ask) }

  /** Advance the input by the given number of bits. */
  def drop(n: Long): StreamDecoder[BitVector] =
    StreamDecoder { Process.eval(Cursor.modify(_.drop(n))) }

  /** Advance the input by the given number of bits, purely as an effect. */
  def advance(n: Long): StreamDecoder[Nothing] =
    drop(n).edit(_.drain)

  /** Set the current cursor to the given `BitVector`. */
  def set(bits: BitVector): StreamDecoder[Nothing] =
    StreamDecoder { Process.eval_(Cursor.set(bits)) }

  /** Trim the input by calling `take(n)` on the input `BitVector`. */
  def take(n: Long): StreamDecoder[BitVector] =
    StreamDecoder { Process.eval(Cursor.modify(_.take(n))) }

  def decode[A](in: BitVector)(implicit A: Decoder[A]): StreamDecoder[A] =
    A.decode(in).fold(
      fail,
      { case (rem,a) => set(rem) ++ emit(a) }
    )

  /** Run the given `Decoder` once and emit its result, if successful. */
  def once[A](implicit A: Decoder[A]): StreamDecoder[A] =
    ask flatMap { decode[A] }

  /**
   * Like [[scodec.stream.decode.once]], but treats decoding failures
   * like normal termination. This is useful for allowing
   */
  def tryOnce[A](implicit A: Decoder[A]): StreamDecoder[A] = ask flatMap { in =>
    A.decode(in).fold(
      _ => halt,
      { case (rem,a) => set(rem) ++ emit(a) }
    )
  }

  /**
   * Runs `p1`, then runs `p2` if `p1` emits no elements and consumes
   * none of the input. Example `or(tryOnce(codecs.int32), once(codecs.uint32))`.
   */
  def or[A](p1: StreamDecoder[A], p2: StreamDecoder[A]) =
    ask flatMap { s0 =>
      val freshP2 = ask flatMap { s1 => if (s0 eq s1) p2 else halt }
      val joiner = // type annotation needed here for some reason :(
        (P.awaitL[A].repeat: Tee[A,A,A]).orElse(P.awaitR[A].repeat)
      p1.tee(freshP2)(joiner)
    }

  /**
   * Parse a stream of `A` values from the input, using the given decoder.
   * The returned stream terminates normally if the final value decoded
   * exhausts `in` and leaves no trailing bits. The returned stream terminates
   * with an error if the `Decoder[A]` ever fails on the input.
   */
  def many[A](implicit A: Decoder[A]): StreamDecoder[A] = ask flatMap { in =>
    if (in.isEmpty) halt
    else once(A) ++ many(A)
  }

  /**
   * Like [[scodec.stream.decode.many]], but fails with an error if no
   * elements are decoded. The returned stream will have at least one
   * element if it succeeds.
   */
  def many1[A:Decoder]: StreamDecoder[A] =
    nonEmpty("many1 given empty input")(many[A])

  /**
   * Like `many`, but parses and ignores a `D` delimiter value in between
   * decoding each `A` value.
   */
  def sepBy[A:Decoder,D:Decoder]: StreamDecoder[A] =
    once[A] flatMap { hd =>
      emit(hd) ++ many(Decoder[D] ~ Decoder[A]).map(_._2)
    }

  /**
   * Like `[[scodec.stream.decode.sepBy]]`, but fails with an error if no
   * elements are decoded. The returned stream will have at least one
   * element if it succeeds.
   */
  def sepBy1[A:Decoder,D:Decoder]: StreamDecoder[A] =
    nonEmpty ("sepBy1 given empty input") { sepBy[A,D] }

  private implicit class DecoderSyntax[A](A: Decoder[A]) {
    def ~[B](B: Decoder[B]): Decoder[(A,B)] = new Decoder[(A,B)] {
      def decode(bits: BitVector) = Decoder.decodeBoth(A,B)(bits)
    }
  }

  /**
   * Raises a decoding error if the given decoder emits no results,
   * otherwise runs `p` as normal.
   */
  def nonEmpty[A](messageIfEmpty: String)(p: StreamDecoder[A]): StreamDecoder[A] =
    p pipe {
      P.await1[A].flatMap(process1.init(_)).orElse(
      P.fail(DecodingError(messageIfEmpty)))
    }

//
//  ///**
//  // * If `p` terminates with a decoding error, halt normally and reset the
//  // * cursor back to the current position. If `p` succeeds, advance the
//  // * cursor as normal.
//  // */
//  //def attempt[A](p: StreamDecoder[A]): StreamDecoder[A] =
//  //  // this would be nice, but not possible with current scalaz-stream rep,
//  //  // which requires being in Task or some other Catchable to do a `.attempt()`
//  //  ???

}
