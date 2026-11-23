package com.voicestudio.engine

import com.google.ai.edge.litert.Accelerator
import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ

internal object EngineModuleConfig {
    const val SAMPLE_RATE_HZ: Int = SYNTHESIS_SAMPLE_RATE_HZ
    val PREFERRED_VOCODER_ACCELERATOR: Accelerator = Accelerator.GPU
    val FALLBACK_VOCODER_ACCELERATOR: Accelerator = Accelerator.CPU
}
