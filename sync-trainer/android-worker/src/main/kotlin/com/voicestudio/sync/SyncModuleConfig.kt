package com.voicestudio.sync

import androidx.work.NetworkType
import com.voicestudio.edl.FOREGROUND_RAM_CEILING_MB

internal object SyncModuleConfig {
    val SYNC_NETWORK_TYPE: NetworkType = NetworkType.UNMETERED
    const val RAM_CEILING_MB: Int = FOREGROUND_RAM_CEILING_MB
}
