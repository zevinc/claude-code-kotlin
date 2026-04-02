package com.anthropic.claudecode.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Message sealed class hierarchy - represents all message types in the system
 * Message 密封类层次结构 - 表示系统中的所有消息类型
 *
 * Maps from TypeScript discriminated unions to Kotlin sealed classes.
 * 将 TypeScript 判别联合映射到 Kotlin 密封类。
 */
@Serializable
sealed class Message {
    /**
     * Unique identifier for this message
     * 此消息的唯一标识符
     */
    abstract val uuid: String

    /**
     * Timestamp when the message was created (ISO 8601)
     * 消息创建时的时间戳（ISO 8601 格式）
     */
    abstract val timestamp: String

    /**
     * User message - input from the user
     * 用户消息 - 来自用户的输入
     */
    @Serializable
    @SerialName("user")
    data class User(
        override val uuid: String,
        override val timestamp: String,
        /** Message content blocks / 消息内容块 */
        val content: List<ContentBlock>,
        /** Index of pasted image (if any) / 粘贴图片的索引（如果有） */
        val imageIndex: Int? = null,
        /** IDs of pasted images / 粘贴图片的 ID 列表 */
        val imagePasteIds: List<String>? = null,
        /** Whether this is a compact summary / 是否为压缩摘要 */
        val isCompactSummary: Boolean = false,
        /** Plan content (if in plan mode) / 计划内容（如果在计划模式中） */
        val planContent: String? = null
    ) : Message()

    /**
     * Assistant message - response from Claude
     * 助手消息 - 来自 Claude 的响应
     */
    @Serializable
    @SerialName("assistant")
    data class Assistant(
        override val uuid: String,
        override val timestamp: String,
        /** Response content blocks / 响应内容块 */
        val content: List<ContentBlock>,
        /** Model used for this response / 用于此响应的模型 */
        val model: String? = null,
        /** Reason the response stopped / 响应停止的原因 */
        val stopReason: StopReason? = null,
        /** Cost in USD for this response / 此响应的 USD 成本 */
        val costUsd: Double? = null
    ) : Message()

    /**
     * System message - system notifications and errors
     * 系统消息 - 系统通知和错误
     */
    @Serializable
    @SerialName("system")
    data class System(
        override val uuid: String,
        override val timestamp: String,
        /** Message subtype / 消息子类型 */
        val subtype: String = "",
        /** Text content / 文本内容 */
        val content: String,
        /** Whether this is a meta message / 是否为元消息 */
        val isMeta: Boolean = false
    ) : Message()

    /**
     * Progress message - real-time updates during tool execution
     * 进度消息 - 工具执行过程中的实时更新
     */
    @Serializable
    @SerialName("progress")
    data class Progress(
        override val uuid: String,
        override val timestamp: String,
        /** Associated tool use ID / 关联的工具使用 ID */
        val toolUseId: String,
        /** Progress data / 进度数据 */
        val data: ProgressData? = null
    ) : Message()

    /**
     * Attachment message - file or image attachments
     * 附件消息 - 文件或图片附件
     */
    @Serializable
    @SerialName("attachment")
    data class Attachment(
        override val uuid: String,
        override val timestamp: String,
        /** Attachment content / 附件内容 */
        val attachment: JsonObject? = null
    ) : Message()

    /**
     * Tombstone message - marker for removed content
     * 墓碑消息 - 已移除内容的标记
     */
    @Serializable
    @SerialName("tombstone")
    data class Tombstone(
        override val uuid: String,
        override val timestamp: String,
        /** Reason for removal / 移除原因 */
        val reason: String? = null
    ) : Message()
}

/**
 * Content block types within messages
 * 消息内的内容块类型
 *
 * Maps from TypeScript ContentBlock union type.
 * 映射自 TypeScript ContentBlock 联合类型。
 */
