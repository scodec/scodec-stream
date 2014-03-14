package scodec.stream.encode

case class EncodingError(message: String) extends Exception(message)
