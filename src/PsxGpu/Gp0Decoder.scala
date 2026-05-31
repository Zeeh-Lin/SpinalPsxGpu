package PsxGpu

import spinal.core._
import spinal.lib._

object Opcode extends SpinalEnum(binarySequential) {
  val misc, polygon, line, rectangle,
      vramToVram, cpuToVram, vramToCpu, env = newElement()
}

object RectangleSize extends SpinalEnum(binarySequential) {
  val variable, single, sprite8, sprite16 = newElement()
}

object SemiTransparency extends SpinalEnum(binarySequential) {
  val halfBackHalfFront, backPlusFront, backMinusFront, backPlusQuarterFront = newElement()
}

object TextureColorMode extends SpinalEnum(binarySequential) {
  val mode4bit, mode8bit, mode15bit, reserved = newElement()
}

// ── Shared rendering primitive fields ──
// Bits 23-0 (color) and bit 25 (semiTransparent) are at identical positions
// for polygon, line, and rectangle commands.

case class PrimBase() extends Bundle {
  val color           = Bits(24 bits)
  val semiTransparent = Bool()
}

case class PolygonCfg() extends Bundle {
  val base       = PrimBase()
  val gouraud    = Bool()  // bit 28
  val quad       = Bool()  // bit 27
  val textured   = Bool()  // bit 26
  val rawTexture = Bool()  // bit 24
}

case class LineCfg() extends Bundle {
  val base     = PrimBase()
  val gouraud  = Bool()  // bit 28
  val polyline = Bool()  // bit 27
}

case class RectangleCfg() extends Bundle {
  val base       = PrimBase()
  val size       = RectangleSize()  // bits 28-27
  val textured   = Bool()           // bit 26
  val rawTexture = Bool()           // bit 24
}

// ── Environment command bundles ──

// GP0(E1h) - Draw Mode setting (aka "Texpage")
case class DrawMode() extends Bundle {
  val texturePageX     = UInt(4 bits)   // N*64 halfwords
  val texturePageY     = UInt(2 bits)   // {bit11(N*512), bit4(N*256)}
  val semiTransparency = SemiTransparency()
  val textureColors    = TextureColorMode()
  val dither           = Bool()
  val drawToDisplay    = Bool()
  val flipX            = Bool()
  val flipY            = Bool()
}

// GP0(E2h) - Texture Window setting
case class TextureWindow() extends Bundle {
  val maskX   = UInt(5 bits)   // in 8-pixel steps
  val maskY   = UInt(5 bits)
  val offsetX = UInt(5 bits)
  val offsetY = UInt(5 bits)
}

// GP0(E3h/E4h) - Drawing Area corners
case class DrawingArea() extends Bundle {
  val x = UInt(10 bits)   // 0..1023
  val y = UInt(10 bits)   // 0..1023 (v2 GPU), upper bit ignored on v0
}

// GP0(E5h) - Drawing Offset
case class DrawingOffset() extends Bundle {
  val x = SInt(11 bits)   // -1024..+1023
  val y = SInt(11 bits)
}

// GP0(E6h) - Mask Bit Setting
case class MaskSetting() extends Bundle {
  val setMask   = Bool()   // bit0: 0=TextureBit15, 1=ForceBit15=1
  val checkMask = Bool()   // bit1: 0=Draw Always, 1=Draw if Bit15=0
}

// ── GP0 Command Decoder ──
// Pure combinational. Decodes a 32-bit GP0 command word.
// Flow-control outputs (paramWords, bypassFifo) delegated to shared Gp0Flow.

