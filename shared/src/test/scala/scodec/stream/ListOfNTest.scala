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
import cats.effect.SyncIO
import fs2._
import scodec.codecs._

// test for https://github.com/scodec/scodec-stream/issues/58
object ListOfNTest extends Properties("list-of-n") {

  val codec = listOfN(uint16, uint16)
  val pipe = StreamDecoder.many(codec).toPipeByte[SyncIO]

  val ints = (1 to 7).toList
  val encodedBytes = Chunk.array(codec.encode(ints).require.toByteArray)

  property("non-split chunk") = {
    val source = Stream.chunk(encodedBytes)
    val decodedList = source.through(pipe).compile.lastOrError.unsafeRunSync()
    decodedList == ints
  }
  property("split chunk") = {
    val (splitChunk1, splitChunk2) = encodedBytes.splitAt(6)
    val splitSource = Stream.chunk(splitChunk1) ++ Stream.chunk(splitChunk2)
    val decodedList = splitSource.through(pipe).compile.lastOrError.unsafeRunSync()
    decodedList == ints
  }
}
