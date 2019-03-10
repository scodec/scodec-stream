package scodec
package stream
package encode

case class EncodingError(err: Err) extends Exception(err.toString)
