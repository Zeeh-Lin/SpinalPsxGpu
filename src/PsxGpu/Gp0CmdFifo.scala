package PsxGpu

import spinal.core._
import spinal.lib._

// ── GP0 Command FIFO (16 words x 32 bits) ──
class Gp0CmdFifo() extends Component {
  val io = new Bundle {
    val push      = slave  Stream(Bits(32 bits))
    val pop       = master Stream(Bits(32 bits))
    val occupancy = out UInt(5 bits)
    val full      = out Bool()
    val empty     = out Bool()
  }

  val fifo = StreamFifo(Bits(32 bits), 16)

  io.push      <> fifo.io.push
  fifo.io.pop  <> io.pop

  io.occupancy := fifo.io.occupancy
  io.full      := fifo.io.occupancy === 16
  io.empty     := fifo.io.occupancy === 0
}
