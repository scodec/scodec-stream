package scodec.stream.decode

import org.scalacheck._
import Prop._
import scodec.codecs._

object StreamDecoderTest extends Properties("StreamDecoder") {

  property("many-ints") = forAll((ints: List[Int]) => {
    val bits = repeated(int32).encodeValid(ints.toIndexedSeq)
    once(int32).many.decode(bits).chunkAll.runLastOr(Vector()).run.toList == ints
  })
}

