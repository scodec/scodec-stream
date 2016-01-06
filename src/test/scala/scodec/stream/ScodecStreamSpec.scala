package scodec.stream

import org.scalacheck._
import Prop._
import fs2.{ Strategy, Stream }
import fs2.util.Task
import scodec.bits.BitVector
import scodec.{ Attempt, Decoder, Err }
import scodec.codecs._
import scodec.stream.{decode => D, encode => E}

object ScodecStreamSpec extends Properties("scodec.stream") {

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
    D.tryMany(int32).decode(bits).runLog.run.run.toList == List(1,2,3)
  }

  property("many1") = forAll { (ints: List[Int]) =>
    val bits = E.many(int32).encodeAllValid(ints)
    D.many1(int32).decode(bits).runLog.run.attemptRun.fold(
      err => ints.isEmpty,
      vec => vec.toList == ints
    )
  }

  property("onComplete") = secure {
    val bits = E.many(int32).encodeAllValid(Vector(1,2,3))
    var cleanedUp = false
    val dec: StreamDecoder[Int] = D.many1(int32)
      .flatMap { _ => D.fail(Err("oh noes!")) }
      .onComplete { D.suspend { cleanedUp = true; D.empty }}
    cleanedUp == false &&
    dec.decode(bits).runFold(())((_, _) => ()).run.attemptRun.isLeft
  }

  property("isolate") = forAll { (ints: List[Int]) =>
    val bits = vector(int32).encode(ints.toVector).require
    val p =
      D.many(int32).isolate(bits.size).map(_ => 0) ++
      D.many(int32).isolate(bits.size).map(_ => 1)
    val res = p.decode(bits ++ bits).runLog.run.run
    res == (Vector.fill(ints.size)(0) ++ Vector.fill(ints.size.toInt)(1))
  }

  property("or") = forAll { (ints: List[Int]) =>
    val bits = E.many(int32).encodeAllValid(ints.toIndexedSeq)
    val p1 = D.once(int32).many.or(D.empty)
    val p2 = D.empty.or(D.many(int32))
    val p3 = D.once(int32).many | D.once(int32).many
    val p4 = p3 or p1
    def fail(err: Err): Decoder[Nothing] = new Decoder[Nothing] {
      def decode(bits: BitVector) = Attempt.failure(err)
    }
    val failing = D.tryOnce(uint8.flatMap { _ => fail(Err("!!!")) })
    // NB: this fails as expected - since `once` does not backtrack
    // val failing = once(uint8.flatMap { _ => fail("!!!") })
    val p5 = failing or p1
    val p6 = p2 or failing

    List(p1,p2,p3,p4,p5,p6).forall { p =>
      p.decodeAllValid(bits).toList == ints
    }
  }

  val string = variableSizeBytes(int32, utf8)

  property("sepBy") = forAll { (ints: List[Int], delim: String) =>
    val e = listDelimited(string.encode(delim).require, int32)
    val encoded = e.encode(ints).require
    D.many(int32).sepBy(string).decodeAllValid(encoded).toList ?= ints
  }

  property("decodeResource") = forAll { (strings: List[String]) =>
    // make sure that cleanup action gets run
    import scodec.stream.{encode => E}
    val bits = E.once(string).many.encodeAllValid(strings)
    var cleanedUp = 0
    val decoded = D.many(string).decodeResource(())(_ => bits, _ => cleanedUp += 1)
    cleanedUp == 0 && // make sure we don't bump this strictly
    decoded.runLog.run.run.toList == strings && // normal termination
    decoded.take(2).runLog.run.run.toList == strings.take(2) && // early termination
    { // exceptions
      val failed = decoded.take(3).map(_ => sys.error("die")).runLog.run.attemptRun.isLeft
      strings.isEmpty || failed
    } &&
    cleanedUp == 3
  }

  property("peek") = forAll { (strings: List[String]) =>
    val bits = E.once(string).many.encodeAllValid(strings)
    val d = D.many(string).peek ++ D.many(string)
    d.decodeAllValid(bits).toList == (strings ++ strings)
  }

  property("process") = {
    case class Chunk(get: Int)
    implicit val chunkSize = Arbitrary(Gen.choose(1,128).map(Chunk(_)))
    new Properties("fixed size") {
      property("strings") = forAll { (strings: List[String], chunkSize: Chunk) =>
        val bits = E.many(string).encodeAllValid(strings)
        val chunks = Stream.emits(bits.grouped(chunkSize.get.toLong))
        val d = D.process(string)
        (chunks pipe d).toList == strings
      }
      property("ints") = forAll { (ints: List[Int], chunkSize: Chunk) =>
        val bits = E.many(int32).encodeAllValid(ints)
        val chunks = Stream.emits(bits.grouped(chunkSize.get.toLong))
        val d = D.process(int32)
        (chunks pipe d).toList == ints
      }
    }
  }

  property("toLazyBitVector") = {
    forAll { (ints: List[Int]) =>
      val bvs = ints.map { i => int32.encode(i).require }
      toLazyBitVector(Stream.emits(bvs))(Strategy.sequential) == bvs.foldLeft(BitVector.empty) { _ ++ _ }
    }
  }
}

