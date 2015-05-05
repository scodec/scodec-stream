package scodec.stream.decode

import org.scalacheck._
import Prop._
import scalaz.stream.{Process,process1}
import scodec.{Codec,codecs => C}
import scodec.bits.BitVector

object SpaceLeakTest extends Properties("space-leak") {

  property("head of stream not retained") = secure {
    // make sure that head of stream can be garbage collected
    // as we go; this also checks for stack safety
    val ints =
      C.variableSizeBytes(C.int32, C.vector(C.int32))
    val N = 400000
    val M = 5
    val chunk = (0 until M).toVector
    def chunks = BitVector.unfold(0)(_ => Some(ints.encode(chunk).require -> 0))
    val dec = many(ints).take(N)
            . flatMap(chunk => emitAll(chunk))
            . pipe(process1.sum)

    val r = dec.decode(chunks)
    r.runLastOr(0).run == (0 until M).sum * N
  }
}
