package scodec.stream.decode

import java.io.InputStream
import java.nio.channels.{FileChannel, ReadableByteChannel}
import scalaz.{~>,\/,MonadPlus}
import scalaz.stream.{Process,Process1,process1,Tee}
import scalaz.concurrent.Task
import scodec.Decoder
import scodec.bits.BitVector

/**
 * A streaming decoding process, represented as a stream of state
 * actions on [[scodec.bits.BitVector]]. Most clients will typically
 * use one of the decoding convenience methods on this class, rather
 * than using `process` directly.
 */
case class StreamDecoder[+A](process: Process[Cursor,A]) {

  import scodec.stream.{decode => D}

  /**
   * Decoding a stream of `A` values from the given `BitVector`.
   * This function does not retain a reference to `bits`, allowing
   * it to be be garbage collected as the returned stream is traversed.
   */
  final def decode(bits: => BitVector): Process[Task,A] = Process.suspend {
    @volatile var cur = bits // am pretty sure this doesn't need to be volatile, but just being safe
    def set(bs: BitVector): Task[BitVector] = Task.delay {
      cur = bs
      cur
    }
    process.translate(new (Cursor ~> Task) {
      def apply[A](c: Cursor[A]): Task[A] = Task.suspend {
        c.run(cur).fold(
          msg => Task.fail(DecodingError(msg)),
          { case (rem,a) => set(rem).flatMap { _ => Task.now(a) }}
        )
      }
    })
  }

  /**
   * Resource-safe version of `decode`. Acquires a resource,
   * decodes a stream of values, and releases the resource when
   * the returned `Process[Task,A]` is finished being consumed.
   * The `acquire` and `release` actions may be asynchronous.
   */
  final def decodeAsyncResource[R](acquire: Task[R])(
      read: R => BitVector,
      release: R => Task[Unit]): Process[Task,A] =
    Process.eval(acquire).flatMap { r =>
      decode(read(r)) onComplete { Process.eval_(release(r)) }
    }

  /**
   * Resource-safe version of `decode`. Acquires a resource,
   * decodes a stream of values, and releases the resource when
   * the returned `Process[Task,A]` is finished being consumed.
   * If the `acquire` and `release` actions are asynchronous, use
   * [[decodeAsyncResource]].
   */
  final def decodeResource[R](acquire: => R)(
      read: R => BitVector,
      release: R => Unit): Process[Task,A] =
    decodeAsyncResource(Task.delay(acquire))(read, r => Task.delay(release(r)))

  /**
   * Resource-safe version of `decode` for an `InputStream` resource.
   * This is just a convenience function which calls [[decodeResource]], using
   * [[scodec.bits.BitVector.fromInputStream]] as the `read` function, and which
   * closes `in` after the returned `Process[Task,A]` is consumed.
   */
  final def decodeInputStream(
      in: => InputStream,
      chunkSizeInBytes: Int = 1024 * 1000 * 16): Process[Task,A] =
    decodeResource(in)(BitVector.fromInputStream(_, chunkSizeInBytes), _.close)

  /**
   * Resource-safe version of `decode` for a `ReadableByteChannel` resource.
   * This is just a convenience function which calls [[decodeResource]], using
   * [[scodec.bits.BitVector.fromChannel]] as the `read` function, and which
   * closes `in` after the returned `Process[Task,A]` is consumed.
   */
  final def decodeChannel(
      in: => ReadableByteChannel,
      chunkSizeInBytes: Int = 1024 * 1000 * 16,
      direct: Boolean = false): Process[Task,A] =
    decodeResource(in)(BitVector.fromChannel(_, chunkSizeInBytes, direct), _.close)

  /**
   * Resource-safe version of `decode` for a `ReadableByteChannel` resource.
   * This is just a convenience function which calls [[decodeResource]], using
   * [[scodec.bits.BitVector.fromChannel]] as the `read` function, and which
   * closes `in` after the returned `Process[Task,A]` is consumed.
   */
  final def decodeMmap(
      in: => FileChannel,
      chunkSizeInBytes: Int = 1024 * 1000 * 16): Process[Task,A] =
    decodeResource(in)(BitVector.fromMmap(_, chunkSizeInBytes), _.close)

  /**
   * Run this `StreamDecoder`, then `d`, then concatenate the two streams.
   */
  final def ++[A2>:A](d: => StreamDecoder[A2]): StreamDecoder[A2] =
    edit { _ ++ d.process }

  /** Modify the `Process[Cursor,A]` backing this `StreamDecoder`. */
  final def edit[B](f: Process[Cursor,A] => Process[Cursor,B]): StreamDecoder[B] =
    StreamDecoder { f(process) }

  /**
   * Monadic bind for this `StreamDecoder`. Runs a stream decoder for each `A`
   * produced by this `StreamDecoder`, then concatenates all the resulting
   * streams of results. This is the same 'idea' as `List.flatMap`.
   */
  final def flatMap[B](f: A => StreamDecoder[B]): StreamDecoder[B] =
    flatMapP(f andThen (_.process))

  /**
   * Like `flatMap`, but takes a function that produces a `Process[Cursor,B]`.
   */
  final def flatMapP[B](f: A => Process[Cursor,B]): StreamDecoder[B] =
    edit { _ flatMap f }

  /**
   * Run this `StreamDecoder` zero or more times until the input is exhausted.
   */
  final def many: StreamDecoder[A] = {
    import scodec.stream.{decode => D}
    val step: StreamDecoder[A] =
      D.ask.flatMap { bits =>
        if (bits.isEmpty) D.halt
        else this
      }
    step edit { _.repeat }
  }

  /**
   * Run this `StreamDecoder` one or more times until the input is exhausted.
   */
  final def many1: StreamDecoder[A] =
    this.many.nonEmpty("many1 produced no outputs")

  /**
   * Transform the output of this `StreamDecoder` using the function `f`.
   */
  final def map[B](f: A => B): StreamDecoder[B] =
    edit { _ map f }

  /**
   * Transform the output of this `StreamDecoder`, converting left values
   * to decoding failures.
   */
  final def mapEither[B](f: A => String \/ B): StreamDecoder[B] =
    this.flatMap { a => f(a).fold(D.fail, D.emit) }

  /**
   * Raises a decoding error if the given decoder emits no results,
   * otherwise runs `p` as normal.
   */
  final def nonEmpty(messageIfEmpty: String): StreamDecoder[A] =
    pipe {
      Process.await1[A].flatMap(process1.init(_)).orElse(
      Process.fail(DecodingError(messageIfEmpty)))
    }

  /**
   * Alias for `scodec.stream.decode.or(this,d)`.
   * Runs `this`, then runs `d` if `this` emits no elements.
   * Example: `tryOnce(codecs.int32).or(once(codecs.uint32))`.
   * This function does no backtracking of its own; any desired
   * backtracking should be handled by `this`.
   */
  final def or[A2>:A](d: StreamDecoder[A2]): StreamDecoder[A2] =
    scodec.stream.decode.or(this, d)

  /** Operator alias for `this.or(d)`. */
  final def |[A2>:A](d: StreamDecoder[A2]): StreamDecoder[A2] =
    this.or(d)

  /**
   * Run this `StreamDecoder`, then `d`, then concatenate the two streams,
   * even if `this` halts with an error. The error will be reraised when
   * `d` completes. See [[scalaz.stream.Process.onComplete]].
   */
  final def onComplete[A2>:A](d: => StreamDecoder[A2]): StreamDecoder[A2] =
    edit { _ onComplete d.process }

  /**
   * Transform the output of this `StreamDecoder` using the given
   * single-input stream transducer.
   */
  final def pipe[B](p: Process1[A,B]): StreamDecoder[B] =
    edit { _ pipe p }

  /**
   * Alternate between decoding `A` values using this `StreamDecoder`,
   * and decoding `B` values which are ignored.
   */
  final def sepBy[B:Decoder]: StreamDecoder[A] =
    tee(scodec.stream.decode.many[B])((Process.awaitL[A] fby Process.awaitR[B].drain).repeat)

  /** Decode at most `n` values using this `StreamDecoder`. */
  final def take(n: Int): StreamDecoder[A] =
    edit { _.take(n) }

  /**
   * Combine the output of this `StreamDecoder` with another streaming
   * decoder, using the given binary stream transducer. Note that both
   * `d` and `this` will operate on the same input `BitVector`, so this
   * combinator is more useful for expressing alternation between two
   * decoders.
   */
  final def tee[B,C](d: StreamDecoder[B])(t: Tee[A,B,C]): StreamDecoder[C] =
    edit { _.tee(d.process)(t) }

  // add alias for `or`
}

object StreamDecoder {

  /** `MonadPlus` instance for `StreamDecoder`. The `plus` operation is `++`. */
  implicit val instance = new MonadPlus[StreamDecoder] {
    def point[A](a: => A) = emit(a)
    def bind[A,B](a: StreamDecoder[A])(f: A => StreamDecoder[B]) =
      a flatMap f
    def empty[A] = halt
    def plus[A](d1: StreamDecoder[A], d2: => StreamDecoder[A]) = d1 ++ d2
  }
}
