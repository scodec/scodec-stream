package scodec.stream.decode

import cats.implicits._
import cats.effect.IO

import org.scalacheck._
import Prop._
import scodec.codecs._
import scodec.bits.BitVector

object SpaceLeakTest extends Properties("space-leak") {

  property("head of stream not retained") = secure {
    // make sure that head of stream can be garbage collected
    // as we go; this also checks for stack safety
    val ints = variableSizeBytes(int32, vector(int32))
    val N = 400000L
    val M = 5
    val chunk = (0 until M).toVector
    def chunks = BitVector.unfold(0)(_ => Some(ints.encode(chunk).require -> 0))
    val dec = many(ints).take(N).
      flatMap(chunk => emits(chunk)).
      through(_.foldMonoid)

    val r = dec.decode[IO](chunks)
    r.runFold(0)((_, last) => last).unsafeRunSync == (0 until M).sum * N
  }
}
