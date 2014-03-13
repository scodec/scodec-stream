package scodec.stream.decode

import java.io.InputStream
import java.nio.channels.{FileChannel, ReadableByteChannel}
import scalaz.MonadPlus
import scalaz.stream.{Process,Process1,Tee}
import scalaz.concurrent.Task
import scodec.bits.BitVector

/**
 * A streaming decoding process, represented as a stream of state
 * actions on [[scodec.bits.BitVector]]. Most clients will typically
 * use one of the decoding convenience methods on this class, rather
 * than using `process` directly.
 */
case class StreamDecoder[+A](process: Process[Cursor,A]) {

  /**
   * Convenience function for decoding a stream of `A` values
   * from the given `BitVector`. This function does not retain
   * a reference to `bits`, allowing it to be be garbage collected
   * as the returned stream is traversed. See [[scodec.stream.decode.evaluate]]
   * for further notes.
   */
  final def decode(bits: => BitVector): Process[Task,A] =
    scodec.stream.decode.evaluate(bits)(process)

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
   * Transform the output of this `StreamDecoder` using the function `f`.
   */
  final def map[B](f: A => B): StreamDecoder[B] =
    edit { _ map f }

  /**
   * Monadic bind for this `StreamDecoder`. Runs a stream decoder for each `A`
   * produced by this `StreamDecoder`, then concatenates all the resulting
   * streams of results. This is the same 'idea' as `List.flatMap`.
   */
  final def flatMap[B](f: A => StreamDecoder[B]): StreamDecoder[B] =
    edit { _ flatMap (f andThen (_.process)) }

  /**
   * Run this `StreamDecoder`, then `d`, then concatenate the two streams.
   */
  final def ++[A2>:A](d: => StreamDecoder[A2]): StreamDecoder[A2] =
    edit { _ ++ d.process }

  /** Modify the `Process[Cursor,A]` backing this `StreamDecoder`. */
  final def edit[B](f: Process[Cursor,A] => Process[Cursor,B]): StreamDecoder[B] =
    StreamDecoder { f(process) }

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
   * Combine the output of this `StreamDecoder` with another streaming
   * decoder, using the given binary stream transducer.
   */
  final def tee[B,C](d: StreamDecoder[B])(t: Tee[A,B,C]): StreamDecoder[C] =
    edit { _.tee(d.process)(t) }
}

object StreamDecoder {

  /** The decoder that consumes no input and emits no values. */
  val halt: StreamDecoder[Nothing] = StreamDecoder(Process.halt)

  /** The decoder that consumes no input and halts with the given error. */
  def fail(err: Throwable): StreamDecoder[Nothing] = StreamDecoder(Process.fail(err))

  /** The decoder that consumes no input, emits the given `a`, then halts. */
  def emit[A](a: A): StreamDecoder[A] = StreamDecoder(Process.emit(a))

  /** The decoder that consumes no input, emits the given `A` values, then halts. */
  def emitAll[A](as: Seq[A]): StreamDecoder[A] = StreamDecoder(Process.emitAll(as))

  /** `MonadPlus` instance for `StreamDecoder`. The `plus` operation is `++`. */
  implicit val instance = new MonadPlus[StreamDecoder] {
    def point[A](a: => A) = emit(a)
    def bind[A,B](a: StreamDecoder[A])(f: A => StreamDecoder[B]) =
      a flatMap f
    def empty[A] = halt
    def plus[A](d1: StreamDecoder[A], d2: => StreamDecoder[A]) = d1 ++ d2
  }
}
