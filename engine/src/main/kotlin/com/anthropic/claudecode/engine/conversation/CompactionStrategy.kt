package com.anthropic.claudecode.engine.conversation

import com.anthropic.claudecode.services.api.*
import com.anthropic.claudecode.utils.tokens.TokenCounter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

/**
 * CompactionStrategy - defines how conversation history is compacted
 * CompactionStrategy - 定义对话历史如何被压缩
 *
 * Maps from TypeScript services/conversation/compaction.ts.
 * Supports multiple strategies for reducing context window usage
 * while preserving important conversation context.
 * 映射自 TypeScript services/conversation/compaction.ts。
 * 支持多种策略来减少上下文窗口使用量，
 * 同时保留重要的对话上下文。
 */
sealed class CompactionStrategy {

    /**
     * Full compaction: summarize all old messages into a single summary
     * 完整压缩：将所有旧消息总结为一个摘要
     */
    data class Full(val instruction: String? = null) : CompactionStrategy()

    /**
     * Partial compaction: summarize messages up to a specific index
     * 部分压缩：总结到特定索引的消息
     */
    data class Partial(val upToIndex: Int, val instruction: String? = null) : CompactionStrategy()

    /**
     * Micro compaction: reduce token usage by trimming large tool outputs
     * 微压缩：通过修剪大型工具输出来减少令牌使用
     */
    data object Micro : CompactionStrategy()

    /**
     * Rolling compaction: keep sliding window of recent messages
     * 滚动压缩：保持最近消息的滑动窗口
     */
    data class Rolling(val keepRecentCount: Int = 10) : CompactionStrategy()
}

/**
 * CompactionEngine - executes compaction strategies
 * CompactionEngine - 执行压缩策略
 */
