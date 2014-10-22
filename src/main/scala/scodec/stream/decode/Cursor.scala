package scodec.stream.decode

import scalaz.stream.{Process,process1}
import scalaz.stream.{Process => P}
import scalaz.concurrent.Task
import scalaz.{\/,Catchable,Monad,MonadPlus}
import scalaz.\/.{left,right}
import scodec.Err
import scodec.bits.BitVector

case class Cursor[+A](run: BitVector => Err \/ (BitVector,A))

object Cursor {
  def ask: Cursor[BitVector] = Cursor { bits => right(bits -> bits) }
  def set(bits: BitVector) = Cursor { _ => right(bits -> bits) }
  def modify(f: BitVector => BitVector) = Cursor { bits =>
    val r = f(bits)
    right(r -> r)
  }
}
