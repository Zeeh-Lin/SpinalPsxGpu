package PsxGpu

import spinal.core._

// Pure combinational GP0 command word classification.
// Shared by FifoController (pre-FIFO) and Gp0Decoder (post-FIFO).

object Gp0Flow {

  // ── FIFO bypass ──
  // Returns true if this command word does NOT occupy a FIFO slot.
  // Applies to: NOP(00h), NOP mirrors(04h..1Eh), E0h, E3h-E5h, E7h-EFh.
  def bypassFifo(inst: Bits): Bool = {
    val result = False
    val op = inst(31 downto 29)

    when(op === B"000") {
      val cmd = inst(28 downto 24).asUInt
      // bypass: 00h(NOP), 04h..1Eh(NOP mirrors)
      // not:   01h(ClearCache), 02h(QuickFill), 03h(unknown), 1Fh(IRQ)
      result := cmd === U(0, 5 bits) || (cmd >= U(4, 5 bits) && cmd <= U(30, 5 bits))
    }

    when(op === B"111") {
      val cmdByte = inst(31 downto 24).asUInt
      // bypass: E0h(NOP mirror), E3h-E5h(Drawing cfg), E7h-EFh(NOP mirrors)
      // not:    E1h(DrawMode), E2h(TexWindow), E6h(Mask)
      val isNop = cmdByte === U(0xE0, 8 bits) || cmdByte >= U(0xE7, 8 bits)
      val isCfg = cmdByte >= U(0xE3, 8 bits) && cmdByte <= U(0xE5, 8 bits)
      result := isNop || isCfg
    }

    result
  }

  // ── Parameter word count ──
  // Returns how many 32-bit words follow this command before execution.
  // 0 = command is self-contained.
  // Polyline returns 0 (downstream detects terminator).
  def paramWords(inst: Bits): UInt = {
    val count = UInt(4 bits)
    count := 0
    val op = inst(31 downto 29)

    switch(op) {
      is(B"001") {  // Polygon
        val perVertex = U(1, 2 bits) + inst(28).asUInt + inst(26).asUInt
        val verts     = Mux(inst(27), U(4, 3 bits), U(3, 3 bits))
        val total     = verts * perVertex
        val subFirst  = Mux(inst(28), U(1), U(0))
        count := (total - subFirst).resize(4 bits)
      }

      is(B"010") {  // Line
        val perVertex = U(1, 2 bits) + inst(28).asUInt
        when(inst(27)) {
          count := 0  // polyline: variable length
        }.otherwise {
          val total    = U(2, 2 bits) * perVertex
          val subFirst = Mux(inst(28), U(1), U(0))
          count := (total - subFirst).resize(4 bits)
        }
      }

      is(B"011") {  // Rectangle
        val base = U(1, 2 bits) + inst(26).asUInt
        when(inst(28 downto 27) === B"00") {
          count := (base + 1).resize(4 bits)  // variable size
        }.otherwise {
          count := base.resize(4 bits)
        }
      }

      is(B"100") { count := 3 }  // vramToVram
      is(B"101") { count := 2 }  // cpuToVram
      is(B"110") { count := 2 }  // vramToCpu

      is(B"000") {  // Misc
        when(inst(28 downto 24) === B"00010") {
          count := 2  // Quick Fill
        }
      }

      default {}
    }

    count
  }
}
