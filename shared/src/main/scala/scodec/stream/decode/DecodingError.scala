package scodec
package stream
package decode

case class DecodingError(err: Err) extends Exception(err.messageWithContext)