@Serializable
sealed class ContentBlock {
    /**
     * Plain text content / 纯文本内容
     */
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String
    ) : ContentBlock()

    /**
     * Image content / 图片内容
     */
    @Serializable
    @SerialName("image")
    data class Image(
        /** Image source (base64 or URL) / 图片来源（base64 或 URL） */
        val source: ImageSource,
        /** Media type (e.g., "image/png") / 媒体类型（例如 "image/png"） */
        val mediaType: String
    ) : ContentBlock()

    /**
     * Tool use request from the assistant
     * 来自助手的工具使用请求
     */
    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        /** Unique ID for this tool use / 此工具使用的唯一 ID */
        val id: String,
        /** Tool name / 工具名称 */
        val name: String,
        /** Tool input as JSON / 工具输入（JSON 格式） */
        val input: JsonObject
    ) : ContentBlock()

    /**
     * Tool result returned to the assistant
     * 返回给助手的工具结果
     */
    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        /** ID of the tool use this result corresponds to / 此结果对应的工具使用 ID */
        val toolUseId: String,
        /** Result content / 结果内容 */
        val content: List<ToolResultContent>,
        /** Whether the tool execution resulted in an error / 工具执行是否产生了错误 */
        val isError: Boolean = false
    ) : ContentBlock()

    /**
     * Thinking block (extended thinking) / 思考块（扩展思考）
     */
    @Serializable
    @SerialName("thinking")
    data class Thinking(
        val thinking: String,
        val signature: String? = null
    ) : ContentBlock()
}

/**
 * Image source types / 图片来源类型
 */
@Serializable
sealed class ImageSource {
    /**
     * Base64-encoded image data / Base64 编码的图片数据
     */
    @Serializable
    @SerialName("base64")
    data class Base64(
        val data: String,
        val mediaType: String
    ) : ImageSource()

    /**
     * URL reference to an image / 图片的 URL 引用
     */
    @Serializable
    @SerialName("url")
    data class Url(
        val url: String
    ) : ImageSource()
}

/**
 * Tool result content types / 工具结果内容类型
 */
@Serializable
sealed class ToolResultContent {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ToolResultContent()

    @Serializable
    @SerialName("image")
    data class Image(val source: ImageSource, val mediaType: String) : ToolResultContent()
}

/**
 * Stop reason for assistant responses
 * 助手响应的停止原因
 */
@Serializable
enum class StopReason {
    /** Natural end of response / 响应自然结束 */
    @SerialName("end_turn")
    END_TURN,

    /** Maximum tokens reached / 达到最大令牌数 */
    @SerialName("max_tokens")
    MAX_TOKENS,

    /** Tool use requested / 请求使用工具 */
    @SerialName("tool_use")
    TOOL_USE,

    /** Response was interrupted / 响应被中断 */
    @SerialName("stop_sequence")
    STOP_SEQUENCE
}

/**
 * Progress data for tool execution updates
 * 工具执行更新的进度数据
 */
@Serializable
sealed class ProgressData {
    /**
     * Bash tool progress / Bash 工具进度
     */
    @Serializable
    @SerialName("bash")
    data class Bash(
        val output: String? = null,
        val exitCode: Int? = null,
        val isStderr: Boolean = false
    ) : ProgressData()

    /**
     * Web search progress / 网页搜索进度
     */
    @Serializable
    @SerialName("web_search")
    data class WebSearch(
        val status: String? = null,
        val resultsCount: Int? = null
    ) : ProgressData()

    /**
     * Agent tool progress / 代理工具进度
     */
    @Serializable
    @SerialName("agent")
    data class Agent(
        val agentId: String? = null,
        val status: String? = null
    ) : ProgressData()

    /**
     * MCP tool progress / MCP 工具进度
     */
    @Serializable
    @SerialName("mcp")
    data class Mcp(
        val serverName: String? = null,
        val status: String? = null
    ) : ProgressData()

    /**
     * Generic progress / 通用进度
     */
    @Serializable
    @SerialName("generic")
    data class Generic(
        val message: String? = null,
        val percentage: Double? = null
    ) : ProgressData()
}

/**
 * Message source - indicates where a message originated
 * 消息来源 - 指示消息的来源
 */
@Serializable
enum class MessageSource {
    @SerialName("user")
    USER,

    @SerialName("teammate")
    TEAMMATE,

    @SerialName("system")
    SYSTEM,

    @SerialName("tick")
    TICK,

    @SerialName("task")
    TASK
}

/**
 * Role in conversation / 对话中的角色
 */
@Serializable
enum class Role {
    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT
}
