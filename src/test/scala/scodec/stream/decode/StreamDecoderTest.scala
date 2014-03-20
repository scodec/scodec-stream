package scodec.stream.decode

import org.scalacheck._
import Prop._
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
}

