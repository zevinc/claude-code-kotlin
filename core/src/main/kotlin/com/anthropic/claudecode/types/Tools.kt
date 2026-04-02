package com.anthropic.claudecode.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Validation result - outcome of input validation
 * 验证结果 - 输入验证的结果
 */
@Serializable
sealed class ValidationResult {
    /**
     * Validation passed / 验证通过
     */
    @Serializable
    @SerialName("success")
    data object Success : ValidationResult()

    /**
     * Validation failed with error details
     * 验证失败，带有错误详情
     */
    @Serializable
    @SerialName("failure")
    data class Failure(
        /** Error message / 错误消息 */
        val message: String,
        /** Error code / 错误代码 */
        val errorCode: Int
    ) : ValidationResult()
}

/**
 * Tool result - outcome of a tool execution
 * 工具结果 - 工具执行的结果
 */
@Serializable
data class ToolResult<out T>(
    /** Result data / 结果数据 */
    val data: T? = null,
    /** Primary output (alias for data, used by tool impls) / 主要输出（data 别名，工具实现使用） */
    val output: @UnsafeVariance T? = data,
    /** Output content blocks / 输出内容块 */
    val outputContent: List<ToolResultContent> = emptyList(),
    /** Whether the execution was an error / 执行是否出错 */
    val isError: Boolean = false,
    /** Error message if any / 错误消息（如有） */
    val errorMessage: String? = null
)

/**
 * Tool input JSON schema - defines the expected input structure
 * 工具输入 JSON Schema - 定义预期的输入结构
 */
@Serializable
data class ToolInputJSONSchema(
    /** Schema type (always "object") / Schema 类型（始终为 "object"） */
    val type: String = "object",
    /** Property definitions / 属性定义 */
    val properties: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    /** Required property names / 必需的属性名称 */
    val required: List<String>? = null,
    /** Additional properties allowed / 是否允许额外属性 */
    val additionalProperties: Boolean? = null,
    /** Human-readable tool description / 人类可读的工具描述 */
    val description: String? = null,
    /** Full JSON schema as JsonObject (for API serialization) / 完整 JSON Schema（用于 API 序列化） */
    val schema: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())
)

/**
 * Tool progress data types - specific progress indicators for different tools
 * 工具进度数据类型 - 不同工具的特定进度指示器
 */
@Serializable
sealed class ToolProgressData {
    /**
     * Bash tool execution progress / Bash 工具执行进度
     */
    @Serializable
    @SerialName("bash")
    data class BashProgress(
        val stdout: String? = null,
        val stderr: String? = null,
        val exitCode: Int? = null,
        val isInterruptible: Boolean = false,
        val status: String? = null
    ) : ToolProgressData()

    /**
     * Web search progress / 网页搜索进度
     */
    @Serializable
    @SerialName("web_search")
    data class WebSearchProgress(
        val status: String? = null,
        val resultsCount: Int? = null,
        val query: String? = null
    ) : ToolProgressData()

    /**
     * Agent tool progress / 代理工具进度
     */
    @Serializable
    @SerialName("agent")
    data class AgentToolProgress(
        val agentId: String? = null,
        val status: String? = null,
        val output: String? = null
    ) : ToolProgressData()

    /**
     * MCP tool progress / MCP 工具进度
     */
    @Serializable
    @SerialName("mcp")
    data class MCPProgress(
        val serverName: String? = null,
        val status: String? = null,
        val percentage: Double? = null
    ) : ToolProgressData()

    /**
     * Skill tool progress / 技能工具进度
     */
    @Serializable
    @SerialName("skill")
    data class SkillToolProgress(
        val skillName: String? = null,
        val status: String? = null
    ) : ToolProgressData()

    /**
     * Task output progress / 任务输出进度
     */
    @Serializable
    @SerialName("task_output")
    data class TaskOutputProgress(
        val taskId: String? = null,
        val output: String? = null
    ) : ToolProgressData()

    /**
     * REPL tool progress / REPL 工具进度
     */
    @Serializable
    @SerialName("repl")
    data class REPLToolProgress(
        val output: String? = null,
        val isError: Boolean = false
    ) : ToolProgressData()
}

/**
 * Compact progress event types
 * 压缩进度事件类型
 */
@Serializable
sealed class CompactProgressEvent {
    /**
     * Hooks execution started / 钩子执行开始
     */
    @Serializable
    @SerialName("hooks_start")
    data class HooksStart(
        val hookType: HookType
    ) : CompactProgressEvent()

    /**
     * Compaction started / 压缩开始
     */
    @Serializable
    @SerialName("compact_start")
    data object CompactStart : CompactProgressEvent()

    /**
     * Compaction ended / 压缩结束
     */
    @Serializable
    @SerialName("compact_end")
    data object CompactEnd : CompactProgressEvent()
}

/**
 * Hook types for compact progress events
 * 压缩进度事件的钩子类型
 */
@Serializable
enum class HookType {
    @SerialName("pre_compact")
    PRE_COMPACT,

    @SerialName("post_compact")
    POST_COMPACT,

    @SerialName("session_start")
    SESSION_START
}

/**
 * Query chain tracking for nested queries
 * 嵌套查询的查询链跟踪
 */
@Serializable
data class QueryChainTracking(
    /** Chain identifier / 链标识符 */
    val chainId: String,
    /** Nesting depth / 嵌套深度 */
    val depth: Int
)
