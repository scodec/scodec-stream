package scodec.stream

import cats.effect.{ExitCode, IO, IOApp}
import fs2.Stream
import scodec.Attempt.Successful
import scodec.bits.{BitVector, _}
import scodec.codecs._
import scodec.{DecodeResult, Decoder}

import scala.concurrent.duration._

object Fs2Scodec extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    val cafeMsg = hex"cafe"
    val babeMsg = hex"babe"

    val cafeDec = constant(cafeMsg).map(_ => "cafe")
    val babeDec = constant(babeMsg).map(_ => "babe")

    val choiceDec = Decoder.choiceDecoder(cafeDec, babeDec)

    val Successful(DecodeResult(cafeRes, BitVector.empty)) = choiceDec.decode(cafeMsg.bits)
    val Successful(DecodeResult(babeRes, BitVector.empty)) = choiceDec.decode(babeMsg.bits)

    def streamedRes[T](msg: ByteVector, dec: Decoder[T]) =
      Stream.emits(msg.toArray).covary[IO]
        .metered(100.millis)
        .through(StreamDecoder.tryMany(dec).toPipeByte)
        .compile.toList

    for {
      cafeChoiceStreamRes <- streamedRes(cafeMsg, choiceDec)
      cafeStreamRes <- streamedRes(cafeMsg, cafeDec)
      babeChoiceStreamRes <- streamedRes(babeMsg, choiceDec)
      babeStreamRes <- streamedRes(babeMsg, babeDec)
      _ <- IO.delay(println(
        s"""
           | cafeRes: $cafeRes
           | babeRes: $babeRes
           | cafeChoiceStreamRes: $cafeChoiceStreamRes
           | cafeStreamRes: $cafeStreamRes
           | babeChoiceStreamRes: $babeChoiceStreamRes
           | babeStreamRes: $babeStreamRes
           |""".stripMargin
      ))
    } yield ExitCode.Success
  }
}
