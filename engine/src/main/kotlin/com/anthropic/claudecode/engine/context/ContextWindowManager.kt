package com.anthropic.claudecode.engine.context

import com.anthropic.claudecode.utils.tokens.TokenCounter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

/**
 * ContextWindowManager - manages context window limits
 * ContextWindowManager - 管理上下文窗口限制
 *
 * Maps from TypeScript services/claude/contextWindow.ts.
 * Tracks token usage against model context window limits,
 * triggers compaction when approaching limits.
 * 映射自 TypeScript services/claude/contextWindow.ts。
 * 跟踪令牌使用量与模型上下文窗口限制的关系，
 * 在接近限制时触发压缩。
 */
class ContextWindowManager(
    private val model: String = "claude-sonnet-4-20250514",
    private val tokenCounter: TokenCounter = TokenCounter()
) {
    private var currentContextTokens: Long = 0
    private var compactionCount: Int = 0

    /**
     * Get the maximum context window size for the model
     * 获取模型的最大上下文窗口大小
     */
    fun getMaxContextTokens(): Long = when {
        "opus" in model.lowercase() -> 200_000L
        "sonnet" in model.lowercase() -> 200_000L
        "haiku" in model.lowercase() -> 200_000L
        else -> 200_000L
    }

    /**
     * Get the maximum output tokens for the model
     * 获取模型的最大输出令牌数
     */
    fun getMaxOutputTokens(): Int = when {
        "opus" in model.lowercase() -> 32_000
        "sonnet" in model.lowercase() -> 16_000
        "haiku" in model.lowercase() -> 8_192
        else -> 16_000
    }

    /**
     * Update the current context token count
     * 更新当前上下文令牌计数
     */
    fun updateContextTokens(tokens: Long) {
        currentContextTokens = tokens
    }

    /**
     * Estimate context tokens from messages
     * 从消息估算上下文令牌数
     */
    fun estimateContextTokens(messages: List<String>, systemPrompt: String?): Long {
        var total = 0L
        systemPrompt?.let { total += tokenCounter.estimateTokens(it) }
        for (msg in messages) {
            total += tokenCounter.estimateTokens(msg)
        }
        currentContextTokens = total
        return total
    }

    /**
     * Check if compaction is needed
     * 检查是否需要压缩
     */
    fun shouldCompact(): Boolean {
        val maxTokens = getMaxContextTokens()
        val threshold = (maxTokens * COMPACT_THRESHOLD_RATIO).toLong()
        return currentContextTokens > threshold
    }

    /**
     * Check if we're close to the context limit
     * 检查是否接近上下文限制
     */
    fun isNearLimit(): Boolean {
        val maxTokens = getMaxContextTokens()
        val threshold = (maxTokens * NEAR_LIMIT_RATIO).toLong()
        return currentContextTokens > threshold
    }

    /**
     * Record that a compaction occurred
     * 记录发生了一次压缩
     */
    fun recordCompaction(newTokenCount: Long) {
        compactionCount++
        currentContextTokens = newTokenCount
        logger.info { "Context compacted (count=$compactionCount): $newTokenCount tokens" }
    }

    /**
     * Get context window status / 获取上下文窗口状态
     */
    fun getStatus(): ContextWindowStatus {
        val maxTokens = getMaxContextTokens()
        val usage = if (maxTokens > 0) currentContextTokens.toDouble() / maxTokens else 0.0

        return ContextWindowStatus(
            currentTokens = currentContextTokens,
            maxTokens = maxTokens,
            usagePercent = (usage * 100).toInt(),
            shouldCompact = shouldCompact(),
            isNearLimit = isNearLimit(),
            compactionCount = compactionCount
        )
    }

    companion object {
        const val COMPACT_THRESHOLD_RATIO = 0.75
        const val NEAR_LIMIT_RATIO = 0.90
    }
}

@Serializable
data class ContextWindowStatus(
    val currentTokens: Long = 0,
    val maxTokens: Long = 200_000,
    val usagePercent: Int = 0,
    val shouldCompact: Boolean = false,
    val isNearLimit: Boolean = false,
    val compactionCount: Int = 0
)
