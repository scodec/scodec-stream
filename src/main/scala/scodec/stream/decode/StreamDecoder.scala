package scodec.stream.decode

import scala.language.higherKinds
import java.io.InputStream
import java.nio.channels.{FileChannel, ReadableByteChannel}
import fs2._
import fs2.util.{ ~>, Task }
import scala.util.control.NonFatal
import scodec.{ Attempt, Decoder, DecodeResult, Err }
import scodec.bits.BitVector
import shapeless.Lazy

/**
 * A streaming decoding process, represented as a stream of state
 * actions on [[scodec.bits.BitVector]]. Most clients will typically
 * use one of the decoding convenience methods on this class, rather
 * than using `decoder` directly.
 */
trait StreamDecoder[+A] { self =>

  import scodec.stream.{decode => D}

  /**
   * The `Stream` backing this `StreamDecoder`. All functions on `StreamDecoder`
   * are defined in terms of this `Stream`.
   */
  def decoder: Stream[Cursor,A]

  /**
   * Decode the given `BitVector`, returning a strict `Vector` of
   * the results, and throwing an exception in the event of a
   * decoding error.
   */
  def decodeAllValid(bits: => BitVector): Vector[A] =
    decode(bits).runLog.run.unsafeRun

  /**
   * Decoding a stream of `A` values from the given `BitVector`.
   * This function does not retain a reference to `bits`, allowing
   * it to be be garbage collected as the returned stream is traversed.
   */
  final def decode(bits: => BitVector): Stream[Task,A] = Stream.suspend {
    @volatile var cur = bits // am pretty sure this doesn't need to be volatile, but just being safe
    decoder.translate(new (Cursor ~> Task) {
      def apply[X](c: Cursor[X]): Task[X] = Task.suspend {
        c.run(cur).fold(
          msg => Task.fail(DecodingError(msg)),
          res => Task.now { cur = res.remainder; res.value }
        )
      }
    })
  }

  /**
   * Resource-safe version of `decode`. Acquires a resource,
   * decodes a stream of values, and releases the resource when
   * the returned `Stream[Task,A]` is finished being consumed.
   * The `acquire` and `release` actions may be asynchronous.
   */
  final def decodeAsyncResource[R](acquire: Task[R])(
      read: R => BitVector,
      release: R => Task[Unit]): Stream[Task,A] = {
    Stream.bracket(acquire)(read andThen { b => decode(b) }, release)
  }

  /**
   * Resource-safe version of `decode`. Acquires a resource,
   * decodes a stream of values, and releases the resource when
   * the returned `Stream[Task,A]` is finished being consumed.
   * If the `acquire` and `release` actions are asynchronous, use
   * [[decodeAsyncResource]].
   */
  final def decodeResource[R](acquire: => R)(
      read: R => BitVector,
      release: R => Unit): Stream[Task,A] =
    decodeAsyncResource(Task.delay(acquire))(read, r => Task.delay(release(r)))

  /**
   * Resource-safe version of `decode` for an `InputStream` resource.
   * This is just a convenience function which calls [[decodeResource]], using
   * `scodec.bits.BitVector.fromInputStream` as the `read` function, and which
   * closes `in` after the returned `Stream[Task,A]` is consumed.
   */
  final def decodeInputStream(
      in: => InputStream,
      chunkSizeInBytes: Int = 1024 * 1000 * 16): Stream[Task,A] =
    decodeResource(in)(BitVector.fromInputStream(_, chunkSizeInBytes), _.close)

  /**
   * Resource-safe version of `decode` for a `ReadableByteChannel` resource.
   * This is just a convenience function which calls [[decodeResource]], using
   * `scodec.bits.BitVector.fromChannel` as the `read` function, and which
   * closes `in` after the returned `Stream[Task,A]` is consumed.
   */
  final def decodeChannel(
      in: => ReadableByteChannel,
      chunkSizeInBytes: Int = 1024 * 1000 * 16,
      direct: Boolean = false): Stream[Task,A] =
    decodeResource(in)(BitVector.fromChannel(_, chunkSizeInBytes, direct), _.close)

