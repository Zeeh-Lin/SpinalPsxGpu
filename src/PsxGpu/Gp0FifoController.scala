package PsxGpu

import spinal.core._
import spinal.lib._

// ── GP0 FIFO Controller (write-side state machine) ──

object FifoState extends SpinalEnum(binarySequential) {
  val cmd, param = newElement()
}

class Gp0FifoController() extends Component {
  val io = new Bundle {
    val gp0Write  = slave  Stream(Bits(32 bits))
    val fifoOut   = master Stream(Bits(32 bits))
    val bypassOut = master Stream(Bits(32 bits))
    val isParam   = out Bool()
  }

  val state    = RegInit(FifoState.cmd)
  val paramCnt = Reg(UInt(4 bits)) init 0

  // ── Write handshake ──
  io.gp0Write.ready := Mux(
    state === FifoState.cmd,
    Gp0Flow.bypassFifo(io.gp0Write.payload) || io.fifoOut.ready,
    io.fifoOut.ready
  )

  // ── Data routing ──
  def inFire       = io.gp0Write.valid && io.gp0Write.ready
  def inBypass     = Gp0Flow.bypassFifo(io.gp0Write.payload)
  def inNumParams  = Gp0Flow.paramWords(io.gp0Write.payload)
  def inIsCmd      = state === FifoState.cmd
  def inIsParm     = state === FifoState.param
  def inNotBypass  = !inBypass
  def inHasParams  = inNumParams > U(0)
  def inLastParam  = paramCnt === U(0)

  io.isParam := inIsParm

  io.bypassOut.valid   := inFire && inIsCmd && inBypass
  io.bypassOut.payload := io.gp0Write.payload
  io.fifoOut.valid     := inFire && ((inIsCmd && inNotBypass) || inIsParm)
  io.fifoOut.payload   := io.gp0Write.payload

  // ── State register updates (each in its own scope) ──
  {
    when(inFire && inIsCmd && inNotBypass && inHasParams) {
      state    := FifoState.param
      paramCnt := inNumParams - 1
    }
  }
  {
    when(inFire && inIsParm && inLastParam) {
      state := FifoState.cmd
    }
  }
  {
    when(inFire && inIsParm && !inLastParam) {
      paramCnt := paramCnt - 1
    }
  }
}
