package scodec.stream.codec

import scodec.stream.encode._
import scodec.stream.decode._

trait StreamGenCodec[-I,+O] extends StreamEncoder[I] with StreamDecoder[O] {

  def editEncoder[I2](f: StreamEncoder[I] => StreamEncoder[I2]): StreamGenCodec[I2,O] =
    StreamGenCodec(f(this), this)

  def editDecoder[O2](f: StreamDecoder[O] => StreamDecoder[O2]): StreamGenCodec[I,O2] =
    StreamGenCodec(this, f(this))

  override def take(n: Int): StreamGenCodec[I,O] =
    StreamGenCodec(StreamEncoder(encoder).take(n), StreamDecoder(decoder).take(n))

  override def many: StreamGenCodec[I,O] =
    StreamGenCodec(StreamEncoder(encoder).many, StreamDecoder(decoder).many)
}

object StreamGenCodec {

  def apply[I,O](e: StreamEncoder[I], d: StreamDecoder[O]): StreamGenCodec[I,O] =
    new StreamGenCodec[I,O] {
      def encoder = e.encoder
      def decoder = d.decoder
    }
}
