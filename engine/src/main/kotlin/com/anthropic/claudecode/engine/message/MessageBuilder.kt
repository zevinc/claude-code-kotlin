package com.anthropic.claudecode.engine.message

import com.anthropic.claudecode.services.api.ApiMessage
import com.anthropic.claudecode.services.api.ApiTool
import com.anthropic.claudecode.tools.Tool
import com.anthropic.claudecode.tools.Tools
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject

private val logger = KotlinLogging.logger {}

/**
 * MessageBuilder - builds API request messages from conversation state
 * MessageBuilder - 从对话状态构建 API 请求消息
 *
 * Maps from TypeScript services/claude/messageBuilder.ts.
 * Handles: tool definitions, system prompt assembly,
 * message formatting, and cache control markers.
 * 映射自 TypeScript services/claude/messageBuilder.ts。
 * 处理：工具定义、系统提示组装、消息格式化和缓存控制标记。
 */
class MessageBuilder {

    /**
     * Build the full system prompt from components
     * 从组件构建完整的系统提示
     */
    fun buildSystemPrompt(
        basePrompt: String,
        toolDescriptions: String? = null,
        claudeMdContent: String? = null,
        additionalInstructions: String? = null
    ): String {
        val parts = mutableListOf<String>()
        parts.add(basePrompt)

        toolDescriptions?.let {
            parts.add("# Available Tools\n$it")
        }

        claudeMdContent?.let {
            parts.add("# Project Instructions\n$it")
        }

        additionalInstructions?.let {
            parts.add(it)
        }

        return parts.joinToString("\n\n")
    }

    /**
     * Build tool definitions for the API request
     * 为 API 请求构建工具定义
     */
    fun buildToolDefinitions(tools: Tools): List<ApiTool> {
        return tools
            .filter { it.isEnabled() }
            .map { tool ->
                ApiTool(
                    name = tool.name,
                    description = tool.inputJSONSchema.description ?: "",
                    inputSchema = tool.inputJSONSchema.schema
                )
            }
    }

    /**
     * Build a user message for tool results
     * 为工具结果构建用户消息
     */
    fun buildToolResultMessage(
        results: List<ToolResultEntry>
    ): ApiMessage {
        val content = results.joinToString("\n\n") { entry ->
            val statusIcon = if (entry.isError) "[ERROR]" else "[OK]"
            "$statusIcon ${entry.toolName}: ${entry.output.take(MAX_RESULT_LENGTH)}"
        }
        return ApiMessage(role = "user", content = content)
    }

    /**
     * Truncate messages to fit within token budget
     * 截断消息以适应令牌预算
     */
    fun truncateMessages(
        messages: List<ApiMessage>,
        maxTokensBudget: Int,
        estimateTokens: (String) -> Int
    ): List<ApiMessage> {
        if (messages.isEmpty()) return messages

        var totalTokens = 0
        val result = mutableListOf<ApiMessage>()

        // Always keep the first message (system context) and last few
        // 始终保留第一条消息（系统上下文）和最后几条
        val keepFirst = messages.take(1)
        val keepLast = messages.takeLast(KEEP_LAST_MESSAGES.coerceAtMost(messages.size - 1))
        val middle = messages.drop(1).dropLast(KEEP_LAST_MESSAGES.coerceAtMost(messages.size - 1))

        for (msg in keepFirst) {
            totalTokens += estimateTokens(msg.content)
            result.add(msg)
        }

        // Add middle messages from most recent, as budget allows
        // 从最近的开始添加中间消息，只要预算允许
        val middleReversed = middle.reversed()
        val includedMiddle = mutableListOf<ApiMessage>()

        for (msg in middleReversed) {
            val tokens = estimateTokens(msg.content)
            if (totalTokens + tokens < maxTokensBudget) {
                totalTokens += tokens
                includedMiddle.add(0, msg)
            } else {
                break
            }
        }

        result.addAll(includedMiddle)

        for (msg in keepLast) {
            totalTokens += estimateTokens(msg.content)
            result.add(msg)
        }

        if (result.size < messages.size) {
            logger.info { "Truncated ${messages.size - result.size} messages to fit token budget" }
        }

        return result
    }

    companion object {
        const val MAX_RESULT_LENGTH = 20_000
        const val KEEP_LAST_MESSAGES = 4
    }
}

data class ToolResultEntry(
    val toolName: String,
    val output: String,
    val isError: Boolean = false
)