  /**
   * Resource-safe version of `decode` for a `ReadableByteChannel` resource.
   * This is just a convenience function which calls [[decodeResource]], using
   * `scodec.bits.BitVector.fromChannel` as the `read` function, and which
   * closes `in` after the returned `Stream[Task,A]` is consumed.
   */
  final def decodeMmap(
      in: => FileChannel,
      chunkSizeInBytes: Int = 1024 * 1000 * 16): Stream[Task,A] =
    decodeResource(in)(BitVector.fromMmap(_, chunkSizeInBytes), _.close)

  /** Modify the `Stream[Cursor,A]` backing this `StreamDecoder`. */
  final def edit[B](f: Stream[Cursor, A] => Stream[Cursor, B]): StreamDecoder[B] =
    StreamDecoder.instance { f(decoder) }

  /**
   * Run this `StreamDecoder`, then `d`, then concatenate the two streams.
   */
  final def ++[A2>:A](d: => StreamDecoder[A2]): StreamDecoder[A2] =
    edit { _ ++ d.decoder }

  /**
   * Monadic bind for this `StreamDecoder`. Runs a stream decoder for each `A`
   * produced by this `StreamDecoder`, then concatenates all the resulting
   * streams of results. This is the same 'idea' as `List.flatMap`.
   */
  final def flatMap[B](f: A => StreamDecoder[B]): StreamDecoder[B] =
    flatMapS(f andThen (_.decoder))

  /**
   * Like `flatMap`, but takes a function that produces a `Stream[Cursor,B]`.
   */
  final def flatMapS[B](f: A => Stream[Cursor, B]): StreamDecoder[B] =
    edit { _ flatMap f }

  /** Alias for `decode.isolate(numberOfBits)(this)`. */
  def isolate(numberOfBits: Long): StreamDecoder[A] = D.isolate(numberOfBits)(this)

  /** Alias for `decode.isolateBytes(numberOfBytes)(this)`. */
  def isolateBytes(numberOfBytes: Long): StreamDecoder[A] = D.isolateBytes(numberOfBytes)(this)

  /** Run this `StreamDecoder` zero or more times until the input is exhausted. */
  def many: StreamDecoder[A] = {
    lazy val go: StreamDecoder[A] = D.ask.flatMap { bits =>
      if (bits.isEmpty) D.empty
      else this ++ go
    }
    go
  }

  /**
   * Run this `StreamDecoder` one or more times until the input is exhausted.
   */
  def many1: StreamDecoder[A] =
    this.many.nonEmpty(Err("many1 produced no outputs"))

  /**
   * Transform the output of this `StreamDecoder` using the function `f`.
   */
  final def map[B](f: A => B): StreamDecoder[B] =
    edit { _ map f }

  /**
   * Transform the output of this `StreamDecoder` using the partial function `pf`.
   */
  final def collect[B](pf: PartialFunction[A, B]): StreamDecoder[B] =
    edit { _ collect pf }

  /**
   * Transform the output of this `StreamDecoder`, converting left values
   * to decoding failures.
   */
  final def mapEither[B](f: A => Either[Err, B]): StreamDecoder[B] =
    this.flatMap { a => f(a).fold(D.fail, D.emit) }

