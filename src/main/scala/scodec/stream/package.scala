package scodec

import scalaz.stream.{Process1, Process}
import scodec.bits.BitVector

package object stream {
  type StreamDecoder[+A] = scodec.stream.decode.StreamDecoder[A]
  val StreamDecoder = scodec.stream.decode.StreamDecoder

  type StreamEncoder[-A] = scodec.stream.encode.StreamEncoder[A]
}
