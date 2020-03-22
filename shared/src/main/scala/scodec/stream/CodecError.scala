package scodec
package stream

import scala.util.control.NoStackTrace

/** Lifts an `scodec.Err` in to an exception. */
final case class CodecError(err: Err) extends Exception(err.messageWithContext) with NoStackTrace