class CompactionEngine(
    private val apiClient: AnthropicClient,
    private val model: String = "claude-sonnet-4-20250514",
    private val tokenCounter: TokenCounter = TokenCounter()
) {
    /**
     * Execute compaction on message history
     * 对消息历史执行压缩
     *
     * @param messages Current message history / 当前消息历史
     * @param strategy Compaction strategy to use / 要使用的压缩策略
     * @param systemPrompt Current system prompt / 当前系统提示
     * @return Compacted message list / 压缩后的消息列表
     */
    suspend fun compact(
        messages: List<ApiMessage>,
        strategy: CompactionStrategy,
        systemPrompt: String? = null
    ): CompactionResult {
        logger.info { "Compacting ${messages.size} messages with strategy: ${strategy::class.simpleName}" }

        val before = messages.sumOf { tokenCounter.estimateTokens(it.content.take(500)).toInt() }

        val compacted = when (strategy) {
            is CompactionStrategy.Full -> compactFull(messages, strategy.instruction)
            is CompactionStrategy.Partial -> compactPartial(messages, strategy.upToIndex, strategy.instruction)
            is CompactionStrategy.Micro -> compactMicro(messages)
            is CompactionStrategy.Rolling -> compactRolling(messages, strategy.keepRecentCount)
        }

        val after = compacted.sumOf { tokenCounter.estimateTokens(it.content.take(500)).toInt() }

        logger.info { "Compaction: $before -> $after tokens (${messages.size} -> ${compacted.size} messages)" }

        return CompactionResult(
            messages = compacted,
            originalCount = messages.size,
            compactedCount = compacted.size,
            tokensBefore = before.toLong(),
            tokensAfter = after.toLong(),
            strategy = strategy::class.simpleName ?: "unknown"
        )
    }

    /**
     * Full compaction: summarize everything except recent messages
     * 完整压缩：总结除最近消息外的所有内容
     */
    private suspend fun compactFull(messages: List<ApiMessage>, instruction: String?): List<ApiMessage> {
        if (messages.size <= KEEP_RECENT) return messages

        val toSummarize = messages.take(messages.size - KEEP_RECENT)
        val toKeep = messages.takeLast(KEEP_RECENT)

        val summary = generateSummary(toSummarize, instruction)

        val result = mutableListOf<ApiMessage>()
        if (summary.isNotBlank()) {
            result.add(ApiMessage(role = "user", content = "[Previous Context Summary]\n$summary"))
            result.add(ApiMessage(role = "assistant", content = "I understand the context from our previous conversation. How can I continue helping you?"))
        }
        result.addAll(toKeep)
        return result
    }

    /**
     * Partial compaction: summarize messages up to a specific index
     * 部分压缩：总结到特定索引的消息
     */
    private suspend fun compactPartial(
        messages: List<ApiMessage>,
        upToIndex: Int,
        instruction: String?
    ): List<ApiMessage> {
        val effectiveIndex = upToIndex.coerceIn(0, messages.size)
        if (effectiveIndex <= 1) return messages

        val toSummarize = messages.take(effectiveIndex)
        val toKeep = messages.drop(effectiveIndex)

        val summary = generateSummary(toSummarize, instruction)

        val result = mutableListOf<ApiMessage>()
        if (summary.isNotBlank()) {
            result.add(ApiMessage(role = "user", content = "[Context Summary (messages 1-$effectiveIndex)]\n$summary"))
            result.add(ApiMessage(role = "assistant", content = "Understood. I have context from our earlier conversation."))
        }
        result.addAll(toKeep)
        return result
    }

    /**
     * Micro compaction: trim large tool outputs without summarization
     * 微压缩：修剪大型工具输出，不进行总结
     */
    private fun compactMicro(messages: List<ApiMessage>): List<ApiMessage> {
        return messages.map { msg ->
            val content = msg.content
            if (content.length > MICRO_TRIM_THRESHOLD) {
                // Trim large content, keeping head and tail
                // 修剪大型内容，保留头部和尾部
                val head = content.take(MICRO_KEEP_HEAD)
                val tail = content.takeLast(MICRO_KEEP_TAIL)
                val trimmedSize = content.length - head.length - tail.length
                msg.copy(content = "$head\n\n[... $trimmedSize characters trimmed ...]\n\n$tail")
            } else {
                msg
            }
        }
    }

    /**
     * Rolling compaction: keep only the most recent N messages
     * 滚动压缩：只保留最近的 N 条消息
     */
    private suspend fun compactRolling(messages: List<ApiMessage>, keepCount: Int): List<ApiMessage> {
        if (messages.size <= keepCount) return messages

        val toSummarize = messages.take(messages.size - keepCount)
        val toKeep = messages.takeLast(keepCount)

        val summary = generateSummary(toSummarize, null)

        val result = mutableListOf<ApiMessage>()
        if (summary.isNotBlank()) {
            result.add(ApiMessage(role = "user", content = "[Rolling Context]\n$summary"))
            result.add(ApiMessage(role = "assistant", content = "Context retained. Continuing."))
        }
        result.addAll(toKeep)
        return result
    }

    /**
     * Generate a summary of messages via API call
     * 通过 API 调用生成消息摘要
     */
    private suspend fun generateSummary(messages: List<ApiMessage>, instruction: String?): String {
        val contextText = messages.joinToString("\n") { msg ->
            val content = if (msg.content.length > 500) msg.content.take(500) + "..." else msg.content
            "${msg.role}: $content"
        }

        val summaryPrompt = instruction
            ?: "Summarize the following conversation context concisely, preserving key decisions, file paths, code changes, and important technical details. Focus on what's needed to continue the work."

        val request = MessageRequest(
            model = model,
            messages = listOf(
                ApiMessage(role = "user", content = "$summaryPrompt\n\n---\n$contextText")
            ),
            stream = false,
            maxTokens = 2000
        )

        return try {
            val response = apiClient.sendMessage(request)
            response.content.firstOrNull()?.let { block ->
                block["text"]?.toString()?.removeSurrounding("\"") ?: ""
            } ?: ""
        } catch (e: Exception) {
            logger.error(e) { "Summary generation failed" }
            ""
        }
    }

    companion object {
        const val KEEP_RECENT = 6
        const val MICRO_TRIM_THRESHOLD = 3000
        const val MICRO_KEEP_HEAD = 1000
        const val MICRO_KEEP_TAIL = 500
    }
}

@Serializable
data class CompactionResult(
    val messages: List<ApiMessage>,
    val originalCount: Int,
    val compactedCount: Int,
    val tokensBefore: Long,
    val tokensAfter: Long,
    val strategy: String
)
