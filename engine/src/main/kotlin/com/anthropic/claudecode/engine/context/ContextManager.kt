package com.anthropic.claudecode.engine.context

import com.anthropic.claudecode.engine.conversation.CompactionEngine
import com.anthropic.claudecode.engine.conversation.CompactionResult
import com.anthropic.claudecode.engine.conversation.CompactionStrategy
import com.anthropic.claudecode.services.api.AnthropicClient
import com.anthropic.claudecode.services.api.ApiMessage
import com.anthropic.claudecode.utils.tokens.TokenCounter
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * ContextManager - orchestrates context window management with compaction
 * ContextManager - 协调上下文窗口管理与压缩
 *
 * Maps from TypeScript services/claude/context.ts.
 * Bridges ContextWindowManager (tracking) and CompactionEngine (execution).
 * Automatically triggers compaction when context window fills up.
 * 映射自 TypeScript services/claude/context.ts。
 * 桥接 ContextWindowManager（跟踪）和 CompactionEngine（执行）。
 * 当上下文窗口填满时自动触发压缩。
 */
class ContextManager(
    private val apiClient: AnthropicClient,
    private val model: String = "claude-sonnet-4-20250514",
    private val tokenCounter: TokenCounter = TokenCounter()
) {
    private val windowManager = ContextWindowManager(model, tokenCounter)
    private val compactionEngine = CompactionEngine(apiClient, model, tokenCounter)

    /**
     * Check and compact messages if needed before API call
     * 在 API 调用前检查并在需要时压缩消息
     *
     * @return Original messages if no compaction needed, or compacted messages
     */
    suspend fun maybeCompact(
        messages: List<ApiMessage>,
        systemPrompt: String? = null
    ): CompactCheckResult {
        // Estimate current context usage / 估算当前上下文使用量
        val allContent = messages.joinToString("") { it.content }
        val systemTokens = systemPrompt?.let { tokenCounter.estimateTokens(it).toLong() } ?: 0L
        val totalTokens = tokenCounter.estimateTokens(allContent) + systemTokens
        windowManager.updateContextTokens(totalTokens)

        val status = windowManager.getStatus()
        logger.debug { "Context: ${status.usagePercent}% (${status.currentTokens}/${status.maxTokens} tokens)" }

        if (!status.shouldCompact) {
            return CompactCheckResult(messages = messages, compacted = false)
        }

        logger.info { "Context at ${status.usagePercent}%, triggering compaction" }

        // Choose strategy based on context pressure
        // 根据上下文压力选择策略
        val strategy = if (status.isNearLimit) {
            CompactionStrategy.Full()
        } else if (messages.size > 30) {
            CompactionStrategy.Rolling(keepRecentCount = 12)
        } else {
            CompactionStrategy.Micro
        }

        val result = compactionEngine.compact(messages, strategy, systemPrompt)
        windowManager.recordCompaction(result.tokensAfter)

        return CompactCheckResult(
            messages = result.messages,
            compacted = true,
            result = result
        )
    }

    /**
     * Force compaction with a specific strategy
     * 使用特定策略强制压缩
     */
    suspend fun forceCompact(
        messages: List<ApiMessage>,
        strategy: CompactionStrategy,
        systemPrompt: String? = null
    ): CompactionResult {
        val result = compactionEngine.compact(messages, strategy, systemPrompt)
        windowManager.recordCompaction(result.tokensAfter)
        return result
    }

    /** Get context window status / 获取上下文窗口状态 */
    fun getStatus(): ContextWindowStatus = windowManager.getStatus()

    /** Get max output tokens for model / 获取模型的最大输出令牌数 */
    fun getMaxOutputTokens(): Int = windowManager.getMaxOutputTokens()
}

data class CompactCheckResult(
    val messages: List<ApiMessage>,
    val compacted: Boolean,
    val result: CompactionResult? = null
)
