package scodec.stream.decode

case class DecodingError(message: String) extends Exception(message)
