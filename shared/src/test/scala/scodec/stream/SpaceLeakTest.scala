package scodec.stream

import cats.implicits._
import fs2.{Fallible, Stream}

import org.scalacheck._
import Prop._
import scodec.codecs._

object SpaceLeakTest extends Properties("space-leak") {

  property("head of stream not retained") = secure {
    // make sure that head of stream can be garbage collected
    // as we go; this also checks for stack safety
    val ints = variableSizeBytes(int32, vector(int32))
    val N = 400000L
    val M = 5
    val chunk = (0 until M).toVector
    val dec = StreamDecoder.many(ints)
    val source = Stream(ints.encode(chunk).require).repeat
    val actual =
      source.through(dec.toPipe[Fallible]).take(N).flatMap(Stream.emits(_)).compile.foldMonoid
    actual == Right((0 until M).sum * N)
  }
}
