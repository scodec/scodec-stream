package scodec
package stream

import scala.util.control.NoStackTrace

final case class CodecError(err: Err) extends Exception(err.messageWithContext) with NoStackTrace
