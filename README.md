scodec-stream
=============

Scodec-stream is a library for streaming binary encoding and decoding. It is built atop [scodec](https://github.com/scodec/scodec) and [fs2](https://github.com/functional-streams-for-scala/fs2). Here's a brief example of its use:

```Scala
import scodec.codecs._
import scodec.stream._
import scodec.bits._
import cats.effect.{Blocker, IO}
import fs2.Stream

val frames: StreamDecoder[ByteVector] = StreamDecoder.many(int32)
 .flatMap { numBytes => StreamDecoder.once(bytes(numBytes)) }

val filePath = java.nio.file.Paths.get("path/to/file")

val s: Stream[IO, ByteVector] =
  Stream.resource(Blocker[IO]).flatMap { blocker =>
    fs2.io.file.readAll[IO](filePath, blocker, 4096).through(frames.toPipeByte)
  }
```

When consumed, `s` will incrementally read chunks from `"largefile.bin"`, then decode a stream of frames, where each frame is expected to begin with a number of bytes specified as a 32-bit signed int (the `int32` codec), followed by a frame payload of that many bytes. Nothing happens until the `s` stream is consumed, and `s` will ensure the file is closed in the event of an error or normal termination of the consumer.

See the [MPEG PCAP decoding example](https://github.com/scodec/scodec-stream/blob/main/jvm/src/test/scala/scodec/stream/examples/Mpeg.scala) for a more sophisticated use case.

__Links:__

* [Administrative](#admin)
* [Getting Binaries](#getting-binaries)
* [API docs][api]
* [scodec-protocols](https://github.com/scodec/scodec-protocols) has useful streaming encoders and decoders for various domains and is a nice place to look for examples of the library in use.

[api]: http://www.google.com/?q=scodec-stream+api

### Administrative

This project is licensed under a [3-clause BSD license](LICENSE).

People are expected to follow the [Typelevel Code of Conduct](http://typelevel.org/conduct.html)
when discussing scodec on the Github page, Gitter channel, mailing list,
or other venues.

Concerns or issues can be sent to Michael Pilquist (*mpilquist@gmail.com*) or
to [Typelevel](http://typelevel.org/about.html).

### Getting Binaries

See the [releases page on the website](http://scodec.org/releases/).

### Code of Conduct ###

See the [Code of Conduct](CODE_OF_CONDUCT.md).