class Gp0Decoder() extends Component {
  val io = new Bundle {
    val inst       = in  Bits(32 bits)
    val opcode     = out(Opcode())
    val clearCache = out Bool()

    // Flow control
    val paramWords = out UInt (4 bits)  // parameter words to consume after this command
    val bypassFifo = out Bool()         // command does not occupy FIFO space

    // Rendering primitive commands
    val polygonCfg   = out(PolygonCfg())
    val lineCfg      = out(LineCfg())
    val rectangleCfg = out(RectangleCfg())

    // Environment commands
    val drawMode      = out(DrawMode())
    val textureWindow = out(TextureWindow())
    val drawingAreaTL = out(DrawingArea())
    val drawingAreaBR = out(DrawingArea())
    val drawingOffset = out(DrawingOffset())
    val maskSetting   = out(MaskSetting())
  }

  val op = Opcode()
  op.assignFromBits(io.inst(31 downto 29))
  io.opcode := op

  // ── Common primitive fields (shared bit positions) ──
  val primColor           = io.inst(23 downto 0)
  val primSemiTransparent = io.inst(25)

  io.polygonCfg.base.color             := primColor
  io.polygonCfg.base.semiTransparent   := primSemiTransparent
  io.lineCfg.base.color                := primColor
  io.lineCfg.base.semiTransparent      := primSemiTransparent
  io.rectangleCfg.base.color           := primColor
  io.rectangleCfg.base.semiTransparent := primSemiTransparent

  // ── Polygon-specific (opcode=1) ──
  io.polygonCfg.gouraud    := io.inst(28)
  io.polygonCfg.quad       := io.inst(27)
  io.polygonCfg.textured   := io.inst(26)
  io.polygonCfg.rawTexture := io.inst(24)

  // ── Line-specific (opcode=2) ──
  io.lineCfg.gouraud  := io.inst(28)
  io.lineCfg.polyline := io.inst(27)

  // ── Rectangle-specific (opcode=3) ──
  io.rectangleCfg.size.assignFromBits(io.inst(28 downto 27))
  io.rectangleCfg.textured   := io.inst(26)
  io.rectangleCfg.rawTexture := io.inst(24)

  // ── GP0(E1h) Draw Mode ──
  io.drawMode.texturePageX     := io.inst(3 downto 0).asUInt
  io.drawMode.texturePageY     := (io.inst(11) ## io.inst(4)).asUInt
  io.drawMode.semiTransparency.assignFromBits(io.inst(6 downto 5))
  io.drawMode.textureColors.assignFromBits(io.inst(8 downto 7))
  io.drawMode.dither           := io.inst(9)
  io.drawMode.drawToDisplay    := io.inst(10)
  io.drawMode.flipX            := io.inst(12)
  io.drawMode.flipY            := io.inst(13)

  // ── GP0(E2h) Texture Window ──
  io.textureWindow.maskX   := io.inst(4 downto 0).asUInt
  io.textureWindow.maskY   := io.inst(9 downto 5).asUInt
  io.textureWindow.offsetX := io.inst(14 downto 10).asUInt
  io.textureWindow.offsetY := io.inst(19 downto 15).asUInt

  // ── GP0(E3h/E4h) Drawing Area ──
  io.drawingAreaTL.x := io.inst(9 downto 0).asUInt
  io.drawingAreaTL.y := io.inst(19 downto 10).asUInt
  io.drawingAreaBR.x := io.inst(9 downto 0).asUInt
  io.drawingAreaBR.y := io.inst(19 downto 10).asUInt

  // ── GP0(E5h) Drawing Offset ──
  io.drawingOffset.x := io.inst(10 downto 0).asSInt
  io.drawingOffset.y := io.inst(21 downto 11).asSInt

  // ── GP0(E6h) Mask Setting ──
  io.maskSetting.setMask   := io.inst(0)
  io.maskSetting.checkMask := io.inst(1)

  // ── Parameter word count / FIFO bypass ──
  // Delegated to shared Gp0Flow to avoid duplication with FifoController.

  io.paramWords := Gp0Flow.paramWords(io.inst)
  io.bypassFifo := Gp0Flow.bypassFifo(io.inst)

  // ── Misc command decoding ──
  io.clearCache := False
  when(op === Opcode.misc) {
    io.clearCache := io.inst(28 downto 24) === B"00001"  // GP0(01h) Clear Cache
  }
}
