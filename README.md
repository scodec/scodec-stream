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

* [Where to get it](#-where-to-get-it)
* [Usage guide](#-guide)
* [API docs](https://scodec.github.io/scodec-stream/api)
* [Scodec mailing list](https://groups.google.com/forum/#!forum/scodec)
* [scodec-protocols](https://github.com/scodec/scodec-protocols) has useful streaming encoders and decoders for various domains and is a nice place to look for examples of the library in use.

### Where to get it

Add the following to your sbt build:

```Scala
libraryDependencies += "org.typelevel" %% "scodec-stream" % "1.0.0-SNAPSHOT"
```

There has not yet been an official (non-snapshot) scodec-stream release. Sign up for the [mailing list](https://groups.google.com/forum/#!forum/scodec) if you want to be notified of future releases.

### Guide

