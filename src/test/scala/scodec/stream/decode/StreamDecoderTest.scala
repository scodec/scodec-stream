package scodec.stream.decode

import org.scalacheck._
import Prop._
import scalaz.\/._
import scodec.bits.BitVector
import scodec.Decoder
import scodec.codecs._

object StreamDecoderTest extends Properties("StreamDecoder") {

  property("many-ints") = forAll { (ints: List[Int]) =>
    val bits = repeated(int32).encodeValid(ints.toIndexedSeq)
    once(int32).many.decode(bits).chunkAll.runLastOr(Vector()).run.toList == ints
  }

  property("isolate") = forAll { (ints: List[Int]) =>
    val bits = repeated(int32).encodeValid(ints.toIndexedSeq)
    val p =
      many(int32).isolate(bits.size).map(_ => 0) ++
      many(int32).isolate(bits.size).map(_ => 1)
    val res = p.decode(bits ++ bits).chunkAll.runLastOr(Vector()).run
    res == (Vector.fill(ints.size)(0) ++ Vector.fill(ints.size.toInt)(1))
  }

  property("or") = forAll { (ints: List[Int]) =>
    val bits = repeated(int32).encodeValid(ints.toIndexedSeq)
    val p1 = once(int32).many.or(halt)
    val p2 = halt.or(many(int32))
    val p3 = once(int32).many | once(int32).many
    val p4 = p3 or p1
    def fail(msg: String): Decoder[Nothing] = new Decoder[Nothing] {
      def decode(bits: BitVector) = left(msg)
    }
    val failing = tryOnce(uint8.flatMap { _ => fail("!!!") })
    val p5 = failing or p1
    val p6 = p2 or failing

    List(p1,p2,p3,p4,p5,p6).forall { p =>
      p.decode(bits).chunkAll.runLastOr(Vector()).run.toList == ints
    }
  }
}

