package scodec.stream

import org.scalacheck._
import Prop._
import scalaz.\/._
import scalaz.stream.Process
import scodec.bits.BitVector
import scodec.Decoder
import scodec.codecs._
import scodec.stream.{decode => D, encode => E}

object ScodecStreamSpec extends Properties("scodec.stream") {

  property("many/tryMany") = forAll { (ints: List[Int]) =>
    val bits = repeated(int32).encodeValid(ints.toIndexedSeq)
    val bits2 = E.many(int32).encodeAllValid(ints)
    bits == bits2 &&
    D.once(int32).many.decodeAllValid(bits).toList == ints &&
    D.tryMany(int32).decodeAllValid(bits2).toList == ints
  }

  property("tryMany-example") = secure {
    val bits = E.many(int32).encodeAllValid(Vector(1,2,3))
    D.tryMany(int32).decodeAllValid(bits).toList == List(1,2,3) &&
    D.tryMany(int32).decode(bits).runLog.run.toList == List(1,2,3)
  }

  property("many1") = forAll { (ints: List[Int]) =>
    val bits = E.many(int32).encodeAllValid(ints)
    D.many1(int32).decode(bits).chunkAll.runLastOr(Vector()).attemptRun.fold(
      err => ints.isEmpty,
      vec => vec.toList == ints
    )
  }

  property("onComplete") = secure {
    val bits = E.many(int32).encodeAllValid(Vector(1,2,3))
    var cleanedUp = false
    val dec: StreamDecoder[Int] = D.many1(int32)
      .flatMap { _ => D.fail("oh noes!") }
      .onComplete { D.suspend { cleanedUp = true; D.halt }}
    cleanedUp == false &&
    dec.decode(bits).run.attemptRun.isLeft
  }

  property("isolate") = forAll { (ints: List[Int]) =>
    val bits = repeated(int32).encodeValid(ints.toIndexedSeq)
    val p =
      D.many(int32).isolate(bits.size).map(_ => 0) ++
      D.many(int32).isolate(bits.size).map(_ => 1)
    val res = p.decode(bits ++ bits).chunkAll.runLastOr(Vector()).run
    res == (Vector.fill(ints.size)(0) ++ Vector.fill(ints.size.toInt)(1))
  }

  property("or") = forAll { (ints: List[Int]) =>
    val bits = E.many(int32).encodeAllValid(ints.toIndexedSeq)
    val p1 = D.once(int32).many.or(D.halt)
    val p2 = D.halt.or(D.many(int32))
    val p3 = D.once(int32).many | D.once(int32).many
    val p4 = p3 or p1
    def fail(msg: String): Decoder[Nothing] = new Decoder[Nothing] {
      def decode(bits: BitVector) = left(msg)
    }
    val failing = D.tryOnce(uint8.flatMap { _ => fail("!!!") })
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
    val e = E.once(int32) ++ E.many(int32).mapBits(string.encodeValid(delim) ++ _)
    val encoded = e.encodeAllValid(ints)
    D.many(int32).sepBy(string).decodeAllValid(encoded).toList == ints
  }

  property("decodeResource") = forAll { (strings: List[String]) =>
    // make sure that cleanup action gets run
    import scodec.stream.{encode => E}
    val bits = E.once(string).many.encodeAllValid(strings)
    var cleanedUp = 0
    val decoded = D.many(string).decodeResource(())(_ => bits, _ => cleanedUp += 1)
    cleanedUp == 0 && // make sure we don't bump this strictly
    decoded.runLog.run.toList == strings && // normal termination
    decoded.take(2).runLog.run.toList == strings.take(2) && // early termination
    { // exceptions
      val failed = decoded.take(3).map(_ => { sys.error("die"); "fail" }).runLog.attemptRun.isLeft
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
    implicit val chunkSize = Arbitrary(Gen.choose(32,64).map(Chunk(_)))
    forAll { (ints: List[Int], chunkSize: Chunk) =>
      val bits = E.once(int32).many.encodeAllValid(ints)
      val chunks = Process.emitAll(bits.grouped(chunkSize.get)).toSource
      val d = D.process(int32)
      (chunks pipe d).runLog.run.toList == ints
    }
  }

  property("tryProcess") = {
    case class Chunk(get: Int)
    implicit val chunkSize = Arbitrary(Gen.choose(1,128).map(Chunk(_)))
    new Properties("fixed size") {
      property("strings") = forAll { (strings: List[String], chunkSize: Chunk) =>
        val bits = E.many(string).encodeAllValid(strings)
        val chunks = Process.emitAll(bits.grouped(chunkSize.get)).toSource
        val d = D.tryProcess(Long.MaxValue)(string)
        (chunks pipe d).runLog.run.toList == strings
      }
      property("ints") = forAll { (ints: List[Int], chunkSize: Chunk) =>
        val bits = E.many(int32).encodeAllValid(ints)
        val chunks = Process.emitAll(bits.grouped(chunkSize.get)).toSource
        val d = D.tryProcess(32)(int32)
        (chunks pipe d).runLog.run.toList == ints
      }
    }
  }
}

