// package scodec.stream

// import scodec.{ Encoder, Err }
// import scodec.bits.BitVector
// import scalaz.stream.{Process => P}

// import shapeless.Lazy

// package object encode {

//   /** The encoder that consumes no input and halts with the given error. */
//   def fail(err: Throwable): StreamEncoder[Nothing] =
//     StreamEncoder.instance[Nothing] { P.fail(err) }

//   /** The encoder that consumes no input and halts with the given error message. */
//   def fail(err: Err): StreamEncoder[Nothing] =
//     StreamEncoder.instance[Nothing] { P.fail(EncodingError(err)) }

//   /** The encoder that consumes no input and emits no values. */
//   val halt: StreamEncoder[Nothing] =
//     StreamEncoder.instance[Nothing] { P.halt }

//   /** A `StreamEncoder` which encodes a stream of values. */
//   def many[A](implicit A: Lazy[Encoder[A]]): StreamEncoder[A] =
//     once[A].many

//   /** A `StreamEncoder` which encodes a single value, then halts. */
//   def once[A](implicit A: Lazy[Encoder[A]]): StreamEncoder[A] = StreamEncoder.instance {
//     P.await1[A].flatMap { a => A.value.encode(a).fold(
//       msg => P.fail(EncodingError(msg)),
//       P.emit
//     )}
//   }

//   /** A `StreamEncoder` that emits the given `BitVector`, then halts. */
//   def emit(bits: BitVector): StreamEncoder[Nothing] =
//     StreamEncoder.instance[Nothing] { scalaz.stream.Process.emit(bits) }

//   /**
//    * A `StreamEncoder` which encodes a single value, then halts.
//    * Unlike `once`, encoding failures are converted to normal termination.
//    */
//   def tryOnce[A](implicit A: Lazy[Encoder[A]]): StreamEncoder[A] = StreamEncoder.instance {
//     P.await1[A].flatMap { a => A.value.encode(a).fold(
//       _ => P.halt,
//       P.emit
//     )}
//   }
// }
