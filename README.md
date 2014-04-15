scodec-stream
=============


Scodec-stream is a library for streaming binary encoding and decoding. It is built atop [scodec](https://github.com/scodec/scodec) and [scalaz-stream](https://github.com/scalaz/scalaz-stream). Here's a brief example of its use:

```Scala
import scodec.{codecs => C}
import scodec.stream.{decode => D, StreamDecoder}
import scodec.bits.ByteVector

val frames: StreamDecoder[ByteVector] = D.once(C.int32)
 .flatMap { numBytes => D.once(C.bytes(numBytes)).isolate(numBytes) }
 .many

val s: Process[scalaz.concurrent.Task, ByteVector] =
  frames.decodeMmap(new java.io.FileInputStream("largefile.bin").getChannel)
```

When consumed, `s` will memory map in the contents of `"largefile.bin"`, then decode a stream of frames, where each frame is expected to begin with a number of bytes specified as a 32-bit signed int (the `int32` codec), followed by a frame payload of that many bytes. Nothing happens until the `s` stream is consumed, and `s` will ensure the `FileChannel` is closed in the event of an error or normal termination of the consumer. See [the guide](#guide) for further information and discussion of streaming encoding.

Decoding speeds have been observed at 100 MB/s for some realistic examples ([decoding MPEG packets from a `.pcap` file](https://github.com/scodec/scodec-stream/blob/master/src/test/scala/scodec/stream/examples/Mpeg.scala)), though decoding speed will generally depend on how fine-grained the decoding is.

__Links:__

* [Where to get it](#where-to-get-it)
* [Usage guide](#guide)
* [API docs][api]
* [Scodec mailing list](https://groups.google.com/forum/#!forum/scodec)
* [scodec-protocols](https://github.com/scodec/scodec-protocols) has useful streaming encoders and decoders for various domains and is a nice place to look for examples of the library in use.

[api]: http://www.google.com/?q=scodec-stream+api

### Where to get it

Add the following to your sbt build:

```Scala
libraryDependencies += "org.typelevel" %% "scodec-stream" % "1.0.0-SNAPSHOT"
```

There has not yet been an official (non-snapshot) scodec-stream release. Sign up for the [mailing list](https://groups.google.com/forum/#!forum/scodec) if you want to be notified of future releases.

### Guide

The library provides two main types, [`scodec.stream.StreamDecoder[A]`][dec], with helper functions in the [`scodec.stream.decode` package object][dec], and [`scodec.stream.StreamEncoder[A]`][enc], with helper functions in the [`scodec.stream.encode` package object][enc-pkg]. The [`scodec.stream.StreamCodec[A]`][codec] type just pairs an encoder with a decoder.

[dec]: https://github.com/scodec/scodec-stream/blob/master/src/main/scala/scodec/stream/decode/StreamDecoder.scala
[dec-src]: https://github.com/scodec/scodec-stream/blob/master/src/main/scala/scodec/stream/decode/package.scala
[enc]: https://github.com/scodec/scodec-stream/blob/master/src/main/scala/scodec/stream/encode/StreamEncoder.scala
[enc-pkg]: https://github.com/scodec/scodec-stream/blob/master/src/main/scala/scodec/stream/encode/package.scala
[codec]: https://github.com/scodec/scodec-stream/blob/master/src/main/scala/scodec/stream/codec/StreamCodec.scala

#### Decoding

The model of a `StreamDecoder[A]` is a `Process[Cursor,A]`, where [`Cursor[X]`][cursor] is a `BitVector => String \/ (BitVector,X)` state action. Thus, a `StreamDecoder[A]` produces a stream of `A` values, using a [`BitVector`](https://github.com/scodec/scodec-bits) input that it may inspect and update as it emits values. The 'current' `BitVector` is sometimes called the 'cursor position' or just 'cursor' throughout this documentation.

[cursor]: https://github.com/scodec/scodec-stream/blob/master/src/main/scala/scodec/stream/decode/Cursor.scala

The combinators for building `StreamDecoder` are fairly typical of what one might see in a monadic parser combinator library. Assuming we've imported `scodec.stream.{decode,StreamDecoder}` and `scodec.codecs`:

* `decode.once`: A `scodec.Decoder[A] => StreamDecoder[A]` which promotes a regular (strict) decoder to a `StreamDecoder` which decodes a single value, emits it, advances the cursor by the number of decoded bits, then halts. Any decoding failures are raised in the output stream, wrapped in [`DecodingError`][dec-err]. Example: `decode.once(codecs.int32)` has type `StreamDecoder[Int]`, and parses a single signed 32-bit integer.
* `decode.tryOnce`: A `scodec.Decoder[A] => StreamDecoder[A]` which promotes a regular (strict) decoder to a `StreamDecoder`. Unlike `decode.once`, decoding failures are not raised. Instead, the cursor position is left at its current location and the stream halts with no emitted elements.
* `d1 or d2` (alternately `d1 | d2`): Runs `d1`, and if it emits no elements and raises no errors, runs `d2`. Example: `decode.tryOnce(codecs.variableSizeBytes(codecs.int32L, codecs.utf8)) | decode.emit("Joe Sixpack")` will try parsing UTF-8 encoded string, which begins with a length in bytes encoded as a little-endian encoded `Int`, followed by that many bytes for the string itself, and if this fails, will leave the cursor at its current location and emit the string `"Joe Sixpack"`.
* `d.many`: Run `d` for as long as there is nonempty input, emitting the stream of decoded values. Example, `decode.once(codecs.int64).many` will parse a stream of 64-bit signed integers. This can also be written as `decode.many(codecs.int64)`.
* `d.isolate(numBits)` and `d.isolateBytes(numBytes)`: Useful when constructing nested decoders, runs `d` on the first `numBits` number of bits or `numBytes` number of bytes of the input, then advances the cursor by that many bits or bytes.
* `d flatMap f`: Given a `d: StreamDecoder[A]` and an `f: A => StreamDecoder[B]`, produce a `StreamDecoder[B]` by running `d`, then using each resulting `A` to choose a `StreamDecoder[B]` to run to produce the output. For example, `decode.many(int32) flatMap { n => decode.once(codecs.fixedSizeBytes(n, codecs.utf8)) }` will be a `StreamDecoder[String]` which produces a stream of `String` outputs by repeatedly reading a signed 32-bit integer, `n`, then decoding a UTF-8 encoded string consisting of `n` bytes.
* `d.peek`: Run `d: StreamDecoder[A]`, but after `d` completes, reset the cursor to its original, pre-`d` position.

There are also various combinators for statefully transforming the output of a `StreamDecoder[A]`, or interleaving multiple decoders, namely `d pipe proc` (alternately `d |> proc`) and `(d1 tee d2)(f)`. See the [API docs][api] for more details.

##### Running decoders

A decoder, `d: StreamDecoder[A]` may be run on an input `bits: BitVector` via `d.decode(bits)`, and there are various convenience functions, including `decodeInputStream`, `decodeMmap`, and `decodeChannel`, for safely decoding from a `java.io.InputStream`, a memory-mapped file, or `java.nio.ReadableByteChannel`. These convenience functions will allocate a lazy `BitVector` backed by the given source and ensure the input is closed when the consumer finishes consuming the decoded stream.

Decoders are given the full input starting from the current cursor location - any chunking of the input `BitVector` is not visible to decoders, which simplifies their implementation as decoders need not worry about being given partial input. Thus, when promoting a `d: Decoder[A]` to a `StreamDecoder[A]` via `decode.once(d)`, `d` will be run on the full remainder of the current input. (Calling `decode.once(d).isolate(42)` will give `d` only the first 42 bits of the input, starting from the current cursor location, and will advance the cursor by 42 bits on completion.)

When a `StreamDecoder[A]` advances the cursor, the head of the underlying `BitVector` may be garbage collected, making it easy to write decoders that operate in constant memory. There are a few caveats:

* `d.peek` will retain the current cursor location in memory for as long as `d` takes to run, so it can reset this cursor location after `d` completes. Thus, it should be used only in restricted scopes, like `decode.once(codecs.int32).peek`, which reads a single `Int` and leaves it unconsumed. (Whereas `decode.many(int32).peek` will read an entire stream of `Int` values, and leave them unconsumed, requiring as much memory as is needed to represent all the integers in the stream)
* `decode.ask: StreamDecoder[BitVector]` obtains the full input starting from the current cursor location. Obviously, stashing this `BitVector` somewhere may prevent the head of the `BitVector` from being garbage collected.

[dec-err]: https://github.com/scodec/scodec-stream/blob/master/src/main/scala/scodec/stream/decode/DecodingError.scala

#### Encoding

