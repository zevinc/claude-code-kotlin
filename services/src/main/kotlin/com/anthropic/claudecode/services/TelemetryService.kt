package com.anthropic.claudecode.services

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * TelemetryService - privacy-respecting usage analytics
 * TelemetryService - 隐私尊重的使用分析
 *
 * Maps from TypeScript services/telemetry.ts.
 * Collects anonymous usage events locally. Data is never sent
 * without explicit user consent. Used for /cost and debugging.
 * Tracks: tool uses, API calls, commands, session duration, errors.
 * 映射自 TypeScript services/telemetry.ts。
 * 在本地收集匿名使用事件。数据在没有明确用户同意的情况下
 * 不会被发送。用于 /cost 和调试。
 * 跟踪：工具使用、API调用、命令、会话时长、错误。
 */
class TelemetryService(
    private val enabled: Boolean = true,
    private val storageDir: String? = null
) {
    private val events = ConcurrentLinkedQueue<TelemetryEvent>()
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionStart = Instant.now()
    private val totalInputTokens = AtomicLong(0)
    private val totalOutputTokens = AtomicLong(0)
    private val totalCacheReadTokens = AtomicLong(0)
    private val totalCacheCreateTokens = AtomicLong(0)
    private val totalCostCents = AtomicLong(0)

    /** Record a generic event / 记录通用事件 */
    fun record(event: TelemetryEvent) {
        if (!enabled) return
        events.add(event)
        if (events.size > MAX_BUFFER_SIZE) flush()
    }

    /** Record a tool use event / 记录工具使用事件 */
    fun recordToolUse(toolName: String, durationMs: Long, success: Boolean) {
        record(TelemetryEvent(
            type = "tool_use", name = toolName,
            durationMs = durationMs, success = success,
            timestamp = Instant.now().toString()
        ))
    }

    /** Record a command use event / 记录命令使用事件 */
    fun recordCommand(commandName: String) {
        record(TelemetryEvent(
            type = "command", name = commandName,
            timestamp = Instant.now().toString()
        ))
    }

    /** Record an API call with full token tracking / 记录带完整令牌跟踪的 API 调用 */
    fun recordApiCall(
        model: String, inputTokens: Long, outputTokens: Long,
        cacheReadTokens: Long = 0, cacheCreateTokens: Long = 0,
        durationMs: Long = 0, costCentsE4: Long = 0
    ) {
        totalInputTokens.addAndGet(inputTokens)
        totalOutputTokens.addAndGet(outputTokens)
        totalCacheReadTokens.addAndGet(cacheReadTokens)
        totalCacheCreateTokens.addAndGet(cacheCreateTokens)
        totalCostCents.addAndGet(costCentsE4)

        record(TelemetryEvent(
            type = "api_call", name = model,
            durationMs = durationMs,
            inputTokens = inputTokens, outputTokens = outputTokens,
            cacheReadTokens = cacheReadTokens, cacheCreateTokens = cacheCreateTokens,
            timestamp = Instant.now().toString()
        ))
    }

    /** Record an error / 记录错误 */
    fun recordError(source: String, errorMessage: String) {
        record(TelemetryEvent(
            type = "error", name = source,
            success = false, errorMessage = errorMessage,
            timestamp = Instant.now().toString()
        ))
    }

    /** Record a compaction event / 记录压缩事件 */
    fun recordCompaction(strategy: String, tokensBefore: Long, tokensAfter: Long) {
        record(TelemetryEvent(
            type = "compaction", name = strategy,
            inputTokens = tokensBefore, outputTokens = tokensAfter,
            timestamp = Instant.now().toString()
        ))
    }

    /** Get comprehensive session statistics / 获取全面的会话统计 */
    fun getStats(): TelemetryStats {
        val toolUses = events.filter { it.type == "tool_use" }
        val apiCalls = events.filter { it.type == "api_call" }
        val commands = events.filter { it.type == "command" }
        val errors = events.filter { it.type == "error" }
        val sessionDuration = Duration.between(sessionStart, Instant.now())

        return TelemetryStats(
            totalEvents = events.size,
            toolUseCount = toolUses.size,
            apiCallCount = apiCalls.size,
            commandCount = commands.size,
            errorCount = errors.size,
            totalInputTokens = totalInputTokens.get(),
            totalOutputTokens = totalOutputTokens.get(),
            totalCacheReadTokens = totalCacheReadTokens.get(),
            totalCacheCreateTokens = totalCacheCreateTokens.get(),
            totalCostUsd = totalCostCents.get() / 1_000_000.0,
            avgToolDurationMs = if (toolUses.isNotEmpty()) toolUses.map { it.durationMs }.average().toLong() else 0,
            sessionDurationSeconds = sessionDuration.seconds,
            topTools = toolUses.groupBy { it.name }
                .mapValues { it.value.size }
                .entries.sortedByDescending { it.value }
                .take(10)
                .associate { it.key to it.value }
        )
    }

    /** Flush events to persistent storage / 刷新事件到持久化存储 */
    fun flush() {
        if (storageDir == null || events.isEmpty()) return
        try {
            val file = File(storageDir, "telemetry.jsonl")
            file.parentFile?.mkdirs()
            val entries = mutableListOf<TelemetryEvent>()
            while (events.isNotEmpty()) {
                events.poll()?.let { entries.add(it) }
            }
            val lines = entries.joinToString("\n") { json.encodeToString(TelemetryEvent.serializer(), it) }
            file.appendText(lines + "\n")
        } catch (e: Exception) {
            logger.debug { "Telemetry flush failed: ${e.message}" }
        }
    }

    companion object {
        const val MAX_BUFFER_SIZE = 500
    }
}

@Serializable
data class TelemetryEvent(
    val type: String,
    val name: String = "",
    val durationMs: Long = 0,
    val success: Boolean = true,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheCreateTokens: Long = 0,
    val errorMessage: String? = null,
    val timestamp: String = ""
)

data class TelemetryStats(
    val totalEvents: Int = 0,
    val toolUseCount: Int = 0,
    val apiCallCount: Int = 0,
    val commandCount: Int = 0,
    val errorCount: Int = 0,
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val totalCacheReadTokens: Long = 0,
    val totalCacheCreateTokens: Long = 0,
    val totalCostUsd: Double = 0.0,
    val avgToolDurationMs: Long = 0,
    val sessionDurationSeconds: Long = 0,
    val topTools: Map<String, Int> = emptyMap()
)
