package scodec

import scodec.bits.BitVector
import scalaz.concurrent.Task
import scalaz.stream.Process

package object stream {

  type Bitstream = Process[Task,BitVector]
}