  /**
   * Raises a decoding error if the given decoder emits no results,
   * otherwise runs `s` as normal.
   */
  def nonEmpty(errIfEmpty: Err): StreamDecoder[A] =
    through { s =>
      s.open.flatMap { h =>
        Pull.awaitOption(h).flatMap {
          case None => Pull.fail(DecodingError(errIfEmpty))
          case Some(a #: h1) => Pull.output(a) >> Pull.echo(h1)
        }
      }.run
    }

  /**
   * Alias for `scodec.stream.decode.or(this,d)`.
   * Runs `this`, then runs `d` if `this` emits no elements.
   * Example: `tryOnce(codecs.int32).or(once(codecs.uint32))`.
   * This function does no backtracking of its own; any desired
   * backtracking should be handled by `this`.
   */
  final def or[A2>:A](d: StreamDecoder[A2]): StreamDecoder[A2] =
    D.or(this, d)

  /** Operator alias for `this.or(d)`. */
  final def |[A2>:A](d: StreamDecoder[A2]): StreamDecoder[A2] =
    this.or(d)

  /**
   * Run this `StreamDecoder`, then `d`, then concatenate the two streams,
   * even if `this` halts with an error. The error will be reraised when
   * `d` completes. See `fs2.Stream.onComplete`.
   */
  final def onComplete[A2>:A](d: => StreamDecoder[A2]): StreamDecoder[A2] =
    edit { s => Stream.onComplete(s, d.decoder) }

  /**
   * Transform the output of this `StreamDecoder` using the given
   * single-input stream transducer.
   */
  final def through[B](p: Pipe[Pure,A,B]): StreamDecoder[B] =
    edit { _ through p }

  /**
   * Alternate between decoding `A` values using this `StreamDecoder`,
   * and decoding `B` values which are ignored.
   */
  def sepBy[B](implicit B: Lazy[Decoder[B]]): StreamDecoder[A] =
    through2(D.many[B]) { (value, delimiter) =>
      value.pull2(delimiter) { (valueHandle, delimiterHandle) =>
        def decodeValue(vh: Stream.Handle[Pure, A], dh: Stream.Handle[Pure, B]): Pull[Pure, A, Nothing] = {
          vh.receive1 { case v #: vh1 => Pull.output1(v) >> decodeDelimiter(vh1, dh) }
        }
        def decodeDelimiter(vh: Stream.Handle[Pure, A], dh: Stream.Handle[Pure, B]): Pull[Pure, A, Nothing] = {
          dh.receive1 { case d #: dh1 => decodeValue(vh, dh1) }
        }
        decodeValue(valueHandle, delimiterHandle)
      }
    }

  /** Decode at most `n` values using this `StreamDecoder`. */
  def take(n: Long): StreamDecoder[A] =
    edit { _ take n }

  /** Decode values as long as the predicate tests true. */
  def takeWhile(f: A => Boolean): StreamDecoder[A] =
    edit { _ takeWhile f }

  /** Ignore decoded values as long as the predicate tests true. */
  def dropWhile(f: A => Boolean): StreamDecoder[A] =
    edit { _ dropWhile f }

  /** Skip any decoded values for which the predicate tests false. */
  def filter(f: A => Boolean): StreamDecoder[A] =
    edit { _ filter f }

  /** Skip any decoded values for which the predicate tests false. */
  def withFilter(f: A => Boolean): StreamDecoder[A] = filter(f)

  /**
   * Equivalent to `dropWhile(f).take(1)` - returns a stream of (at most)
   * one element, consisting of the first output for which `f` tests false.
   */
  def firstAfter(f: A => Boolean): StreamDecoder[A] =
    dropWhile(f).take(1)

  /** Ignore the first `n` decoded values. */
  def drop(n: Int): StreamDecoder[A] =
    edit { _ drop n }

  /** Alias for `[[scodec.stream.decode.peek]](this)`. */
  def peek: StreamDecoder[A] = D.peek(this)

  /**
   * Combine the output of this `StreamDecoder` with another streaming
   * decoder, using the given binary stream transducer. Note that both
   * `d` and `this` will operate on the same input `BitVector`, so this
   * combinator is more useful for expressing alternation between two
   * decoders.
   */
  final def through2[B,C](d: StreamDecoder[B])(t: Pipe2[Pure,A,B,C]): StreamDecoder[C] =
    edit { _.through2(d.decoder)(t) }

  /** Create a strict (i.e., non-stream) decoder. */
  final def strict: Decoder[Vector[A]] = new Decoder[Vector[A]] {
    def decode(bits: BitVector) = {
      try {
        val result = (self ++ D.ask).decode(bits).runLog.run.unsafeRun
        val as = result.init.asInstanceOf[Vector[A]]
        val remainder = result.last.asInstanceOf[BitVector]
        Attempt.successful(DecodeResult(as, remainder))
      } catch {
        case e: DecodingError => Attempt.failure(e.err)
        case NonFatal(e) => Attempt.failure(Err(e.getMessage))
      }
    }
  }
}

object StreamDecoder {

  /** Create a `StreamDecoder[A]` from a `Stream[Cursor,A]`. */
  def instance[A](d: Stream[Cursor,A]): StreamDecoder[A] = new StreamDecoder[A] {
    def decoder = d
  }

  /** Conjure up a `StreamDecoder[A]` from implicit scope. */
  def apply[A](implicit A: StreamDecoder[A]): StreamDecoder[A] = A
}
