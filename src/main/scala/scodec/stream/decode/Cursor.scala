package scodec.stream.decode

import cats.ApplicativeError
import scodec.{ Attempt, DecodeResult, Err }
import scodec.bits.BitVector

case class Cursor[+A](run: BitVector => Attempt[DecodeResult[A]])

object Cursor {
  def ask: Cursor[BitVector] = Cursor { current => Attempt.successful(DecodeResult(current, current)) }
  def set(bits: BitVector) = Cursor { _ => Attempt.successful(DecodeResult(bits, bits)) }
  def modify(f: BitVector => BitVector) = Cursor { current =>
    val modified = f(current)
    Attempt.successful(DecodeResult(modified, modified))
  }

  implicit val applicativeErrorInstance: ApplicativeError[Cursor, Throwable] = new ApplicativeError[Cursor, Throwable] {
    def pure[A](a: A) = Cursor(b => Attempt.successful(DecodeResult(a, b)))
    def ap[A, B](ff: Cursor[A => B])(fa: Cursor[A]): Cursor[B] =
      Cursor[B](in => ff.run(in) match {
        case Attempt.Successful(DecodeResult(f, rem)) =>
          fa.run(rem) match {
            case Attempt.Successful(DecodeResult(a, rem2)) =>
              Attempt.successful(DecodeResult(f(a), rem2))
            case Attempt.Failure(err) => Attempt.failure(err)
          }
        case Attempt.Failure(err) => Attempt.failure(err)
      })
    def raiseError[A](e: Throwable) = Cursor(_ => Attempt.failure(ExceptionErr(e, Nil)))
    def handleErrorWith[A](fa: Cursor[A])(f: Throwable => Cursor[A]): Cursor[A] =
      Cursor[A](in => fa.run(in).fold({
        case t: Throwable => f(t).run(in)
        case other => Attempt.failure(other)
      }, Attempt.successful))
  }

  final case class ExceptionErr(t: Throwable, context: List[String]) extends Err {
    def message: String = t.getMessage
    def pushContext(ctx: String) = copy(context = ctx :: context)
  }
}
