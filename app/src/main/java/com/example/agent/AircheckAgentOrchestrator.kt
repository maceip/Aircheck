package com.example.agent

import android.content.Context
import com.google.mlkit.genai.agent.Agent
import com.google.mlkit.genai.agent.AgentEvent
import com.google.mlkit.genai.agent.AgentRunner
import com.google.mlkit.genai.agent.AgentSession
import com.google.mlkit.genai.agent.AgentTool
import com.google.mlkit.genai.agent.AppFunctionAgentTools
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AgentTurnSnapshot(
    val isRunning: Boolean = false,
    val activeToolName: String? = null,
    val responseText: String = "",
    val invokedTools: List<String> = emptyList(),
    val errorMessage: String? = null
)

/**
 * Multi-turn on-device agent orchestrator built strictly on `com.google.mlkit:genai-agent`
 * (`Agent`, `AgentRunner`, `AgentSession`, `AppFunctionAgentTools.discover`) with zero ADK.
 *
 * - Discovers Aircheck's `@AppFunctionDeclaration` tools programmatically via
 *   `AppFunctionAgentTools.discover(context)` (never combined with `enableAppFunctions(true)`).
 * - Wraps every sensitive `ACCESS_LEVEL_SELF` tool in [HitlConfirmationTool] so outbound renders
 *   and Remotion install actions require explicit user confirmation.
 * - Closes [AgentSession] and [AgentRunner] cleanly on [close].
 */
class AircheckAgentOrchestrator(
    context: Context,
    private val onConfirmSensitiveTool: suspend (toolName: String, args: Map<String, Any>) -> Boolean = { _, _ -> false }
) : AutoCloseable {

    private val appContext = context.applicationContext ?: context
    private val initMutex = Mutex()
    private var runner: AgentRunner? = null
    private var activeSession: AgentSession? = null

    private val _state = MutableStateFlow(AgentTurnSnapshot())
    val state: StateFlow<AgentTurnSnapshot> = _state.asStateFlow()

    suspend fun discoverConfiguredTools(): List<AgentTool> {
        val discovered = AppFunctionAgentTools.discover(appContext, Dispatchers.IO)
        return decorateToolsWithHitl(discovered, onConfirmSensitiveTool)
    }

    private suspend fun getOrCreateSession(sessionId: String = DEFAULT_SESSION_ID): AgentSession =
        initMutex.withLock {
            activeSession?.let { return@withLock it }
            val tools = discoverConfiguredTools()
            val agentBuilder = Agent.builder()
                .setName("AircheckStudioAgent")
                .setInstructions(
                    "You are Aircheck's local broadcast studio assistant. " +
                        "Use declared AppFunctions to inspect drafts, graph peers, VoiceStudio bundles, and Remotion setup."
                )
            if (tools.isNotEmpty()) {
                agentBuilder.addTools(tools)
            }
            val createdRunner = AgentRunner(agentBuilder.build())
            val createdSession = createdRunner.getOrCreateSession(sessionId)
            runner = createdRunner
            activeSession = createdSession
            createdSession
        }

    /**
     * Runs a multi-turn prompt through [AgentSession.runAsync], streaming [AgentEvent] updates
     * (`FunctionCall`, `FunctionResponse`, `TextMessage`, plus forward-compatible `else -> {}`).
     */
    suspend fun sendUserPrompt(prompt: String): Result<String> {
        val clean = prompt.trim()
        if (clean.isEmpty()) {
            return Result.failure(IllegalArgumentException("Prompt must not be empty."))
        }
        _state.value = AgentTurnSnapshot(isRunning = true)
        return try {
            val session = getOrCreateSession()
            val content = Content.builder().text(clean).build()
            val buffer = StringBuilder()
            val tools = mutableListOf<String>()

            session.runAsync(content).collect { event ->
                when (event) {
                    is AgentEvent.FunctionCall -> {
                        tools += event.name
                        _state.update {
                            it.copy(
                                activeToolName = event.name,
                                invokedTools = tools.toList()
                            )
                        }
                    }

                    is AgentEvent.FunctionResponse -> {
                        _state.update { it.copy(activeToolName = null) }
                    }

                    is AgentEvent.TextMessage -> {
                        buffer.append(event.text)
                        _state.update { it.copy(responseText = buffer.toString()) }
                    }

                    else -> {}
                }
            }

            val finalResponse = buffer.toString()
            _state.update { it.copy(isRunning = false, activeToolName = null, responseText = finalResponse) }
            Result.success(finalResponse)
        } catch (e: GenAiException) {
            val msg = e.localizedMessage ?: "On-device Gemini Nano agent unavailable (code=${e.errorCode})."
            _state.update { it.copy(isRunning = false, activeToolName = null, errorMessage = msg) }
            Result.failure(e)
        } catch (e: Throwable) {
            val msg = e.localizedMessage ?: "On-device agent execution failed."
            _state.update { it.copy(isRunning = false, activeToolName = null, errorMessage = msg) }
            Result.failure(e)
        }
    }

    /**
     * Stateless single-turn execution via [AgentRunner.runSingleTurnAsync] with zero retained history.
     */
    suspend fun runEphemeralSingleTurn(prompt: String): Result<String> {
        val clean = prompt.trim()
        if (clean.isEmpty()) {
            return Result.failure(IllegalArgumentException("Prompt must not be empty."))
        }
        return try {
            getOrCreateSession()
            val activeRunner = checkNotNull(runner)
            val content = Content.builder().text(clean).build()
            val sb = StringBuilder()
            activeRunner.runSingleTurnAsync(content).collect { event ->
                when (event) {
                    is AgentEvent.TextMessage -> sb.append(event.text)
                    else -> {}
                }
            }
            Result.success(sb.toString())
        } catch (e: GenAiException) {
            Result.failure(e)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override fun close() {
        runCatching { activeSession?.close() }
        runCatching { runner?.close() }
        activeSession = null
        runner = null
    }

    companion object {
        const val DEFAULT_SESSION_ID: String = "aircheck_studio_session"

        fun decorateToolsWithHitl(
            tools: List<AgentTool>,
            onConfirm: suspend (toolName: String, args: Map<String, Any>) -> Boolean
        ): List<AgentTool> = tools.map { tool ->
            if (HitlConfirmationTool.shouldGateTool(tool.name)) {
                HitlConfirmationTool(tool, onConfirm)
            } else {
                tool
            }
        }
    }
}
