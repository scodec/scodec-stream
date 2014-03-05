package scodec.stream

import scalaz.stream.Process
import scalaz.concurrent.Task

trait StreamEncoder[-A] { self =>

  def encode(a: Process[Task,A]): Bitstream
}
