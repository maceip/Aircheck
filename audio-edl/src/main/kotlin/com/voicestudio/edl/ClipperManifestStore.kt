package com.voicestudio.edl

/**
 * Shared cross-module persistence contract allowing `:ui-clipper`'s AppFunctions and timeline
 * controllers to read and update the Room-backed [VoiceProjectManifest] without circular module
 * dependencies.
 */
interface ClipperManifestStore {
    suspend fun getManifest(projectId: String): VoiceProjectManifest?
    suspend fun saveManifest(manifest: VoiceProjectManifest)
}

object ClipperManifestRegistry {
    @Volatile
    var store: ClipperManifestStore? = null

    fun resetForTest() {
        store = null
    }
}
