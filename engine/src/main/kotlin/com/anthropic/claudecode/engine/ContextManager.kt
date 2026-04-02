package com.anthropic.claudecode.engine

import com.anthropic.claudecode.types.ContentBlock
import com.anthropic.claudecode.types.Message
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Context manager - handles conversation context window management
 * 上下文管理器 - 处理对话上下文窗口管理
 *
 * Maps from TypeScript auto-compact, context-collapse, and history-snip features.
 * Manages the conversation messages to fit within model token limits while
 * preserving important context.
 * 映射自 TypeScript 的自动压缩、上下文折叠和历史裁剪功能。
 * 管理对话消息以适应模型令牌限制，同时保留重要上下文。
 */
class ContextManager(
    /** Maximum context tokens for the model / 模型的最大上下文令牌数 */
    private val maxContextTokens: Int = DEFAULT_MAX_CONTEXT_TOKENS,
    /** Threshold ratio for triggering auto-compact / 触发自动压缩的阈值比率 */
    private val compactThreshold: Double = DEFAULT_COMPACT_THRESHOLD
) {
    /**
     * Auto-compact tracking state
     * 自动压缩跟踪状态
     */
    private var lastCompactBoundary: Int = 0
    private var compactCount: Int = 0

    /**
     * Check if context needs compaction and apply if necessary
     * 检查上下文是否需要压缩，如果需要则应用
     *
     * @param messages Current conversation messages / 当前对话消息
     * @return Compacted messages or original if no compaction needed / 压缩后的消息或未需要压缩时的原始消息
     */
    fun maybeCompact(messages: List<Message>): CompactResult {
        val estimatedTokens = estimateTokenCount(messages)

        if (estimatedTokens < maxContextTokens * compactThreshold) {
            return CompactResult(messages, compacted = false)
        }

        logger.info { "Context approaching limit ($estimatedTokens tokens), compacting..." }
        return compact(messages)
    }

    /**
     * Compact messages by summarizing older messages
     * 通过摘要旧消息来压缩消息
     *
     * Strategy:
     * 1. Keep recent messages intact (last N turns)
     * 2. Summarize older messages into a compact form
     * 3. Preserve system messages and important context
     *
     * 策略：
     * 1. 保留最近的消息（最后 N 轮）
     * 2. 将旧消息摘要为紧凑形式
     * 3. 保留系统消息和重要上下文
     */
    fun compact(messages: List<Message>): CompactResult {
        if (messages.size <= KEEP_RECENT_COUNT * 2) {
            return CompactResult(messages, compacted = false)
        }

        val recentMessages = messages.takeLast(KEEP_RECENT_COUNT * 2)
        val olderMessages = messages.dropLast(KEEP_RECENT_COUNT * 2)

        // Build summary of older messages / 构建旧消息的摘要
        val summary = buildSummary(olderMessages)
        val summaryMessage = Message.System(
            uuid = java.util.UUID.randomUUID().toString(),
            timestamp = java.time.Instant.now().toString(),
            content = "[Context compacted: ${olderMessages.size} messages summarized]\n$summary",
            subtype = "notification"
        )

        val compactedMessages = listOf<Message>(summaryMessage) + recentMessages
        lastCompactBoundary = 1 // Summary is at index 0
        compactCount++

        logger.info {
            "Compacted ${olderMessages.size} messages into summary, " +
            "kept ${recentMessages.size} recent messages"
        }

        return CompactResult(compactedMessages, compacted = true, removedCount = olderMessages.size)
    }

    /**
     * Estimate token count for a list of messages
     * 估算消息列表的令牌数
     *
     * Uses a simple heuristic: ~4 characters per token for English text.
     * 使用简单启发式：英文文本约 4 个字符/令牌。
     */
    fun estimateTokenCount(messages: List<Message>): Int {
        return messages.sumOf { message ->
            val textLength = when (message) {
                is Message.User -> message.content.sumOf { block ->
                    when (block) {
                        is ContentBlock.Text -> block.text.length
                        is ContentBlock.ToolResult -> block.content.sumOf { rc ->
                            when (rc) {
                                is com.anthropic.claudecode.types.ToolResultContent.Text -> rc.text.length
                                is com.anthropic.claudecode.types.ToolResultContent.Image -> 1000 // Estimated
                            }
                        }
                        else -> 100
                    }
                }
                is Message.Assistant -> message.content.sumOf { block ->
                    when (block) {
                        is ContentBlock.Text -> block.text.length
                        is ContentBlock.ToolUse -> 200 // Estimated
                        else -> 100
                    }
                }
                is Message.System -> message.content.length
                is Message.Progress -> 100
                is Message.Attachment -> 200
                is Message.Tombstone -> 0
            }
            textLength / CHARS_PER_TOKEN
        }
    }

    /**
     * Build a summary of messages for compaction
     * 构建消息摘要用于压缩
     */
    private fun buildSummary(messages: List<Message>): String {
        val sb = StringBuilder()
        var userCount = 0
        var assistantCount = 0
        var toolUseCount = 0

        for (message in messages) {
            when (message) {
                is Message.User -> userCount++
                is Message.Assistant -> {
                    assistantCount++
                    toolUseCount += message.content.count { it is ContentBlock.ToolUse }
                }
                else -> {}
            }
        }

        sb.appendLine("Previous conversation: $userCount user messages, $assistantCount assistant responses")
        if (toolUseCount > 0) {
            sb.appendLine("Tool uses: $toolUseCount tool executions")
        }

        // Include key content from recent-ish messages / 包含较近消息的关键内容
        val importantMessages = messages.filter { msg ->
            when (msg) {
                is Message.User -> msg.content.any { it is ContentBlock.Text }
                is Message.Assistant -> msg.content.any {
                    it is ContentBlock.Text && (it as ContentBlock.Text).text.length > 100
                }
                else -> false
            }
        }.takeLast(5)

        for (msg in importantMessages) {
            when (msg) {
                is Message.User -> {
                    val text = msg.content.filterIsInstance<ContentBlock.Text>()
                        .joinToString(" ") { it.text }
                        .take(200)
                    sb.appendLine("User: $text...")
                }
                is Message.Assistant -> {
                    val text = msg.content.filterIsInstance<ContentBlock.Text>()
                        .joinToString(" ") { it.text }
                        .take(200)
                    sb.appendLine("Assistant: $text...")
                }
                else -> {}
            }
        }

        return sb.toString()
    }

    companion object {
        /** Default max context tokens / 默认最大上下文令牌数 */
        const val DEFAULT_MAX_CONTEXT_TOKENS = 200000

        /** Default compact threshold (80% of max) / 默认压缩阈值（最大值的 80%） */
        const val DEFAULT_COMPACT_THRESHOLD = 0.8

        /** Number of recent turns to keep during compaction / 压缩时保留的最近轮次数 */
        const val KEEP_RECENT_COUNT = 10

        /** Characters per token estimate / 每令牌估算字符数 */
        const val CHARS_PER_TOKEN = 4
    }
}

/**
 * Result of a context compaction operation
 * 上下文压缩操作的结果
 */
data class CompactResult(
    /** Messages after compaction / 压缩后的消息 */
    val messages: List<Message>,
    /** Whether compaction was performed / 是否执行了压缩 */
    val compacted: Boolean,
    /** Number of messages removed / 移除的消息数 */
    val removedCount: Int = 0
)
