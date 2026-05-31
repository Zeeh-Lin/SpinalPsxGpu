import spinal.core._
import spinal.core.sim._
import spinal.lib._
import PsxGpu._

// Testbench: Gp0FifoController → Gp0CmdFifo → Gp0Decoder
// Sub-component ports are tagged simPublic() — testbench drives them directly.
// No redundant signal breakout needed.

class FifoTestTop() extends Component {
  val ctrl    = new Gp0FifoController
  val fifo    = new Gp0CmdFifo
  val dec     = new Gp0Decoder

  // Master → slave connection (correct direction pair)
  ctrl.io.fifoOut >> fifo.io.push
  dec.io.inst := fifo.io.pop.payload

  // Bypass always accepted in tests
  ctrl.io.bypassOut.ready := True

  // Tag signals for sim access (no manual io breakout needed)
  ctrl.io.gp0Write.simPublic()
  ctrl.io.bypassOut.simPublic()
  ctrl.io.isParam.simPublic()
  fifo.io.empty.simPublic()
  fifo.io.pop.simPublic()
  dec.io.opcode.simPublic()
  dec.io.paramWords.simPublic()
}

object Gp0CmdFifoSim {
  def main(args: Array[String]): Unit = {

    Config.sim.doSim(new FifoTestTop) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      // Init: gp0 not writing, pop not reading
      dut.ctrl.io.gp0Write.valid #= false
      dut.fifo.io.pop.ready #= false

      var passed = 0
      var failed = 0
      def check(label: String)(block: => Boolean): Unit = {
        if (block) { passed += 1 } else { println(s"FAIL: $label"); failed += 1 }
      }

      def gp0(opcode: Int, bits: Int): Long =
        ((opcode.toLong & 7) << 29) | (bits.toLong & 0x1FFFFFFF)
      def envCmd(cmdByte: Int, payload: Int): Long =
        gp0(7, ((cmdByte & 0x1F) << 24) | payload)

      // ── push: drive gp0Write, returns bypass info (read before clearing valid) ──
      def push(value: Long): (Boolean, Long) = {
        dut.ctrl.io.gp0Write.valid #= true
        dut.ctrl.io.gp0Write.payload #= value
        dut.clockDomain.waitSamplingWhere(dut.ctrl.io.gp0Write.ready.toBoolean)
        val bv = dut.ctrl.io.bypassOut.valid.toBoolean
        val bd = dut.ctrl.io.bypassOut.payload.toLong
        dut.ctrl.io.gp0Write.valid #= false
        dut.clockDomain.waitSampling()
        (bv, bd)
      }

      // ── popOne: advance FIFO by one word ──
      def popOne(): Unit = {
        dut.fifo.io.pop.ready #= true
        dut.clockDomain.waitSampling()
        dut.fifo.io.pop.ready #= false
        dut.clockDomain.waitSampling()
      }

      // ── drain FIFO ──
      def drain(): Unit = {
        dut.fifo.io.pop.ready #= true
        while (dut.fifo.io.pop.valid.toBoolean) { dut.clockDomain.waitSampling() }
        dut.fifo.io.pop.ready #= false
        dut.clockDomain.waitSampling()
      }

      // ═══════════════════════════════════════════════
      println("Test 1: E3h bypass")
      val e3 = envCmd(0xE3, 100 | (200 << 10))
      val (bv1, bd1) = push(e3)
      check("E3h bypass valid") { bv1 }
      check("E3h bypass data")  { bd1 == e3 }
      check("FIFO empty")       { dut.fifo.io.empty.toBoolean }

      // ═══════════════════════════════════════════════
      println("Test 2: E1h -> FIFO -> decoder")
      push(envCmd(0xE1, (5 << 0) | (1 << 7)))
      dut.clockDomain.waitSamplingWhere(dut.fifo.io.pop.valid.toBoolean)
      check("E1h opcode=env")   { dut.dec.io.opcode.toEnum == Opcode.env }
      check("E1h paramWords=0") { dut.dec.io.paramWords.toInt == 0 }
      popOne()

      // ═══════════════════════════════════════════════
      println("Test 3: polygon -> FIFO -> decoder")
      drain()
      push(gp0(1, (0x00 << 24) | 0xFF0000))  // flat tri, 3 vtx
      push(gp0(0, 0x00100020))
      push(gp0(0, 0x00300040))
      push(gp0(0, 0x00500060))
      // Read command word at FIFO front (popReady still false)
      dut.clockDomain.waitSamplingWhere(dut.fifo.io.pop.valid.toBoolean)
      check("poly opcode")       { dut.dec.io.opcode.toEnum == Opcode.polygon }
      check("poly paramWords=3") { dut.dec.io.paramWords.toInt == 3 }
      popOne(); popOne(); popOne(); popOne()  // consume all 4 words
      check("poly FIFO empty") { dut.fifo.io.empty.toBoolean }

      // ═══════════════════════════════════════════════
      println("Test 4: bypass while FIFO not empty")
      push(envCmd(0xE1, 0))
      check("FIFO not empty")  { !dut.fifo.io.empty.toBoolean }
      val (bv4, _) = push(envCmd(0xE3, 640 | (480 << 10)))
      check("bypass while FIFO busy") { bv4 }
      drain()

      // ═══════════════════════════════════════════════
      println("Test 5: CMD->PARAM->CMD state machine")
      drain()
      push(gp0(0, 0x02 << 24))      // Quick Fill
      check("in PARAM")       { dut.ctrl.io.isParam.toBoolean }
      push(gp0(0, 0x00100020))
      check("still PARAM")    { dut.ctrl.io.isParam.toBoolean }
      push(gp0(0, 0x00200040))
      check("back to CMD")    { !dut.ctrl.io.isParam.toBoolean }

      // ═══════════════════════════════════════════════
      println("Test 6: vramToVram paramWords=3")
      drain()
      push(gp0(4, 0)); push(gp0(0, 0)); push(gp0(0, 0)); push(gp0(0, 0))
      dut.clockDomain.waitSamplingWhere(dut.fifo.io.pop.valid.toBoolean)
      check("vramToVram paramWords=3") { dut.dec.io.paramWords.toInt == 3 }
      popOne(); popOne(); popOne(); popOne()

      // ═══════════════════════════════════════════════
      val total = passed + failed
      println(s"\n$passed / $total passed")
      if (failed > 0) { println(s"$failed FAILURES"); simFailure() }
      else println("ALL PASSED")
    }
  }
}
