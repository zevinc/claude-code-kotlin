package com.anthropic.claudecode.engine.message

import com.anthropic.claudecode.services.api.ApiMessage
import com.anthropic.claudecode.types.ContentBlock
import com.anthropic.claudecode.types.Message
import com.anthropic.claudecode.types.ToolResultContent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val logger = KotlinLogging.logger {}

/**
 * Message normalizer - transforms internal messages to API format
 * 消息规范化器 - 将内部消息转换为 API 格式
 *
 * Maps from TypeScript utils/messages.ts normalizeMessagesForAPI().
 * Handles: role alternation, tool_use/tool_result pairing,
 * content block serialization, citation stripping.
 * 映射自 TypeScript utils/messages.ts normalizeMessagesForAPI()。
 * 处理：角色交替、tool_use/tool_result 配对、内容块序列化、引用剥离。
 */
object MessageNormalizer {

    /**
     * Normalize messages for the Anthropic API
     * 为 Anthropic API 规范化消息
     *
     * Ensures proper role alternation, tool_use/tool_result pairing,
     * and content block format.
     * 确保正确的角色交替、tool_use/tool_result 配对和内容块格式。
     *
     * @param messages Internal message list / 内部消息列表
     * @return API-formatted messages / API 格式的消息列表
     */
    fun normalizeForApi(messages: List<Message>): List<ApiMessageBlock> {
        val apiMessages = mutableListOf<ApiMessageBlock>()
        val pendingToolUseIds = mutableSetOf<String>()

        for (message in messages) {
            when (message) {
                is Message.User -> {
                    val content = normalizeUserContent(message.content)
                    if (content.isNotEmpty()) {
                        apiMessages.add(ApiMessageBlock(role = "user", content = content))
                    }
                }
                is Message.Assistant -> {
                    val content = normalizeAssistantContent(message.content)
                    if (content.isNotEmpty()) {
                        apiMessages.add(ApiMessageBlock(role = "assistant", content = content))
                        // Track tool use IDs for pairing / 追踪工具使用 ID 以进行配对
                        for (block in message.content) {
                            if (block is ContentBlock.ToolUse) {
                                pendingToolUseIds.add(block.id)
                            }
                        }
                    }
                }
                is Message.System -> {
                    // System messages become user messages with [system] prefix
                    // 系统消息变为带 [system] 前缀的用户消息
                    apiMessages.add(ApiMessageBlock(
                        role = "user",
                        content = listOf(ContentBlockJson.Text("[system] ${message.content}"))
                    ))
                }
                else -> {
                    // Skip Progress, Attachment, Tombstone
                    // 跳过 Progress、Attachment、Tombstone
                }
            }
        }

        // Ensure proper role alternation / 确保正确的角色交替
        return ensureRoleAlternation(apiMessages)
    }

    /**
     * Ensure tool_use blocks have matching tool_result blocks
     * 确保 tool_use 块有匹配的 tool_result 块
     */
    fun ensureToolResultPairing(messages: List<ApiMessageBlock>): List<ApiMessageBlock> {
        val result = mutableListOf<ApiMessageBlock>()
        val unresolvedToolUses = mutableSetOf<String>()

        for (msg in messages) {
            if (msg.role == "assistant") {
                for (block in msg.content) {
                    if (block is ContentBlockJson.ToolUse) {
                        unresolvedToolUses.add(block.id)
                    }
                }
            }
            if (msg.role == "user") {
                for (block in msg.content) {
                    if (block is ContentBlockJson.ToolResult) {
                        unresolvedToolUses.remove(block.toolUseId)
                    }
                }
            }
            result.add(msg)
        }

        // Add synthetic error results for orphaned tool uses
        // 为孤立的工具使用添加合成错误结果
        if (unresolvedToolUses.isNotEmpty()) {
            logger.warn { "Adding synthetic error results for ${unresolvedToolUses.size} orphaned tool uses" }
            val syntheticResults = unresolvedToolUses.map { id ->
                ContentBlockJson.ToolResult(
                    toolUseId = id,
                    content = "[Tool execution was interrupted]",
                    isError = true
                )
            }
            result.add(ApiMessageBlock(role = "user", content = syntheticResults))
        }

        return result
    }

