package scodec.stream.decode

import scodec.{ Attempt, DecodeResult }
import scodec.bits.BitVector

case class Cursor[+A](run: BitVector => Attempt[DecodeResult[A]])

object Cursor {
  def ask: Cursor[BitVector] = Cursor { current => Attempt.successful(DecodeResult(current, current)) }
  def set(bits: BitVector) = Cursor { _ => Attempt.successful(DecodeResult(bits, bits)) }
  def modify(f: BitVector => BitVector) = Cursor { current =>
    val modified = f(current)
    Attempt.successful(DecodeResult(modified, modified))
  }
}
