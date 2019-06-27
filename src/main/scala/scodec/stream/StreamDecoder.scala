package scodec.stream

import language.higherKinds

import fs2._
import scodec.{ Attempt, Decoder, DecodeResult, Err }
import scodec.bits.BitVector

final class StreamDecoder[+A](private val step: StreamDecoder.Step[A]) { self =>

  import StreamDecoder._

  def toPipe[F[_]: RaiseThrowable]: Pipe[F, BitVector, A] = decode(_)

  def toPipeByte[F[_]: RaiseThrowable]: Pipe[F, Byte, A] =
    in => in.chunks.map(_.toBitVector).through(toPipe)

  def decode[F[_]: RaiseThrowable](s: Stream[F, BitVector]): Stream[F, A] =
    apply(s).void.stream

  def apply[F[_]: RaiseThrowable](s: Stream[F, BitVector]): Pull[F, A, Option[Stream[F, BitVector]]] =
    step match {
      case Empty => Pull.pure(Some(s))
      case Result(a) => Pull.output1(a).as(Some(s))
      case Failed(cause) => Pull.raiseError(cause)
      case Append(x, y) => x(s).flatMap {
        case None => Pull.pure(None)
        case Some(rem) => y()(rem)
      }

      case Decode(decoder, once, failOnErr) =>
        def loop(carry: BitVector, s: Stream[F, BitVector]): Pull[F, A, Option[Stream[F, BitVector]]] = {
          s.pull.uncons1.flatMap {
            case Some((hd, tl)) =>
              val buffer = carry ++ hd
              decoder(buffer) match {
                case Attempt.Successful(DecodeResult(value, remainder)) =>
                  val next = if (remainder.isEmpty) tl else tl.cons1(remainder)
                  val p = value(next)
                  if (once) p else p.flatMap {
                    case Some(next) => loop(BitVector.empty, next)
                    case None => Pull.pure(None)
                  }
                case Attempt.Failure(e: Err.InsufficientBits) =>
                  loop(buffer, tl)
                case Attempt.Failure(e) =>
                  if (failOnErr) Pull.raiseError(CodecError(e))
                  else Pull.pure(Some(Stream(buffer)))
              }
            case None => if (carry.isEmpty) Pull.pure(None) else Pull.pure(Some(Stream(carry)))
          }
        }
        loop(BitVector.empty, s)

      case Isolate(bits, decoder) =>
        def loop(carry: BitVector, s: Stream[F, BitVector]): Pull[F, A, Option[Stream[F, BitVector]]] = {
          s.pull.uncons1.flatMap {
            case Some((hd, tl)) =>
              val (buffer, remainder) = (carry ++ hd).splitAt(bits)
              if (buffer.size == bits)
                decoder[F](Stream(buffer)) >> Pull.pure(Some(tl.cons1(remainder)))
              else loop(buffer, tl)
            case None => if (carry.isEmpty) Pull.pure(None) else Pull.pure(Some(Stream(carry)))
          }
        }
        loop(BitVector.empty, s)
    }

  def flatMap[B](f: A => StreamDecoder[B]): StreamDecoder[B] = new StreamDecoder[B](
    self.step match {
      case Empty => Empty
      case Result(a) => f(a).step
      case Failed(cause) => Failed(cause)
      case Decode(g, once, failOnErr) => Decode(in => g(in).map(_.map(_.flatMap(f))), once, failOnErr)
      case Isolate(bits, decoder) => Isolate(bits, decoder.flatMap(f))
      case Append(x, y) => Append(x.flatMap(f), () => y().flatMap(f))
    }
  )

  def map[B](f: A => B): StreamDecoder[B] = flatMap(a => StreamDecoder.pure(f(a)))

  def ++[A2 >: A](that: => StreamDecoder[A2]): StreamDecoder[A2] =
    new StreamDecoder(Append(this, () => that))

  def isolate(bits: Long): StreamDecoder[A] = StreamDecoder.isolate(bits)(this)

  def strict: Decoder[Vector[A]] = new Decoder[Vector[A]] {
    def decode(bits: BitVector): Attempt[DecodeResult[Vector[A]]] = {
      type ET[X] = Either[Throwable, X]
      self.map(Left(_)).apply[Fallible](Stream(bits))
        .flatMap { remainder => remainder.map { r => r.map(Right(_)).pull.echo }.getOrElse(Pull.done) }
        .stream
        .compile[Fallible, ET, Either[A, BitVector]]
        .fold((Vector.empty[A], BitVector.empty)) { case ((acc, rem), entry) =>
          entry match {
            case Left(a) => (acc :+ a, rem)
            case Right(r2) => (acc, rem ++ r2)
          }
        }
        .fold({
          case CodecError(e) => Attempt.failure(e)
          case other => Attempt.failure(Err.General(other.getMessage, Nil))
        }, { case (acc, rem) => Attempt.successful(DecodeResult(acc, rem))})
    }
  }
}

object StreamDecoder {
  private sealed trait Step[+A]
  private final case object Empty extends Step[Nothing]
  private final case class Result[A](value: A) extends Step[A]
  private final case class Decode[A](f: BitVector => Attempt[DecodeResult[StreamDecoder[A]]], once: Boolean, failOnErr: Boolean) extends Step[A]
  private final case class Isolate[A](bits: Long, decoder: StreamDecoder[A]) extends Step[A]
  private final case class Append[A](x: StreamDecoder[A], y: () => StreamDecoder[A]) extends Step[A]
  private final case class Failed(cause: Throwable) extends Step[Nothing]

  val empty: StreamDecoder[Nothing] = new StreamDecoder[Nothing](Empty)

  def pure[A](a: A): StreamDecoder[A] = new StreamDecoder[A](Result(a))

  def once[A](decoder: Decoder[A]): StreamDecoder[A] =
    new StreamDecoder[A](Decode(in => decoder.decode(in).map(_.map(pure)), once = true, failOnErr = true))

  def many[A](decoder: Decoder[A]): StreamDecoder[A] =
    new StreamDecoder[A](Decode(in => decoder.decode(in).map(_.map(pure)), once = false, failOnErr = true))

  def tryOnce[A](decoder: Decoder[A]): StreamDecoder[A] =
    new StreamDecoder[A](Decode(in => decoder.decode(in).map(_.map(pure)), once = true, failOnErr = false))
  
  def tryMany[A](decoder: Decoder[A]): StreamDecoder[A] =
    new StreamDecoder[A](Decode(in => decoder.decode(in).map(_.map(pure)), once = false, failOnErr = false))

  def raiseError[A](cause: Throwable): StreamDecoder[A] = new StreamDecoder[A](Failed(cause))

  def emits[A](as: Iterable[A]): StreamDecoder[A] =
    as.foldLeft(empty: StreamDecoder[A])((acc, a) => acc ++ pure(a))

  def isolate[A](bits: Long)(decoder: StreamDecoder[A]): StreamDecoder[A] =
    new StreamDecoder(Isolate(bits, decoder))

  def ignore(bits: Long): StreamDecoder[Nothing] =
    once(scodec.codecs.ignore(bits)).flatMap(_ => empty)
}

