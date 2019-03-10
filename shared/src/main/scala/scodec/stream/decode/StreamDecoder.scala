package scodec.stream.decode

import language.higherKinds

import java.io.InputStream
import java.nio.channels.{ FileChannel, ReadableByteChannel }
import cats.~>
import cats.effect.{ Effect, IO }
import fs2._
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
    decode[IO](bits).compile.toVector.unsafeRunSync

  /**
   * Decoding a stream of `A` values from the given `BitVector`.
   * This function does not retain a reference to `bits`, allowing
   * it to be be garbage collected as the returned stream is traversed.
   */
  final def decode[F[_]](bits: => BitVector)(implicit F: Effect[F]): Stream[F,A] = Stream.suspend {
    @volatile var cur = bits // am pretty sure this doesn't need to be volatile, but just being safe
    decoder.translate(new (Cursor ~> F) {
      def apply[X](c: Cursor[X]): F[X] = F.suspend {
        c.run(cur).fold(
          msg => F.raiseError(DecodingError(msg)),
          res => F.pure { cur = res.remainder; res.value }
        )
      }
    })
  }

  /**
   * Resource-safe version of `decode`. Acquires a resource,
   * decodes a stream of values, and releases the resource when
   * the returned `Stream[F,A]` is finished being consumed.
   * The `acquire` and `release` actions may be asynchronous.
   */
  final def decodeAsyncResource[F[_]: Effect,R](acquire: F[R])(
      read: R => BitVector,
      release: R => F[Unit]): Stream[F,A] = {
    Stream.bracket(acquire)(release).flatMap(read andThen { b => decode(b) })
  }

  /**
   * Resource-safe version of `decode`. Acquires a resource,
   * decodes a stream of values, and releases the resource when
   * the returned `Stream[F,A]` is finished being consumed.
   * If the `acquire` and `release` actions are asynchronous, use
   * [[decodeAsyncResource]].
   */
  final def decodeResource[F[_],R](acquire: => R)(
      read: R => BitVector,
      release: R => Unit)(implicit F: Effect[F]): Stream[F,A] =
    decodeAsyncResource(F.delay(acquire))(read, r => F.delay(release(r)))

  /**
   * Resource-safe version of `decode` for an `InputStream` resource.
   * This is just a convenience function which calls [[decodeResource]], using
   * `scodec.bits.BitVector.fromInputStream` as the `read` function, and which
   * closes `in` after the returned `Stream[F,A]` is consumed.
   */
  final def decodeInputStream[F[_]:Effect](
      in: => InputStream,
      chunkSizeInBytes: Int = 1024 * 1000 * 16): Stream[F,A] =
    decodeResource(in)(BitVector.fromInputStream(_, chunkSizeInBytes), _.close)

  /**
   * Resource-safe version of `decode` for a `ReadableByteChannel` resource.
   * This is just a convenience function which calls [[decodeResource]], using
   * `scodec.bits.BitVector.fromChannel` as the `read` function, and which
   * closes `in` after the returned `Stream[F,A]` is consumed.
   */
  final def decodeChannel[F[_]: Effect](
      in: => ReadableByteChannel,
      chunkSizeInBytes: Int = 1024 * 1000 * 16,
      direct: Boolean = false): Stream[F,A] =
    decodeResource(in)(BitVector.fromChannel(_, chunkSizeInBytes, direct), _.close)

  /**
   * Resource-safe version of `decode` for a `ReadableByteChannel` resource.
   * This is just a convenience function which calls [[decodeResource]], using
   * `scodec.bits.BitVector.fromChannel` as the `read` function, and which
   * closes `in` after the returned `Stream[F,A]` is consumed.
   */
  final def decodeMmap[F[_]: Effect](
      in: => FileChannel,
      chunkSizeInBytes: Int = 1024 * 1000 * 16): Stream[F,A] =
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
    this.flatMap { a => f(a).fold(D.raiseError, D.emit) }

  /**
   * Raises a decoding error if the given decoder emits no results,
   * otherwise runs `s` as normal.
   */
  def nonEmpty(errIfEmpty: Err): StreamDecoder[A] =
    through { s =>
      s.pull.uncons.flatMap {
        case None => Pull.raiseError[Cursor](DecodingError(errIfEmpty))
        case Some((hd,tl)) => Pull.output(hd) >> tl.pull.echo
      }.stream
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
   * Transform the output of this `StreamDecoder` using the given pipe.
   */
  final def through[B](p: Pipe[Cursor,A,B]): StreamDecoder[B] =
    edit { _ through p }

  /**
   * Alternate between decoding `A` values using this `StreamDecoder`,
   * and decoding `B` values which are ignored.
   */
  def sepBy[B](implicit B: Lazy[Decoder[B]]): StreamDecoder[A] = {
    through2(D.many[B]) { (value, delimiter) =>
      def decodeValue(vs: Stream[Cursor, A], ds: Stream[Cursor, B]): Pull[Cursor,A,Unit] =
        vs.pull.uncons1.flatMap {
          case Some((v,vs1)) => Pull.output1(v) >> decodeDelimiter(vs1,ds)
          case None => Pull.done
        }
      def decodeDelimiter(vs: Stream[Cursor, A], ds: Stream[Cursor, B]): Pull[Cursor,A,Unit] =
        ds.pull.uncons1.flatMap {
          case Some((d,ds1)) => decodeValue(vs,ds1)
          case None => Pull.done
        }
      decodeValue(value, delimiter).stream
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
  def drop(n: Long): StreamDecoder[A] =
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
  final def through2[B,C](d: StreamDecoder[B])(t: Pipe2[Cursor,A,B,C]): StreamDecoder[C] =
    edit { _.through2(d.decoder)(t) }

  /** Create a strict (i.e., non-stream) decoder. */
  final def strict: Decoder[Vector[A]] = new Decoder[Vector[A]] {
    def decode(bits: BitVector) = {
      try {
        val result = (self ++ D.ask).decode[IO](bits).compile.toVector.unsafeRunSync
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
