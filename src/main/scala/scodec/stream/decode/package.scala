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

  /** Obtain the current input. This stream returns a single element. */
  def ask: Process[Cursor,BitVector] = Process.eval(Cursor.ask)

  /** Advance the input by the given number of bits. */
  def drop(n: Long): Process[Cursor,BitVector] = Process.eval(Cursor.modify(_.drop(n)))

  /** Advance the input by the given number of bits, purely as an effect. */
  def advance(n: Long): Process[Cursor,Nothing] = drop(n).drain

  /** Set the current cursor to the given `BitVector`. */
  def set(bits: BitVector): Process[Cursor,Nothing] = Process.eval_(Cursor.set(bits))

  /** Trim the input by calling `take(n)` on the input `BitVector`. */
  def take(n: Long): Process[Cursor,BitVector] = Process.eval(Cursor.modify(_.take(n)))

  /**
   * Raises a decoding error if the given stream is empty, otherwise
   * returns `p` unaltered.
   */
  def nonEmpty[F[_],A](messageIfEmpty: String)(p: Process[F,A]): Process[F,A] =
    p pipe {
      P.await1[A].flatMap(process1.init(_)).orElse(fail(messageIfEmpty))
    }

  def decode[A](in: BitVector)(implicit A: Decoder[A]): Process[Cursor,A] =
    A.decode(in).fold(
      fail,
      { case (rem,a) => set(rem) fby P.emit(a) }
    )

  /** Run the given `Decoder` once and emit its result, if successful. */
  def once[A](implicit A: Decoder[A]): Process[Cursor,A] = ask flatMap { decode[A] }

  /**
   * Like [[scodec.stream.decode.once]], but treats decoding failures
   * like normal termination. This is useful for allowing
   */
  def tryOnce[A](implicit A: Decoder[A]): Process[Cursor,A] = ask flatMap { in =>
    A.decode(in).fold(
      _ => P.halt,
      { case (rem,a) => set(rem) fby P.emit(a) }
    )
  }

  /**
   * Runs `p1`, then runs `p2` if `p1` emits no elements and consumes
   * none of the input. Example `or(tryOnce(codecs.int32), once(codecs.uint32))`.
   */
  def or[A](p1: Process[Cursor,A], p2: Process[Cursor,A]) =
    ask flatMap { s0 =>
      val freshP2 = ask flatMap { s1 => if (s0 eq s1) p2 else P.halt }
      val joiner = // type annotation needed here for some reason :(
        (P.awaitL[A].repeat: Tee[A,A,A]).orElse(P.awaitR[A].repeat)
      p1.tee(freshP2)(joiner)
    }

  /**
   * Run the given streaming decoder on the input, returning the stream of
   * results. This function does not retain a reference to `bits` and allows
   * for `bits` to be garbage collected as the output stream is traversed.
   */
  def run[A](bits: => BitVector)(p: Process[Cursor,A]): Process[Task,A] = {
    var cur = bits
    def set(bs: BitVector): Task[BitVector] = Task.delay {
      cur = bs
      cur
    }
    p.translate(new (Cursor ~> Task) {
      def apply[A](c: Cursor[A]): Task[A] = Task.suspend {
        c.run(cur).fold(
          msg => Task.fail(DecodingError(msg)),
          { case (rem,a) => set(rem).flatMap { _ => Task.now(a) }}
        )
      }
    })
  }

  /**
   * Parse a stream of `A` values from the input, using the given decoder.
   * The returned stream terminates normally if the final value decoded
   * exhausts `in` and leaves no trailing bits. The returned stream terminates
   * with an error if the `Decoder[A]` ever fails on the input.
   */
  def many[A](implicit A: Decoder[A]): Process[Cursor,A] = ask flatMap { in =>
    if (in.isEmpty) P.halt
    else once(A) fby many(A)
  }

  /**
   * Like [[scodec.stream.decode.many]], but fails with an error if no
   * elements are decoded. The returned stream will have at least one
   * element if it succeeds.
   */
  def many1[A:Decoder]: Process[Cursor,A] =
    nonEmpty("many1 given empty input")(many[A])

  /**
   * Like `many`, but parses and ignores a `D` delimiter value in between
   * decoding each `A` value.
   */
  def sepBy[A:Decoder,D:Decoder]: Process[Cursor,A] =
    once[A] flatMap { hd =>
      P.emit(hd) ++ many(Decoder[D] ~ Decoder[A]).map(_._2)
    }

  /**
   * Like `[[scodec.stream.decode.sepBy]]`, but fails with an error if no
   * elements are decoded. The returned stream will have at least one
   * element if it succeeds.
   */
  def sepBy1[A:Decoder,D:Decoder]: Process[Cursor,A] =
    nonEmpty ("sepBy1 given empty input") { sepBy[A,D] }

  private implicit class DecoderSyntax[A](A: Decoder[A]) {
    def ~[B](B: Decoder[B]): Decoder[(A,B)] = new Decoder[(A,B)] {
      def decode(bits: BitVector) = Decoder.decodeBoth(A,B)(bits)
    }
  }

  /** A stream which immediately fails with the given decoding error message. */
  def fail(msg: String): Process[Nothing,Nothing] =
    P.fail(DecodingError(msg))
//
//  ///**
//  // * If `p` terminates with a decoding error, halt normally and reset the
//  // * cursor back to the current position. If `p` succeeds, advance the
//  // * cursor as normal.
//  // */
//  //def attempt[A](p: Process[Cursor,A]): Process[Cursor,A] =
//  //  // this would be nice, but not possible with current scalaz-stream rep,
//  //  // which requires being in Task or some other Catchable to do a `.attempt()`
//  //  ???

}
