package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Runs OpenAI Codex CLI in the same private ARM64 PRoot workspace as the other agents.
 * JSONL output is translated into the shared Mobile Harness chat events.
 */
class CodexRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir, context)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested = false

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        activeSessionId = sessionId
        userStopRequested = false
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        var process: Process? = null
        try {
            check(installer.isAgentInstalled(com.jarves.mh.model.AgentKind.CODEX_CLI)) {
                "Codex CLI is not installed. Open Settings → Coding agent to install it."
            }
            val installed = installer.installedRuntime()
            val apiKey = secretFor(provider).orEmpty()
            val chatLoginExists = File(installed.rootfs, "root/.codex/auth.json").isFile
            check(apiKey.isNotBlank() || chatLoginExists) {
                "Sign in with your ChatGPT plan: open the terminal and run 'codex login --device-auth', then complete browser verification. Alternatively, configure an OpenAI API key."
            }
            if (provider.kind == ProviderKind.CUSTOM && apiKey.isNotBlank()) {
                check(provider.baseUrl.isNotBlank()) {
                    "Set the OpenAI-compatible API base URL in Settings → AI provider."
                }
            }
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            val guestWorkspace = "/workspace/$projectSlug"
            val environment = linkedMapOf(
                "CODEX_HOME" to "/root/.codex",
                "DISABLE_TELEMETRY" to "1",
                "NO_COLOR" to "1",
            )
            if (apiKey.isNotBlank()) environment["OPENAI_API_KEY"] = apiKey
            if (provider.kind == ProviderKind.CUSTOM && apiKey.isNotBlank()) environment["OPENAI_BASE_URL"] = provider.baseUrl.trimEnd('/')
            val history = conversationHistory.takeLast(8).joinToString("\n\n") { message ->
                (if (message.fromUser) "User: " else "Assistant: ") + message.text.take(8_000)
            }
            val fullPrompt = buildString {
                append("You are working inside Mobile Harness on Android ARM64. ")
                append("Work only in the current project directory and complete the user's request. ")
                append("Project type: ").append(projectKind.name).append(".\n")
                if (history.isNotBlank()) append("Recent conversation:\n").append(history).append("\n\n")
                append("Current request:\n").append(prompt)
            }
            val command = buildList {
                add(CODEX_GUEST_PATH)
                add("exec")
                add("--json")
                // Codex CLI 0.162.x provides --approve-for-me as the supported
                // non-interactive approval mode and configures workspace-write itself.
                // Do not also pass --sandbox: let this single option own the approval/sandbox
                // policy so the CLI does not receive conflicting policy configuration.
                add("--approve-for-me")
                add("--skip-git-repo-check")
                if (provider.kind == ProviderKind.CUSTOM && apiKey.isNotBlank()) {
                    val baseUrl = provider.baseUrl.trimEnd('/').replace("\\", "\\\\").replace("\"", "\\\"")
                    val wireApi = if (provider.dshApi == "openai-completions") "chat" else "responses"
                    add("--config")
                    add("model_provider=\"mobile_harness\"")
                    add("--config")
                    add("model_providers.mobile_harness.name=\"Mobile Harness\"")
                    add("--config")
                    add("model_providers.mobile_harness.base_url=\"$baseUrl\"")
                    add("--config")
                    add("model_providers.mobile_harness.wire_api=\"$wireApi\"")
                    add("--config")
                    add("model_providers.mobile_harness.requires_openai_auth=true")
                }
                if (provider.model.isNotBlank()) {
                    add("--model")
                    add(provider.model)
                }
                // Match Codex's documented stdin-prompt mode. The prompt is sent over stdin
                // exactly once, rather than as argv while the CLI also observes an open stdin.
                add("-")
            }
            val running = installer.process(
                proot = installed.proot,
                rootfs = installed.rootfs,
                workspace = workspace,
                environment = environment,
                guestCommand = command,
                guestWorkspacePath = guestWorkspace,
            )
            process = running
            activeProcess = running
            // Codex exec accepts "-" to read the prompt from stdin. Write the complete
            // context once, then close stdin to signal EOF and let the non-interactive turn run.
            running.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(fullPrompt)
                writer.newLine()
                writer.flush()
            }
            val output = StringBuilder()
            val native = running as? NativeSpawnProcess
                ?: error("Unsupported Android runtime process")
            var outputOffset = 0L
            var pendingOutput = ""
            var discardingOversizedLine = false
            while (running.isAlive || native.outputFile.length() > outputOffset) {
                val file = native.outputFile
                if (file.length() > outputOffset) {
                    RandomAccessFile(file, "r").use { input ->
                        input.seek(outputOffset)
                        val available = (input.length() - outputOffset).coerceAtMost(256 * 1024).toInt()
                        val bytes = ByteArray(available)
                        input.readFully(bytes)
                        outputOffset += available
                        pendingOutput += bytes.toString(Charsets.UTF_8)
                    }
                    val parts = pendingOutput.replace('\r', '\n').split('\n')
                    pendingOutput = parts.last()
                    for (raw in parts.dropLast(1)) {
                        if (raw.length > 128 * 1024) {
                            discardingOversizedLine = true
                            continue
                        }
                        if (discardingOversizedLine) {
                            discardingOversizedLine = false
                            continue
                        }
                        handleOutputLine(sessionId, raw, output)
                    }
                } else {
                    delay(60)
                }
                if (userStopRequested && running.isAlive) running.destroy()
            }
            if (pendingOutput.isNotBlank() && !discardingOversizedLine) {
                handleOutputLine(sessionId, pendingOutput, output)
            }
            val exit = running.waitFor()
            if (userStopRequested) throw IllegalStateException("Stopped by user")
            val changed = checkpoints.changedFiles(workspace, before)
            if (changed.isNotEmpty()) {
                checkpoints.saveChangedPaths(projectId, changed)
                eventBus.emit(RuntimeEvent.FilesChanged(
                    sessionId,
                    checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId)),
                ))
            } else if (!File(checkpoints.checkpointDir(projectId), "changes.json").isFile) {
                acceptLastChanges(projectId)
            }
            check(exit == 0) {
                output.toString().takeLast(1_500).ifBlank { "Codex CLI stopped with exit code $exit" }
            }
            if (finishedSessions.add(sessionId)) eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
        } catch (error: Throwable) {
            Log.e("CodexRuntimeBridge", "Codex session failed", error)
            eventBus.emit(RuntimeEvent.SessionFailed(
                sessionId,
                if (userStopRequested) "Stopped by user" else error.message?.take(1_500) ?: "Codex CLI failed",
            ))
        } finally {
            process?.let { if (it.isAlive) it.destroy() }
            activeProcess = null
            activeSessionId = null
        }
        sessionId
    }

    private suspend fun handleOutputLine(sessionId: String, line: String, output: StringBuilder) {
        if (line.isBlank()) return
        val event = runCatching { JSONObject(line) }.getOrNull()
        if (event == null) {
            appendBounded(output, line)
            eventBus.emit(RuntimeEvent.RuntimeLog(sessionId, "Codex CLI", line.take(1_000)))
            return
        }
        when (event.optString("type")) {
            "item.completed", "item.updated" -> {
                val item = event.optJSONObject("item") ?: return
                when (item.optString("type")) {
                    "agent_message" -> {
                        val text = item.optString("text")
                        if (text.isNotBlank()) {
                            appendBounded(output, text)
                            eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, text))
                        }
                    }
                    "command_execution" -> eventBus.emit(
                        RuntimeEvent.ToolCompleted(
                            sessionId,
                            "Shell",
                            item.optString("aggregated_output").ifBlank { item.optString("command") }.take(1_000),
                        ),
                    )
                    "reasoning" -> eventBus.emit(RuntimeEvent.ReasoningProgress(sessionId, 1))
                }
            }
            "item.started" -> {
                val item = event.optJSONObject("item")
                if (item != null) eventBus.emit(
                    RuntimeEvent.ToolStarted(
                        sessionId,
                        item.optString("type", "Codex task"),
                        item.optString("command").ifBlank { item.optString("text") }.take(1_000),
                    ),
                )
            }
            "error", "turn.failed" -> {
                val message = event.optString("message").ifBlank {
                    event.optJSONObject("error")?.optString("message").orEmpty()
                }
                if (message.isNotBlank()) appendBounded(output, message)
            }
        }
    }

    private fun appendBounded(target: StringBuilder, text: String) {
        target.append(text).append('\n')
        if (target.length > 32_000) target.delete(0, target.length - 32_000)
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) = Unit

    override suspend fun stopSession(sessionId: String) {
        if (activeSessionId != sessionId) return
        userStopRequested = true
        activeProcess?.destroy()
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpoints.checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        if (!backup.isDirectory) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.readChangedPaths(projectId).forEach { path ->
            val target = checkpoints.safeWorkspaceFile(workspace, path)
            val source = checkpoints.safeWorkspaceFile(backup, path)
            if (source.isFile) {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            } else target.delete()
        }
        checkpoint.deleteRecursively()
        true
    }

    override suspend fun acceptLastChanges(projectId: String) {
        checkpoints.checkpointDir(projectId).deleteRecursively()
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val paths = checkpoints.readChangedPaths(projectId)
        if (paths.isEmpty()) emptyList()
        else checkpoints.buildChangeDetails(projectId, checkpoints.ensureWorkspace(projectId), paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val target = checkpoints.safeWorkspaceFile(checkpoints.ensureWorkspace(projectId), path)
        val source = checkpoints.safeWorkspaceFile(File(checkpoints.checkpointDir(projectId), "project"), path)
        if (source.isFile) {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        } else target.delete()
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean {
        checkpoints.removeChangedPath(projectId, path)
        return true
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    companion object {
        const val CODEX_GUEST_PATH = "/usr/local/lib/nodejs/bin/codex"
    }
}
