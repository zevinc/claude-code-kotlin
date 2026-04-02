package com.anthropic.claudecode.services.hooks

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Hook execution engine - runs pre/post hooks for tool and session events
 * Hook 执行引擎 - 为工具和会话事件运行前置/后置钩子
 *
 * Maps from TypeScript utils/hooks.ts.
 * Supports command hooks (shell commands), with JSON stdin/stdout protocol.
 * 映射自 TypeScript utils/hooks.ts。
 * 支持命令钩子（shell 命令），使用 JSON stdin/stdout 协议。
 */
class HookEngine {

    private val hooks = ConcurrentHashMap<HookEvent, MutableList<HookDefinition>>()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Register a hook for an event
     * 为事件注册钩子
     */
    fun register(event: HookEvent, hook: HookDefinition) {
        hooks.getOrPut(event) { mutableListOf() }.add(hook)
        logger.debug { "Registered hook for $event: ${hook.name}" }
    }

    /**
     * Register hooks from settings configuration
     * 从设置配置注册钩子
     */
    fun registerFromSettings(hooksConfig: Map<String, List<HookSettingsEntry>>) {
        for ((eventName, entries) in hooksConfig) {
            val event = HookEvent.fromString(eventName) ?: continue
            for (entry in entries) {
                register(event, HookDefinition(
                    name = entry.command?.take(40) ?: "hook",
                    type = HookType.COMMAND,
                    command = entry.command,
                    timeout = entry.timeout ?: DEFAULT_TIMEOUT_MS
                ))
            }
        }
    }

    /**
     * Execute all hooks for an event
     * 执行事件的所有钩子
     *
     * @param event The hook event / 钩子事件
     * @param context Context data passed to hooks as JSON stdin / 作为 JSON stdin 传递给钩子的上下文数据
     * @return Combined hook results / 合并的钩子结果
     */
    suspend fun execute(event: HookEvent, context: JsonObject): HookResult {
        val eventHooks = hooks[event] ?: return HookResult.empty()

        val results = mutableListOf<SingleHookResult>()
        var blocked = false

        for (hook in eventHooks) {
            val result = executeHook(hook, context)
            results.add(result)

            if (result.blocked) {
                blocked = true
                logger.info { "Hook '${hook.name}' blocked event $event" }
                break // Stop executing further hooks / 停止执行更多钩子
            }
        }

        return HookResult(
            results = results,
            blocked = blocked,
            event = event
        )
    }

    private suspend fun executeHook(hook: HookDefinition, context: JsonObject): SingleHookResult {
        return when (hook.type) {
            HookType.COMMAND -> executeCommandHook(hook, context)
        }
    }

    /**
     * Execute a command hook by running a shell command with JSON on stdin
     * 通过运行 shell 命令并在 stdin 上传递 JSON 来执行命令钩子
     */
    private fun executeCommandHook(hook: HookDefinition, context: JsonObject): SingleHookResult {
        val command = hook.command ?: return SingleHookResult(
            hookName = hook.name, success = false, error = "No command specified"
        )

        return try {
            val process = ProcessBuilder("/bin/bash", "-c", command)
                .redirectErrorStream(false)
                .start()

            // Write context JSON to stdin / 将上下文 JSON 写入 stdin
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(context.toString())
            }

            val completed = process.waitFor(hook.timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                return SingleHookResult(hookName = hook.name, success = false, error = "Hook timed out")
            }

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.exitValue()

            // Parse stdout for hook response / 解析 stdout 获取钩子响应
            val response = try {
                if (stdout.isNotBlank()) json.parseToJsonElement(stdout).jsonObject else null
            } catch (_: Exception) { null }

            val blocked = response?.get("blocked")?.jsonPrimitive?.booleanOrNull ?: false
            val message = response?.get("message")?.jsonPrimitive?.content

            SingleHookResult(
                hookName = hook.name,
                success = exitCode == 0,
                exitCode = exitCode,
                stdout = stdout.take(MAX_OUTPUT_LENGTH),
                stderr = stderr.take(MAX_OUTPUT_LENGTH),
                blocked = blocked,
                message = message
            )
        } catch (e: Exception) {
            logger.error(e) { "Hook execution failed: ${hook.name}" }
            SingleHookResult(hookName = hook.name, success = false, error = e.message)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MAX_OUTPUT_LENGTH = 8000
    }
}

/**
 * Hook events that can trigger hooks
 * 可以触发钩子的钩子事件
 */
enum class HookEvent {
    PRE_TOOL_USE,
    POST_TOOL_USE,
    POST_TOOL_USE_FAILURE,
    USER_PROMPT_SUBMIT,
    SESSION_START,
    SESSION_END,
    STOP,
    SUBAGENT_START,
    SUBAGENT_STOP,
    PRE_COMPACT,
    POST_COMPACT,
    NOTIFICATION,
    PERMISSION_DENIED,
    PERMISSION_REQUEST;

    companion object {
        fun fromString(name: String): HookEvent? = try {
            valueOf(name.uppercase().replace(Regex("([a-z])([A-Z])"), "$1_$2"))
        } catch (_: Exception) {
            // Try camelCase conversion / 尝试驼峰转换
            entries.find { it.name.equals(name.replace("-", "_"), ignoreCase = true) }
        }
    }
}

enum class HookType { COMMAND }

data class HookDefinition(
    val name: String,
    val type: HookType,
    val command: String? = null,
    val timeout: Long = HookEngine.DEFAULT_TIMEOUT_MS
)

@Serializable
data class HookSettingsEntry(
    val command: String? = null,
    val timeout: Long? = null
)

data class HookResult(
    val results: List<SingleHookResult>,
    val blocked: Boolean,
    val event: HookEvent
) {
    companion object {
        fun empty() = HookResult(results = emptyList(), blocked = false, event = HookEvent.SESSION_START)
    }
}

data class SingleHookResult(
    val hookName: String,
    val success: Boolean = true,
    val exitCode: Int? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val blocked: Boolean = false,
    val message: String? = null,
    val error: String? = null
)
