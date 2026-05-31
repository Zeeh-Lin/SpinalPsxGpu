import spinal.core._
import spinal.core.sim._
import PsxGpu._

object Gp0DecoderSim {
  def main(args: Array[String]): Unit = {

    // Generate Verilog
    Config.spinal.generateVerilog(new Gp0Decoder)

    // Simulation
    Config.sim.doSim(new Gp0Decoder) { dut =>
      var passed = 0
      var failed = 0
      def check(label: String)(block: => Boolean): Unit = {
        if (block) { passed += 1 }
        else { println(s"FAIL: $label"); failed += 1 }
      }

      def drive(inst: Long): Unit = {
        dut.io.inst #= inst
        sleep(1)
      }

      // bits goes into inst[28:0], opcode into inst[31:29]
      def gp0(opcode: Int, bits: Int): Long =
        ((opcode.toLong & 0x7) << 29) | (bits.toLong & 0x1FFFFFFF)

      // ── Env command helper: envCmdByte in inst[31:24] ──
      // opcode=7 covers bits 31:29; remaining 5 bits of command byte
      // go in bits 28:24 of the 'bits' parameter.
      def envCmd(cmdByte: Int, payload: Int): Long = {
        val opcode = 7
        val hi  = (cmdByte & 0x1F) << 24  // bits 28:24 of inst
        gp0(opcode, hi | payload)
      }

      // ═══════════════════════════════════════════════════════════════
      // Opcode detection
      // ═══════════════════════════════════════════════════════════════

      drive(gp0(0, 0))
      check("opcode=misc")           { dut.io.opcode.toEnum == Opcode.misc }

      drive(gp0(1, 0))
      check("opcode=polygon")        { dut.io.opcode.toEnum == Opcode.polygon }

      drive(gp0(2, 0))
      check("opcode=line")           { dut.io.opcode.toEnum == Opcode.line }

      drive(gp0(3, 0))
      check("opcode=rectangle")      { dut.io.opcode.toEnum == Opcode.rectangle }

      drive(gp0(4, 0))
      check("opcode=vramToVram")     { dut.io.opcode.toEnum == Opcode.vramToVram }

      drive(gp0(5, 0))
      check("opcode=cpuToVram")      { dut.io.opcode.toEnum == Opcode.cpuToVram }

      drive(gp0(6, 0))
      check("opcode=vramToCpu")      { dut.io.opcode.toEnum == Opcode.vramToCpu }

      drive(envCmd(0xE1, 0))
      check("opcode=env")            { dut.io.opcode.toEnum == Opcode.env }

      // ═══════════════════════════════════════════════════════════════
      // Polygon commands (opcode=1)
      // Flag bits at inst[28:24]: gouraud | quad | textured | semiTrans | rawTex
      // ═══════════════════════════════════════════════════════════════

      // Flat, triangle, untextured, opaque
      drive(gp0(1, (0x00 << 24) | 0xFF0000))
      check("poly: gouraud=0")       { !dut.io.polygonCfg.gouraud.toBoolean }
      check("poly: quad=0")          { !dut.io.polygonCfg.quad.toBoolean }
      check("poly: textured=0")      { !dut.io.polygonCfg.textured.toBoolean }
      check("poly: semiTransp=0")    { !dut.io.polygonCfg.base.semiTransparent.toBoolean }
      check("poly: rawTexture=0")    { !dut.io.polygonCfg.rawTexture.toBoolean }
      check("poly: color=0xFF0000")  { dut.io.polygonCfg.base.color.toLong == 0xFF0000 }
      // perVertex=1, verts=3 → 3 params
      check("poly: paramWords=3")    { dut.io.paramWords.toInt == 3 }

      // All 5 flags set: 0x1F = 1_1111
      drive(gp0(1, (0x1F << 24) | 0xABCDEF))
      check("poly: all flags")       {
        dut.io.polygonCfg.gouraud.toBoolean &&
        dut.io.polygonCfg.quad.toBoolean &&
        dut.io.polygonCfg.textured.toBoolean &&
        dut.io.polygonCfg.base.semiTransparent.toBoolean &&
        dut.io.polygonCfg.rawTexture.toBoolean
      }
      check("poly: color=0xABCDEF")  { dut.io.polygonCfg.base.color.toLong == 0xABCDEF }
      // perVertex=3, verts=4, subtract 1 (gouraud) → 4*3-1=11
      check("poly: paramWords=11")   { dut.io.paramWords.toInt == 11 }

      // Gouraud, triangle, untextured → perVertex=2, verts=3, subtract 1 → 5
      drive(gp0(1, (0x10 << 24) | 0x123456))
      check("poly: ga tri paramWords=5") { dut.io.paramWords.toInt == 5 }

      // Flat, quad, textured → perVertex=2, verts=4, no subtract → 8
      drive(gp0(1, (0x0C << 24) | 0xAAAAAA))
      check("poly: flat quad tex paramWords=8") { dut.io.paramWords.toInt == 8 }

      // ═══════════════════════════════════════════════════════════════
      // Line commands (opcode=2)
      // Flag bits at inst[28:24]: gouraud | polyline | 0 | semiTrans | 0
      // ═══════════════════════════════════════════════════════════════

      // Flat, single, opaque
      drive(gp0(2, (0x00 << 24) | 0xFFEEDD))
      check("line: gouraud=0")       { !dut.io.lineCfg.gouraud.toBoolean }
      check("line: polyline=0")      { !dut.io.lineCfg.polyline.toBoolean }
      check("line: semiTransp=0")    { !dut.io.lineCfg.base.semiTransparent.toBoolean }
      check("line: color=0xFFEEDD")  { dut.io.lineCfg.base.color.toLong == 0xFFEEDD }
      check("line: paramWords=2")    { dut.io.paramWords.toInt == 2 }

      // Gouraud, single, semiTransparent → perVertex=2, verts=2, -1 → 3
      drive(gp0(2, (0x12 << 24) | 0x112233))
      // 0x12 = 1_0010: bit28=1(gouraud), bit27=0(single), bit25=1(semi)
      check("line: ga single paramWords=3") { dut.io.paramWords.toInt == 3 }

      // Gouraud, polyline → paramWords=0 (variable)
      drive(gp0(2, (0x18 << 24) | 0x445566))
      check("line: polyline=1")      { dut.io.lineCfg.polyline.toBoolean }
      check("line: polyline paramWords=0") { dut.io.paramWords.toInt == 0 }

      // Flat, polyline
      drive(gp0(2, (0x08 << 24) | 0x778899))
      check("line: flat poly paramWords=0") { dut.io.paramWords.toInt == 0 }

      // ═══════════════════════════════════════════════════════════════
      // Rectangle commands (opcode=3)
      // Flag bits at inst[28:24]: size[1] | size[0] | textured | semiTrans | rawTex
      // ═══════════════════════════════════════════════════════════════

      // Variable size (00), textured, opaque
      drive(gp0(3, (0x04 << 24) | 0xBBCCDD))
      check("rect: size=variable")   { dut.io.rectangleCfg.size.toEnum == RectangleSize.variable }
      check("rect: textured=1")      { dut.io.rectangleCfg.textured.toBoolean }
      check("rect: semiTransp=0")    { !dut.io.rectangleCfg.base.semiTransparent.toBoolean }
      check("rect: rawTexture=0")    { !dut.io.rectangleCfg.rawTexture.toBoolean }
      check("rect: color=0xBBCCDD")  { dut.io.rectangleCfg.base.color.toLong == 0xBBCCDD }
      // 1(vtx) + 1(tex) + 1(var size) = 3
      check("rect: var tex paramWords=3") { dut.io.paramWords.toInt == 3 }

      // Sprite 8x8 (size=10), untextured → 1 param
      // bits: 28=1 27=0 → size=2
      drive(gp0(3, (0x10 << 24) | 0x001122))
      check("rect: size=sprite8")    { dut.io.rectangleCfg.size.toEnum == RectangleSize.sprite8 }
      check("rect: textured=0")      { !dut.io.rectangleCfg.textured.toBoolean }
      check("rect: sprite8 paramWords=1") { dut.io.paramWords.toInt == 1 }

      // Sprite 1x1 (size=01), textured, semiTransparent → 2 params
      // bits: 28=0 27=1 → size=1
      drive(gp0(3, (0x0E << 24) | 0x334455))
      // 0x0E = 01110: size=01, textured=1, semiTrans=1, rawTex=0
      check("rect: size=single")     { dut.io.rectangleCfg.size.toEnum == RectangleSize.single }
      check("rect: textured=1")      { dut.io.rectangleCfg.textured.toBoolean }
      check("rect: semiTransp=1")    { dut.io.rectangleCfg.base.semiTransparent.toBoolean }
      check("rect: single tex paramWords=2") { dut.io.paramWords.toInt == 2 }

      // Sprite 16x16 (size=11), rawTexture → 1 param
      // bits: 28=1 27=1 → size=3
      drive(gp0(3, (0x19 << 24) | 0x556677))
      // 0x19 = 11001: size=11, textured=0, semiTrans=0, rawTex=1
      check("rect: size=sprite16")   { dut.io.rectangleCfg.size.toEnum == RectangleSize.sprite16 }
      check("rect: rawTexture=1")    { dut.io.rectangleCfg.rawTexture.toBoolean }
      check("rect: sprite16 paramWords=1") { dut.io.paramWords.toInt == 1 }

      // ═══════════════════════════════════════════════════════════════
      // Environment commands (opcode=7)
      // Command byte at inst[31:24]. envCmd(cmdByte, payload) helper
      // ═══════════════════════════════════════════════════════════════

      // GP0(E1h) Draw Mode
      drive(envCmd(0xE1,
        (5 << 0)  |        // texturePageX[3:0]
        (1 << 4)  |        // texturePageY[0]
        (1 << 5)  |        // semiTransparency = 01 (B+F)
        (1 << 7)  |        // textureColors = 01 (8bit)
        (1 << 9)  |        // dither=1
        (1 << 10) |        // drawToDisplay=1
        (1 << 11) |        // texturePageY[1] (N*512)
        (1 << 12) |        // flipX=1
        (1 << 13)          // flipY=1
      ))
      check("E1: texPageX=5")        { dut.io.drawMode.texturePageX.toInt == 5 }
      check("E1: texPageY=3")        { dut.io.drawMode.texturePageY.toInt == 3 }  // bit11=1 bit4=1
      check("E1: semiTransp=B+F")    { dut.io.drawMode.semiTransparency.toEnum == SemiTransparency.backPlusFront }
      check("E1: texColors=8bit")    { dut.io.drawMode.textureColors.toEnum == TextureColorMode.mode8bit }
      check("E1: dither=1")          { dut.io.drawMode.dither.toBoolean }
      check("E1: drawToDisplay=1")   { dut.io.drawMode.drawToDisplay.toBoolean }
      check("E1: flipX=1")           { dut.io.drawMode.flipX.toBoolean }
      check("E1: flipY=1")           { dut.io.drawMode.flipY.toBoolean }

      // GP0(E2h) Texture Window
      drive(envCmd(0xE2,
        (3  << 0)  |
        (7  << 5)  |
        (5  << 10) |
        (15 << 15)
      ))
      check("E2: maskX=3")           { dut.io.textureWindow.maskX.toInt == 3 }
      check("E2: maskY=7")           { dut.io.textureWindow.maskY.toInt == 7 }
      check("E2: offsetX=5")         { dut.io.textureWindow.offsetX.toInt == 5 }
      check("E2: offsetY=15")        { dut.io.textureWindow.offsetY.toInt == 15 }

      // GP0(E3h) Drawing Area TL
      drive(envCmd(0xE3, 100 | (200 << 10)))
      check("E3: x=100")             { dut.io.drawingAreaTL.x.toInt == 100 }
      check("E3: y=200")             { dut.io.drawingAreaTL.y.toInt == 200 }

      // GP0(E4h) Drawing Area BR
      drive(envCmd(0xE4, 640 | (480 << 10)))
      check("E4: x=640")             { dut.io.drawingAreaBR.x.toInt == 640 }
      check("E4: y=480")             { dut.io.drawingAreaBR.y.toInt == 480 }

      // GP0(E5h) Drawing Offset (signed 11-bit)
      drive(envCmd(0xE5, 512 | (256 << 11)))
      check("E5: x=+512")            { dut.io.drawingOffset.x.toInt == 512 }
      check("E5: y=+256")            { dut.io.drawingOffset.y.toInt == 256 }

      // E5h max positive values
      drive(envCmd(0xE5, 1023 | (1022 << 11)))
      check("E5: x=+1023")           { dut.io.drawingOffset.x.toInt == 1023 }
      check("E5: y=+1022")           { dut.io.drawingOffset.y.toInt == 1022 }

      // GP0(E6h) Mask Setting
      drive(envCmd(0xE6, (1 << 0) | (1 << 1)))
      check("E6: setMask=1")         { dut.io.maskSetting.setMask.toBoolean }
      check("E6: checkMask=1")       { dut.io.maskSetting.checkMask.toBoolean }

      drive(envCmd(0xE6, 0))
      check("E6: setMask=0")         { !dut.io.maskSetting.setMask.toBoolean }
      check("E6: checkMask=0")       { !dut.io.maskSetting.checkMask.toBoolean }

      // ═══════════════════════════════════════════════════════════════
      // Misc commands (opcode=0)
      // Command type in inst[28:24]
      // ═══════════════════════════════════════════════════════════════

      // Clear Cache: inst[28:24] = 00001
      drive(gp0(0, 0x01 << 24))
      check("misc: clearCache=1")    { dut.io.clearCache.toBoolean }

      // Not Clear Cache
      drive(gp0(0, 0x00))
      check("misc: clearCache=0")    { !dut.io.clearCache.toBoolean }

      // Quick Fill: inst[28:24] = 00010 → 2 params
      drive(gp0(0, 0x02 << 24))
      check("misc: quickFill paramWords=2") { dut.io.paramWords.toInt == 2 }

      // IRQ: inst[28:24] = 11111 → 0 params
      drive(gp0(0, 0x1F << 24))
      check("misc: IRQ paramWords=0") { dut.io.paramWords.toInt == 0 }

      // ═══════════════════════════════════════════════════════════════
      // FIFO bypass (inst[28:24] drives misc, inst[31:24] drives env)
      // ═══════════════════════════════════════════════════════════════

      // Misc: GP0(00h) NOP → bypass
      drive(gp0(0, 0x00 << 24))
      check("bypass: NOP(00h)")      { dut.io.bypassFifo.toBoolean }

      // Misc: GP0(01h) Clear Cache → no bypass
      drive(gp0(0, 0x01 << 24))
      check("bypass: ClearCache(01h)") { !dut.io.bypassFifo.toBoolean }

      // Misc: GP0(02h) Quick Fill → no bypass
      drive(gp0(0, 0x02 << 24))
      check("bypass: QuickFill(02h)") { !dut.io.bypassFifo.toBoolean }

      // Misc: GP0(03h) unknown → no bypass
      drive(gp0(0, 0x03 << 24))
      check("bypass: unknown(03h)")  { !dut.io.bypassFifo.toBoolean }

      // Misc: GP0(04h) NOP mirror → bypass
      drive(gp0(0, 0x04 << 24))
      check("bypass: NOP(04h)")      { dut.io.bypassFifo.toBoolean }

      // Misc: GP0(1Eh) NOP mirror → bypass
      drive(gp0(0, 0x1E << 24))
      check("bypass: NOP(1Eh)")      { dut.io.bypassFifo.toBoolean }

      // Misc: GP0(1Fh) IRQ → no bypass
      drive(gp0(0, 0x1F << 24))
      check("bypass: IRQ(1Fh)")      { !dut.io.bypassFifo.toBoolean }

      // Env: GP0(E1h) Draw Mode → no bypass
      drive(envCmd(0xE1, 0))
      check("bypass: E1h(DrawMode)") { !dut.io.bypassFifo.toBoolean }

      // Env: GP0(E2h) Texture Window → no bypass
      drive(envCmd(0xE2, 0))
      check("bypass: E2h(TexWindow)") { !dut.io.bypassFifo.toBoolean }

      // Env: GP0(E3h) Drawing Area TL → bypass
      drive(envCmd(0xE3, 0))
      check("bypass: E3h(DrawAreaTL)") { dut.io.bypassFifo.toBoolean }

      // Env: GP0(E4h) Drawing Area BR → bypass
      drive(envCmd(0xE4, 0))
      check("bypass: E4h(DrawAreaBR)") { dut.io.bypassFifo.toBoolean }

      // Env: GP0(E5h) Drawing Offset → bypass
      drive(envCmd(0xE5, 0))
      check("bypass: E5h(DrawOffset)") { dut.io.bypassFifo.toBoolean }

      // Env: GP0(E6h) Mask Setting → no bypass
      drive(envCmd(0xE6, 0))
      check("bypass: E6h(Mask)")     { !dut.io.bypassFifo.toBoolean }

      // Env: GP0(E0h) NOP mirror → bypass
      drive(envCmd(0xE0, 0))
      check("bypass: E0h(NOP)")      { dut.io.bypassFifo.toBoolean }

      // Env: GP0(E7h) NOP mirror → bypass
      drive(envCmd(0xE7, 0))
      check("bypass: E7h(NOP)")      { dut.io.bypassFifo.toBoolean }

      // ═══════════════════════════════════════════════════════════════
      // Memory transfer paramWords
      // ═══════════════════════════════════════════════════════════════

      drive(gp0(4, 0))
      check("xfer: vramToVram=3")    { dut.io.paramWords.toInt == 3 }

      drive(gp0(5, 0))
      check("xfer: cpuToVram=2")     { dut.io.paramWords.toInt == 2 }

      drive(gp0(6, 0))
      check("xfer: vramToCpu=2")     { dut.io.paramWords.toInt == 2 }

      // ═══════════════════════════════════════════════════════════════
      // Results
      // ═══════════════════════════════════════════════════════════════

      val total = passed + failed
      println(s"\n$passed / $total passed")
      if (failed > 0) {
        println(s"$failed FAILURES")
        simFailure()
      } else {
        println("ALL PASSED")
      }
    }
  }
}
