package scodec.stream

import scala.concurrent.ExecutionContext.Implicits.global

import cats.effect.{ContextShift, IO}
import org.scalacheck._
import Prop._
import fs2.Stream
import scodec.bits.BitVector
import scodec.{ Attempt, Decoder, Err }
import scodec.codecs._
import scodec.stream.{decode => D, encode => E}

object ScodecStreamSpec extends Properties("scodec.stream") {

  implicit val cs: ContextShift[IO] = IO.contextShift(global)

  property("many/tryMany") = {
    implicit val arbLong = Arbitrary(Gen.choose(1L,500L)) // chunk sizes

    forAll { (ints: List[Int], n: Long) =>
      val bits = vector(int32).encode(Vector.empty[Int] ++ ints).require
      val bits2 = E.many(int32).encodeAllValid(ints)
      bits == bits2 &&
      D.once(int32).many.decodeAllValid(bits).toList == ints &&
      D.tryMany(int32).decodeAllValid(bits2).toList == ints &&
      D.manyChunked(n.toInt)(int32).decodeAllValid(bits2).toList == ints &&
      D.tryManyChunked(n.toInt)(int32).decodeAllValid(bits2).toList == ints
    }
  }

  property("tryMany-example") = secure {
    val bits = E.many(int32).encodeAllValid(Vector(1,2,3))
    D.tryMany(int32).decodeAllValid(bits).toList == List(1,2,3) &&
    D.tryMany(int32).decode[IO](bits).compile.toVector.unsafeRunSync.toList == List(1,2,3)
  }

  property("many1") = forAll { (ints: List[Int]) =>
    val bits = E.many(int32).encodeAllValid(ints)
    D.many1(int32).decode[IO](bits).compile.toVector.attempt.unsafeRunSync.fold(
      err => ints.isEmpty,
      vec => vec.toList == ints
    )
  }

  property("isolate") = forAll { (ints: List[Int]) =>
    val bits = vector(int32).encode(ints.toVector).require
    val d =
      D.many(int32).isolate(bits.size).map(_ => 0) ++
      D.many(int32).isolate(bits.size).map(_ => 1)
    val res = d.decode[IO](bits ++ bits).compile.toVector.unsafeRunSync
    res == (Vector.fill(ints.size)(0) ++ Vector.fill(ints.size.toInt)(1))
  }

  property("or") = forAll { (ints: List[Int]) =>
    val bits = E.many(int32).encodeAllValid(ints.toIndexedSeq)
    val d1 = D.once(int32).many.or(D.empty)
    val d2 = D.empty.or(D.many(int32))
    val d3 = D.once(int32).many | D.once(int32).many
    val d4 = d3 or d1
    def fail(err: Err): Decoder[Nothing] = new Decoder[Nothing] {
      def decode(bits: BitVector) = Attempt.failure(err)
    }
    val failing = D.tryOnce(uint8.flatMap { _ => fail(Err("!!!")) })
    // NB: this fails as expected - since `once` does not backtrack
    // val failing = once(uint8.flatMap { _ => fail("!!!") })
    val d5 = failing or d1
    val d6 = d2 or failing

    List(d1,d2,d3,d4,d5,d6).forall { d =>
      d.decodeAllValid(bits).toList == ints
    }
  }

  val string = variableSizeBytes(int32, utf8)

  property("sepBy") = forAll { (ints: List[Int], delim: String) =>
    val e = listDelimited(string.encode(delim).require, int32)
    val encoded = e.encode(ints).require
    D.many(int32).sepBy(string).decodeAllValid(encoded).toList ?= ints
  }

  property("peek") = forAll { (strings: List[String]) =>
    val bits = E.once(string).many.encodeAllValid(strings)
    val d = D.many(string).peek ++ D.many(string)
    d.decodeAllValid(bits).toList == (strings ++ strings)
  }

  {
    val genChunkSize = Gen.choose(1L, 128L)
    include(new Properties("fixed size") {
      val genSmallListOfString = Gen.choose(0, 10).flatMap(n => Gen.listOfN(n, Gen.alphaStr))
      property("strings") = forAll(genSmallListOfString, genChunkSize) { (strings: List[String], chunkSize: Long) =>
        val bits = E.many(string).encodeAllValid(strings)
        val chunks = Stream.emits(bits.grouped(chunkSize).toSeq).covary[IO]
        (chunks through D.pipe[IO, String](implicitly, string)).compile.toList.unsafeRunSync == strings
      }
      val genSmallListOfInt = Gen.choose(0, 10).flatMap(n => Gen.listOfN(n, Arbitrary.arbitrary[Int]))
      property("ints") = forAll(genSmallListOfInt, genChunkSize) { (ints: List[Int], chunkSize: Long) =>
        val bits = E.many(int32).encodeAllValid(ints)
        val chunks = Stream.emits(bits.grouped(chunkSize).toSeq).covary[IO]
        (chunks through D.pipe[IO, Int](implicitly, int32)).compile.toList.unsafeRunSync == ints
      }
    }, "process.")
  }

  property("toLazyBitVector") = {
    forAll { (ints: List[Int]) =>
      val bvs = ints.map { i => int32.encode(i).require }
      toLazyBitVector(Stream.emits(bvs)) == bvs.foldLeft(BitVector.empty) { _ ++ _ }
    }
  }

  property("encode.emit") = forAll { (toEmit: Int, ints: List[Int]) =>
    val bv: BitVector = int32.encode(toEmit).require
    val e: StreamEncoder[Int] = E.emit[Int](bv)
    e.encode(Stream.emits(ints)).toList.foldLeft(BitVector.empty)(_ ++ _) == bv
  }
}
