package scodec.stream.encode

import scalaz.stream.{Process,Process1,process1}
import scodec.bits.BitVector

/**
 * A streaming encoding process, represented as a `Process1[A,BitVector]`.
 */
trait StreamEncoder[-A] {

  /**
   * The `Process1` backing this `StreamEncoder[A]`. All functions on `StreamEncoder`
   * are defined in terms of this `Process1`.
   */
  def encoder: Process1[A,BitVector]

  /** Modify the `Process1` backing this `StreamEncoder`. */
  final def edit[A2](f: Process1[A,BitVector] => Process1[A2,BitVector]): StreamEncoder[A2] =
    StreamEncoder { f(encoder) }

  /** Encode the input stream of `A` values using this `StreamEncoder`. */
  final def encode[F[_]](in: Process[F,A]): Process[F,BitVector] =
    in pipe encoder

  /** Transform the input type of this `StreamEncoder`. */
  final def contramap[A0](f0: A0 => A): StreamEncoder[A0] =
    contrapipe (process1.lift(f0))

  /** Transform the input type of this `StreamEncoder` using the given transducer. */
  final def contrapipe[A0](p: Process1[A0,A]): StreamEncoder[A0] =
    edit { p pipe _ }

  /** Encode values as long as there are more inputs. */
  def many: StreamEncoder[A] =
    edit { _.repeat }

  /** Encode at most `n` values. */
  def take(n: Int): StreamEncoder[A] =
    contrapipe { process1.take(n) }
}

object StreamEncoder {

  /** Create a `StreamEncoder` from the given `Process1`. */
  def apply[A](p: Process1[A,BitVector]): StreamEncoder[A] = new StreamEncoder[A] {
    def encoder = p
  }
}
