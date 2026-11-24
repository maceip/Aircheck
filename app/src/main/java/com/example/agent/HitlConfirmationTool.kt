package com.example.agent

import com.google.mlkit.genai.agent.AgentTool

/**
 * Human-in-the-loop (`AgentTool` decorator) guarding sensitive `ACCESS_LEVEL_SELF` AppFunctions
 * (`enqueueMacRigRender`, `triggerLocalVoiceSynthesis`, `requestRemotionInstall`, `updateStudioSettings`).
 *
 * Suspends execution until [onConfirm] returns explicit user approval; never executes outbound
 * or state-mutating actions autonomously when declined.
 */
class HitlConfirmationTool(
    delegate: AgentTool,
    private val onConfirm: suspend (
        toolName: String,
        args: Map<String, Any>
    ) -> Boolean
) : AgentTool(delegate) {

    override suspend fun execute(args: Map<String, Any>): Map<String, Any?> {
        val approved = onConfirm(name, args)
        if (!approved) {
            return mapOf(
                "status" to "CANCELLED_BY_USER",
                "tool" to name,
                "message" to "The user declined to confirm this studio action."
            )
        }
        return checkNotNull(delegate).execute(args)
    }

    companion object {
        val DEFAULT_GATED_TOOL_NAMES: Set<String> = setOf(
            "enqueueMacRigRender",
            "triggerLocalVoiceSynthesis",
            "requestRemotionInstall",
            "updateStudioSettings",
            "swapClauseTake",
            "updateGlobalGapScale"
        )

        fun shouldGateTool(toolName: String): Boolean =
            DEFAULT_GATED_TOOL_NAMES.any { gated ->
                toolName == gated || toolName.endsWith("#$gated") || toolName.endsWith(".$gated")
            }
    }
}
