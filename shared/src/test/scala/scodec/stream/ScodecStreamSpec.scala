/*
 * Copyright (c) 2013, Scodec
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package scodec.stream

import org.scalacheck._
import Prop._
import fs2.{Fallible, Stream}
import scodec.Err
import scodec.bits._
import scodec.codecs._

object ScodecStreamSpec extends Properties("scodec.stream") {

  property("many/tryMany") = forAll { (ints: List[Int]) =>
    val bits = vector(int32).encode(Vector.empty[Int] ++ ints).require
    val bits2 = StreamEncoder.many(int32).encodeAllValid(ints)
    bits == bits2 &&
    StreamDecoder.many(int32).decode[Fallible](Stream(bits)).toList == Right(ints) &&
    StreamDecoder.tryMany(int32).decode[Fallible](Stream(bits2)).toList == Right(ints)
  }

  property("many/tryMany-insufficient") = secure {
    val bits = hex"00000001 00000002 0000".bits
    StreamDecoder.many(int32).decode[Fallible](Stream(bits)).toList == Right(List(1, 2))
    StreamDecoder.tryMany(int32).decode[Fallible](Stream(bits)).toList == Right(List(1, 2))
  }

  property("tryMany-example") = secure {
    val bits = StreamEncoder.many(int32).encodeAllValid(Vector(1, 2, 3))
    StreamDecoder.tryMany(int32).decode[Fallible](Stream(bits)).toList == Right(List(1, 2, 3))
  }

  property("many + flatMap + tryMany") = secure {
    val decoder = StreamDecoder.many(bits(4)).flatMap { a =>
      StreamDecoder.tryMany(
        bits(4).flatMap { b =>
          if (b == bin"0111") scodec.codecs.fail[BitVector](Err(""))
          else scodec.codecs.provide(b)
        }
      )
    }
    val actual = decoder
      .decode[Fallible](Stream.emits(hex"1a bc d7 ab 7a bc".toArray.map(BitVector(_))))
      .compile
      .fold(BitVector.empty)(_ ++ _)
    actual == Right(hex"abcdababc".bits.drop(4))
  }

  property("isolate") = forAll { (ints: List[Int], _: Long) =>
    val bits = vector(int32).encode(ints.toVector).require
    val d =
      StreamDecoder.many(int32).isolate(bits.size).map(_ => 0) ++
        StreamDecoder.many(int32).isolate(bits.size).map(_ => 1)
    val s = Stream(bits ++ bits)
    d.decode[Fallible](s).toVector == Right(
      Vector.fill(ints.size)(0) ++ Vector.fill(ints.size.toInt)(1)
    )
  }

  def genChunkSize = Gen.choose(1L, 128L)
  def genSmallListOfString = Gen.choose(0, 10).flatMap(n => Gen.listOfN(n, Gen.alphaStr))
  property("list-of-fixed-size-strings") = forAll(genSmallListOfString, genChunkSize) {
    (strings: List[String], chunkSize: Long) =>
      val bits = StreamEncoder.many(utf8_32).encodeAllValid(strings)
      val chunks = Stream.emits(BitVector.GroupedOp(bits).grouped(chunkSize).toSeq).covary[Fallible]
      chunks.through(StreamDecoder.many(utf8_32).toPipe).toList == Right(strings)
  }

  def genSmallListOfInt = Gen.choose(0, 10).flatMap(n => Gen.listOfN(n, Arbitrary.arbitrary[Int]))
  property("list-of-fixed-size-ints") = forAll(genSmallListOfInt, genChunkSize) {
    (ints: List[Int], chunkSize: Long) =>
      val bits = StreamEncoder.many(int32).encodeAllValid(ints)
      val chunks = Stream.emits(BitVector.GroupedOp(bits).grouped(chunkSize).toSeq).covary[Fallible]
      chunks.through(StreamDecoder.many(int32).toPipe).toList == Right(ints)
  }

  property("encode.emit") = forAll { (toEmit: Int, ints: List[Int]) =>
    val bv: BitVector = int32.encode(toEmit).require
    val e: StreamEncoder[Int] = StreamEncoder.emit[Int](bv)
    e.encode(Stream.emits(ints).covary[Fallible]).compile.fold(BitVector.empty)(_ ++ _) == Right(bv)
  }

  property("encode.tryOnce") = secure {
    (StreamEncoder.tryOnce(fail[Int](Err("error"))) ++ StreamEncoder.many(int8))
      .encode(Stream(1, 2).covary[Fallible])
      .toList == Right(List(hex"01".bits, hex"02".bits))
  }
}
