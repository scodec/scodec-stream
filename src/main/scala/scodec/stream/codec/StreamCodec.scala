// package scodec.stream
// package codec

// trait StreamCodec[A] extends StreamGenCodec[A,A] {

//   override def take(n: Int): StreamCodec[A] =
//     StreamCodec.instance(StreamEncoder.instance(encoder).take(n),
//                          StreamDecoder.instance(decoder).take(n))

//   override def many: StreamCodec[A] =
//     StreamCodec.instance(
//       StreamEncoder.instance(encoder).many,
//       StreamDecoder.instance(decoder).many)
// }

// object StreamCodec {

//   /** Create a `StreamCodec[A]` from a `StreamEncoder[A]` and `StreamDecoder[A]`. */
//   def instance[A](e: StreamEncoder[A], d: StreamDecoder[A]): StreamCodec[A] =
//     new StreamCodec[A] {
//       def encoder = e.encoder
//       def decoder = d.decoder
//     }

//   /** Conjure up a `StreamCodec[A]` from implicit scope. */
//   def apply[A](implicit A: StreamCodec[A]): StreamCodec[A] = A
// }
