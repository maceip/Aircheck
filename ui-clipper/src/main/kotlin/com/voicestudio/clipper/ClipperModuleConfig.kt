package com.voicestudio.clipper

import androidx.annotation.OptIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Transformer
import com.voicestudio.edl.VERNIER_COARSE_SAMPLES_PER_DP
import com.voicestudio.edl.VERNIER_FINE_SAMPLES_PER_INCH

internal object ClipperModuleConfig {
    const val COARSE_SAMPLES_PER_DP: Int = VERNIER_COARSE_SAMPLES_PER_DP
    const val FINE_SAMPLES_PER_INCH: Int = VERNIER_FINE_SAMPLES_PER_INCH

    @OptIn(UnstableApi::class)
    val TRANSFORMER_CLASS: Class<Transformer> = Transformer::class.java
}

@Composable
internal fun ClipperThemeCheck(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
