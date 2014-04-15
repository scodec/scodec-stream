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

Decoding speeds have been observed at 100 MB/s for some realistic examples ([decoding MPEG packets from a `.pcap` file](https://github.com/scodec/scodec-stream/blob/master/src/test/scala/scodec/stream/examples/Mpeg.scala)), though decoding speed will in general depend on how fine-grained the decoding is.

#### Contents:

* [Where to get it](#where)
* [Usage guide](#guide)
* [API docs](https://scodec.github.io/scodec-stream/api)

### <a id="where"/> Where to get it

### <a id="guide"/> Guide
