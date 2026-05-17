package PsxGpu

import spinal.core._
import spinal.core.sim._

object Config {
    def spinal = SpinalConfig(
        targetDirectory = "gen"
    )

    def sim = SimConfig.withConfig(spinal).withFstWave
}