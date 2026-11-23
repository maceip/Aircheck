package com.voicestudio.verifier

import ai.onnxruntime.OrtEnvironment
import com.voicestudio.edl.VerificationGateThresholds

internal object VerifierModuleConfig {
    val DEFAULT_THRESHOLDS: VerificationGateThresholds = VerificationGateThresholds()
    val ORT_ENV_CLASS: Class<OrtEnvironment> = OrtEnvironment::class.java
}
