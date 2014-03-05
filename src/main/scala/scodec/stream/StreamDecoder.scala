package scodec.stream

import scalaz.stream.Process
import scalaz.concurrent.Task

trait StreamDecoder[+A] { self =>

  def decode(b: Bitstream): Process[Task,A]
}
