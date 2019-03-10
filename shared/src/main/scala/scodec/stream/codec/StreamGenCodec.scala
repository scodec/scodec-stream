package scodec.stream
package codec

trait StreamGenCodec[I,+O] extends StreamEncoder[I] with StreamDecoder[O] { self =>

  def editEncoder[I2](f: StreamEncoder[I] => StreamEncoder[I2]): StreamGenCodec[I2,O] =
    StreamGenCodec.instance(f(this), this)

  def editDecoder[O2](f: StreamDecoder[O] => StreamDecoder[O2]): StreamGenCodec[I,O2] =
    StreamGenCodec.instance(this, f(this))

  override def take(n: Long): StreamGenCodec[I,O] =
    StreamGenCodec.instance(
      StreamEncoder.instance(encoder).take(n),
      StreamDecoder.instance(decoder).take(n))

  override def many: StreamGenCodec[I,O] =
    StreamGenCodec.instance(
      StreamEncoder.instance(encoder).many,
      StreamDecoder.instance(decoder).many)

  /** Promote to a `StreamCodec[O]` given evidence that `I` and `O` are equal. */
  def fuse[OO >: O](implicit ev: OO =:= I): StreamCodec[OO] = new StreamCodec[OO] {
    def encoder = self.encoder.asInstanceOf[StreamEncoder.Step[OO]]
    def decoder = self.decoder
  }
}

object StreamGenCodec {

  /** Create a `StreamGenCodec` from a `StreamEncoder[I]` and a `StreamDecoder[O]`. */
  def instance[I,O](e: StreamEncoder[I], d: StreamDecoder[O]): StreamGenCodec[I,O] =
    new StreamGenCodec[I,O] {
      def encoder = e.encoder
      def decoder = d.decoder
    }
}