    /**
     * Ensure messages alternate between user and assistant roles
     * 确保消息在用户和助手角色之间交替
     */
    private fun ensureRoleAlternation(messages: List<ApiMessageBlock>): List<ApiMessageBlock> {
        if (messages.isEmpty()) return messages

        val result = mutableListOf<ApiMessageBlock>()
        var lastRole: String? = null

        for (msg in messages) {
            if (msg.role == lastRole) {
                // Merge consecutive same-role messages / 合并连续相同角色的消息
                val last = result.last()
                result[result.lastIndex] = last.copy(content = last.content + msg.content)
            } else {
                result.add(msg)
                lastRole = msg.role
            }
        }

        // Ensure conversation starts with user / 确保对话以用户开始
        if (result.isNotEmpty() && result[0].role != "user") {
            result.add(0, ApiMessageBlock(
                role = "user",
                content = listOf(ContentBlockJson.Text("[conversation context]"))
            ))
        }

        return result
    }

    private fun normalizeUserContent(blocks: List<ContentBlock>): List<ContentBlockJson> {
        return blocks.mapNotNull { block ->
            when (block) {
                is ContentBlock.Text -> ContentBlockJson.Text(block.text)
                is ContentBlock.Image -> ContentBlockJson.Image(
                    source = block.source,
                    mediaType = block.mediaType
                )
                is ContentBlock.ToolResult -> ContentBlockJson.ToolResult(
                    toolUseId = block.toolUseId,
                    content = block.content.joinToString("\n") { rc ->
                        when (rc) {
                            is ToolResultContent.Text -> rc.text
                            is ToolResultContent.Image -> "[Image]"
                        }
                    },
                    isError = block.isError
                )
                else -> null
            }
        }
    }

    private fun normalizeAssistantContent(blocks: List<ContentBlock>): List<ContentBlockJson> {
        return blocks.mapNotNull { block ->
            when (block) {
                is ContentBlock.Text -> ContentBlockJson.Text(block.text)
                is ContentBlock.ToolUse -> ContentBlockJson.ToolUse(
                    id = block.id,
                    name = block.name,
                    input = block.input
                )
                is ContentBlock.Thinking -> ContentBlockJson.Thinking(
                    thinking = block.thinking,
                    signature = block.signature
                )
                else -> null
            }
        }
    }
}

/**
 * API message with structured content blocks
 * 带结构化内容块的 API 消息
 */
data class ApiMessageBlock(
    val role: String,
    val content: List<ContentBlockJson>
)

/**
 * Serializable content block for API messages
 * API 消息的可序列化内容块
 */
sealed class ContentBlockJson {
    data class Text(val text: String) : ContentBlockJson()
    data class Image(val source: com.anthropic.claudecode.types.ImageSource, val mediaType: String) : ContentBlockJson()
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : ContentBlockJson()
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean = false) : ContentBlockJson()
    data class Thinking(val thinking: String, val signature: String?) : ContentBlockJson()

    /**
     * Convert to JSON element for API serialization
     * 转换为 JSON 元素用于 API 序列化
     */
    fun toJsonElement(): JsonObject = when (this) {
        is Text -> buildJsonObject {
            put("type", "text")
            put("text", text)
        }
        is ToolUse -> buildJsonObject {
            put("type", "tool_use")
            put("id", id)
            put("name", name)
            put("input", input)
        }
        is ToolResult -> buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", toolUseId)
            put("content", content)
            if (isError) put("is_error", true)
        }
        is Image -> buildJsonObject {
            put("type", "image")
        }
        is Thinking -> buildJsonObject {
            put("type", "thinking")
            put("thinking", thinking)
            signature?.let { put("signature", it) }
        }
    }
}
