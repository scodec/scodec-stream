/*
 * Copyright (c) 2013, Scodec
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package scodec.stream

import fs2._
import scodec.bits.BitVector
import scodec.{Encoder, Err}

/**
  * A streaming encoding process, represented as a `Stream[Pure, A] => Pull[Pure, BitVector, Option[(Stream[Pure, A], StreamEncoder[A])]]`.
  */
final class StreamEncoder[A] private (private val step: StreamEncoder.Step[A]) { self =>

  /**
    * Encode the given sequence of `A` values to a `BitVector`, raising an exception
    * in the event of an encoding error.
    */
  def encodeAllValid(in: Seq[A]): BitVector = {
    type ET[X] = Either[Throwable, X]
    encode[Fallible](Stream.emits(in))
      .compile[Fallible, ET, BitVector]
      .fold(BitVector.empty)(_ ++ _)
      .fold(e => throw e, identity)
  }

  /** Converts this encoder to a `Pipe[F, A, BitVector]`. */
  def toPipe[F[_]: RaiseThrowable]: Pipe[F, A, BitVector] =
    in => encode(in)

  /** Converts this encoder to a `Pipe[F, A, Byte]`. */
  def toPipeByte[F[_]: RaiseThrowable]: Pipe[F, A, Byte] =
    in => encode(in).flatMap(bits => Stream.chunk(Chunk.byteVector(bits.bytes)))

  /** Encodes the supplied stream of `A` values in to a stream of `BitVector`. */
  def encode[F[_]: RaiseThrowable](in: Stream[F, A]): Stream[F, BitVector] =
    apply(in).void.stream

  private def apply[F[_]: RaiseThrowable](
      in: Stream[F, A]
  ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] = {
    def loop(
        s: Stream[F, A],
        encoder: StreamEncoder[A]
    ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] =
      encoder.step[F](s).flatMap {
        case Some((s, next)) => loop(s, next)
        case None            => Pull.pure(None)
      }
    loop(in, this)
  }

  private def or(other: StreamEncoder[A]): StreamEncoder[A] =
    new StreamEncoder[A](
      new StreamEncoder.Step[A] {
        def apply[F[_]: RaiseThrowable](
            s: Stream[F, A]
        ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] =
          self.step(s).flatMap {
            case Some(x) => Pull.pure(Some(x))
            case None    => other.step(s)
          }
      }
    )

  /**
    * Creates a stream encoder that first encodes with this encoder and then when complete,
    * encodes the remainder with the supplied encoder.
    */
  def ++(that: => StreamEncoder[A]): StreamEncoder[A] =
    new StreamEncoder[A](
      new StreamEncoder.Step[A] {
        def apply[F[_]: RaiseThrowable](
            s: Stream[F, A]
        ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] =
          self.step(s).map(_.map { case (s1, next) => (s1, next.or(that)) })
      }
    )

  /** Encodes values as long as there are more inputs. */
  def repeat: StreamEncoder[A] = this ++ repeat

  /** Transform the input type of this `StreamEncoder`. */
  def xmapc[B](f: A => B)(g: B => A): StreamEncoder[B] =
    new StreamEncoder[B](
      new StreamEncoder.Step[B] {
        def apply[F[_]: RaiseThrowable](
            s: Stream[F, B]
        ): Pull[F, BitVector, Option[(Stream[F, B], StreamEncoder[B])]] =
          self.step(s.map(g)).map(_.map { case (s1, e1) => s1.map(f) -> e1.xmapc(f)(g) })
      }
    )
}

object StreamEncoder {

  private trait Step[A] {
    def apply[F[_]: RaiseThrowable](
        s: Stream[F, A]
    ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]]
  }

  /** Creates a stream encoder that consumes no values and emits no bits. */
  def empty[A]: StreamEncoder[A] =
    new StreamEncoder[A](new Step[A] {
      def apply[F[_]: RaiseThrowable](
          s: Stream[F, A]
      ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] =
        Pull.pure(None)
    })

  /** Creates a stream encoder that encodes a single value of input using the supplied encoder. */
  def once[A](encoder: Encoder[A]): StreamEncoder[A] =
    new StreamEncoder[A](new Step[A] {
      def apply[F[_]: RaiseThrowable](
          s: Stream[F, A]
      ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] =
        s.pull.uncons1.flatMap {
          case None => Pull.pure(None)
          case Some((a, s1)) =>
            encoder
              .encode(a)
              .fold(
                e => Pull.raiseError(CodecError(e)),
                b => Pull.output1(b)
              ) >> Pull.pure(Some(s1 -> empty))
        }
    })

  /** Creates a stream encoder that encodes all input values using the supplied encoder. */
  def many[A](encoder: Encoder[A]): StreamEncoder[A] = once(encoder).repeat

  /**
    * Creates a stream encoder which encodes a single value, then halts.
    * Unlike `once`, if an encoding failure occurs, the resulting stream is not terminated.
    */
  def tryOnce[A](encoder: Encoder[A]): StreamEncoder[A] =
    new StreamEncoder[A](new Step[A] {
      def apply[F[_]: RaiseThrowable](
          s: Stream[F, A]
      ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] =
        s.pull.uncons1.flatMap {
          case None => Pull.pure(None)
          case Some((a, s1)) =>
            encoder
              .encode(a)
              .fold(
                _ => Pull.pure(Some(s1.cons1(a) -> empty)),
                b => Pull.output1(b) >> Pull.pure(Some(s1 -> empty))
              )
        }
    })

  /**
    * Creates a stream encoder which encodes all input values, then halts.
    * Unlike `many`, if an encoding failure occurs, the resulting stream is not terminated.
    */
  def tryMany[A](encoder: Encoder[A]): StreamEncoder[A] = tryOnce(encoder).repeat

  /** Creates a stream encoder that emits the given `BitVector`, then halts. */
  def emit[A](bits: BitVector): StreamEncoder[A] =
    new StreamEncoder[A](new Step[A] {
      def apply[F[_]: RaiseThrowable](
          s: Stream[F, A]
      ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] =
        Pull.output1(bits) >> Pull.pure(Some(s -> empty[A]))
    })

  /** The encoder that consumes no input and halts with the given error. */
  def raiseError[A](err: Throwable): StreamEncoder[A] =
    new StreamEncoder[A](new Step[A] {
      def apply[F[_]: RaiseThrowable](
          s: Stream[F, A]
      ): Pull[F, BitVector, Option[(Stream[F, A], StreamEncoder[A])]] =
        Pull.raiseError(err)
    })

  /** The encoder that consumes no input and halts with the given error message. */
  def raiseError[A](err: Err): StreamEncoder[A] = raiseError(CodecError(err))
}
