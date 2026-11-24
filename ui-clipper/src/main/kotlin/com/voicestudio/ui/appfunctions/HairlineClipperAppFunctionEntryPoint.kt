package com.voicestudio.ui.appfunctions

import androidx.appfunctions.AppFunctionAccessLevel
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import androidx.appfunctions.ExperimentalAppFunctionsApi
import androidx.appfunctions.metadata.AppFunctionMetadata
import com.voicestudio.edl.ClipperManifestRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Summary of a synthesized clause inside the Hairline Clipper timeline.
 *
 * @property clauseId Stable clause identifier (for example, `c_001`).
 * @property rawText Plain spoken text of this clause.
 * @property selectedTakeIndex Currently active zero-based take index.
 * @property candidateCount Total number of verified candidate takes available for this clause.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class ClipperClauseSummary(
    val clauseId: String,
    val rawText: String,
    val selectedTakeIndex: Int,
    val candidateCount: Int
)

/**
 * Snapshot of a VoiceStudio project's Hairline Clipper edit decision list.
 *
 * @property projectId Unique VoiceStudio project identifier.
 * @property globalGapScale Inter-clause breath compression factor in `[0.25, 1.0]`.
 * @property clauses Ordered list of clauses in the project timeline.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class ClipperTimelineSnapshot(
    val projectId: String,
    val globalGapScale: Float,
    val clauses: List<ClipperClauseSummary>
)

/**
 * Android 17 (`CINNAMON_BUN`) `:ui-clipper` module AppFunction service entry point exposing
 * zero-crossing take selection and global breath-gap compression.
 */
@AppFunctionServiceEntryPoint(
    serviceName = "HairlineClipperAppFunctionService",
    appFunctionXmlFileName = "hairline_clipper_app_function_service"
)
abstract class HairlineClipperAppFunctionEntryPoint : AppFunctionService() {

    /**
     * Inspects the clauses, active take selections, and gap compression scale for a VoiceStudio project.
     *
     * @param projectId Unique identifier of the VoiceStudio project to inspect.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun inspectProjectClauses(
        projectId: String
    ): ClipperTimelineSnapshot = withContext(Dispatchers.IO) {
        val cleanId = projectId.trim()
        if (cleanId.isEmpty()) {
            throw AppFunctionInvalidArgumentException("projectId must not be blank.")
        }
        val store = ClipperManifestRegistry.store
            ?: throw AppFunctionAppUnknownException("ClipperManifestStore is not initialized.")
        val manifest = store.getManifest(cleanId)
            ?: throw AppFunctionElementNotFoundException("Project '$cleanId' was not found.")
        ClipperTimelineSnapshot(
            projectId = manifest.projectId,
            globalGapScale = manifest.globalGapScale,
            clauses = manifest.clauses.map { node ->
                ClipperClauseSummary(
                    clauseId = node.clauseId,
                    rawText = node.rawText,
                    selectedTakeIndex = node.selectedTakeIndex,
                    candidateCount = node.candidates.size
                )
            }
        )
    }

    /**
     * Selects a different verified audio take for a specific clause in the Hairline Clipper timeline.
     *
     * @param projectId Unique identifier of the VoiceStudio project.
     * @param clauseId Identifier of the clause to update (for example, `c_001`).
     * @param takeIndex Zero-based candidate take index to activate.
     */
    @OptIn(ExperimentalAppFunctionsApi::class)
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    @AppFunctionAccessLevel(
        level = AppFunctionMetadata.ACCESS_LEVEL_SELF,
        isCompatEnforcementEnabled = true
    )
    suspend fun swapClauseTake(
        projectId: String,
        clauseId: String,
        takeIndex: Int
    ): ClipperTimelineSnapshot = withContext(Dispatchers.IO) {
        val cleanId = projectId.trim()
        val cleanClause = clauseId.trim()
        if (cleanId.isEmpty() || cleanClause.isEmpty()) {
            throw AppFunctionInvalidArgumentException("projectId and clauseId must not be blank.")
        }
        val store = ClipperManifestRegistry.store
            ?: throw AppFunctionAppUnknownException("ClipperManifestStore is not initialized.")
        val manifest = store.getManifest(cleanId)
            ?: throw AppFunctionElementNotFoundException("Project '$cleanId' was not found.")
        val target = manifest.clauses.firstOrNull { it.clauseId == cleanClause }
            ?: throw AppFunctionElementNotFoundException("Clause '$cleanClause' was not found in '$cleanId'.")
        if (takeIndex < 0 || takeIndex >= target.candidates.size) {
            throw AppFunctionInvalidArgumentException(
                "takeIndex $takeIndex is out of range (0 until ${target.candidates.size})."
            )
        }
        val updated = manifest.copy(
            clauses = manifest.clauses.map { node ->
                if (node.clauseId == cleanClause) node.copy(selectedTakeIndex = takeIndex) else node
            }
        )
        store.saveManifest(updated)
        ClipperTimelineSnapshot(
            projectId = updated.projectId,
            globalGapScale = updated.globalGapScale,
            clauses = updated.clauses.map { node ->
                ClipperClauseSummary(
                    clauseId = node.clauseId,
                    rawText = node.rawText,
                    selectedTakeIndex = node.selectedTakeIndex,
                    candidateCount = node.candidates.size
                )
            }
        )
    }

    /**
     * Updates the global inter-clause breath gap compression factor for a VoiceStudio project.
     *
     * @param projectId Unique identifier of the VoiceStudio project.
     * @param globalGapScale Gap scale factor between `0.25` (tightest) and `1.0` (natural 90 ms gaps).
     */
    @OptIn(ExperimentalAppFunctionsApi::class)
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    @AppFunctionAccessLevel(
        level = AppFunctionMetadata.ACCESS_LEVEL_SELF,
        isCompatEnforcementEnabled = true
    )
    suspend fun updateGlobalGapScale(
        projectId: String,
        globalGapScale: Float
    ): ClipperTimelineSnapshot = withContext(Dispatchers.IO) {
        val cleanId = projectId.trim()
        if (cleanId.isEmpty()) {
            throw AppFunctionInvalidArgumentException("projectId must not be blank.")
        }
        if (!globalGapScale.isFinite() || globalGapScale !in 0.25f..1.0f) {
            throw AppFunctionInvalidArgumentException("globalGapScale must be within [0.25, 1.0].")
        }
        val store = ClipperManifestRegistry.store
            ?: throw AppFunctionAppUnknownException("ClipperManifestStore is not initialized.")
        val manifest = store.getManifest(cleanId)
            ?: throw AppFunctionElementNotFoundException("Project '$cleanId' was not found.")
        val updated = manifest.copy(globalGapScale = globalGapScale)
        store.saveManifest(updated)
        ClipperTimelineSnapshot(
            projectId = updated.projectId,
            globalGapScale = updated.globalGapScale,
            clauses = updated.clauses.map { node ->
                ClipperClauseSummary(
                    clauseId = node.clauseId,
                    rawText = node.rawText,
                    selectedTakeIndex = node.selectedTakeIndex,
                    candidateCount = node.candidates.size
                )
            }
        )
    }
}
