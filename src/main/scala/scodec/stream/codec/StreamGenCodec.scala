package scodec.stream

case class StreamGenCodec[-A,+B](
  encoder: StreamEncoder[A],
  decoder: StreamDecoder[B]
)
