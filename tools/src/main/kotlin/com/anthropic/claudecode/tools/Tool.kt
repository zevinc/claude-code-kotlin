package com.anthropic.claudecode.tools

import com.anthropic.claudecode.types.*
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject

/**
 * Tool interface - base contract for all tool implementations
 * Tool 接口 - 所有工具实现的基础契约
 *
 * Maps from TypeScript Tool<Input, Output, P> generic interface.
 * Each tool encapsulates: validation, permission checking, execution, and rendering.
 * 映射自 TypeScript Tool<Input, Output, P> 泛型接口。
 * 每个工具封装：验证、权限检查、执行和渲染。
 *
 * @param Input Tool-specific input type / 工具特定的输入类型
 * @param Output Tool-specific output type / 工具特定的输出类型
 */
interface Tool<Input : Any, Output : Any> {
    /**
     * Unique tool name / 唯一的工具名称
     */
    val name: String

    /**
     * Alternative names for this tool / 此工具的别名
     */
    val aliases: List<String>
        get() = emptyList()

    /**
     * Search hint for tool discovery / 工具发现的搜索提示
     */
    val searchHint: String?
        get() = null

    /**
     * JSON schema defining the expected input structure
     * 定义预期输入结构的 JSON Schema
     */
    val inputJSONSchema: ToolInputJSONSchema

    /**
     * Maximum result size in characters (for truncation)
     * 结果最大字符数（用于截断）
     */
    val maxResultSizeChars: Int
        get() = 30000

    /**
     * Execute the tool with the given input
     * 使用给定输入执行工具
     *
     * @param input Tool-specific input / 工具特定的输入
     * @param context Execution context with state, permissions, etc. / 带有状态、权限等的执行上下文
     * @param canUseTool Function to check if a nested tool can be used / 检查嵌套工具是否可用的函数
     * @param parentMessage The assistant message that requested this tool use / 请求此工具使用的助手消息
     * @param onProgress Callback for progress updates / 进度更新的回调
     * @return Tool execution result / 工具执行结果
     */
    suspend fun call(
        input: Input,
        context: ToolUseContext,
        canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant,
        onProgress: ((ToolProgressData) -> Unit)? = null
    ): ToolResult<Output>

    /**
     * Generate a human-readable description for this tool use
     * 为此工具使用生成人类可读的描述
     *
     * @param input The tool input / 工具输入
     * @param options Description generation options / 描述生成选项
     * @return Description string / 描述字符串
     */
    suspend fun description(input: Input, options: DescriptionOptions): String

    /**
     * Check if this tool use is permitted
     * 检查此工具使用是否被允许
     *
     * @param input The tool input / 工具输入
     * @param context Permission checking context / 权限检查上下文
     * @return Permission result / 权限结果
     */
    suspend fun checkPermissions(input: Input, context: ToolUseContext): PermissionResult

    /**
     * Validate the input before execution
     * 执行前验证输入
     *
     * @param input The tool input / 工具输入
     * @param context Validation context / 验证上下文
     * @return Validation result / 验证结果
     */
    suspend fun validateInput(input: Input, context: ToolUseContext): ValidationResult =
        ValidationResult.Success

    /**
     * Parse raw JSON input into the typed input
     * 将原始 JSON 输入解析为类型化输入
     *
     * @param rawInput Raw JSON input / 原始 JSON 输入
     * @return Parsed input / 解析后的输入
     */
    fun parseInput(rawInput: JsonObject): Input

    /**
     * Get the activity description for spinner display
     * 获取用于加载动画显示的活动描述
     *
     * @param input The tool input / 工具输入
     * @return Activity description (e.g., "Reading src/foo.kt") / 活动描述
     */
    fun getActivityDescription(input: Input): String? = null

    /**
     * Whether this tool is enabled in the current context
     * 此工具在当前上下文中是否启用
     */
    fun isEnabled(): Boolean = true

    /**
     * Whether this tool is safe for concurrent execution
     * 此工具是否可安全并发执行
     */
    fun isConcurrencySafe(input: Input): Boolean = false

    /**
     * Whether this tool is read-only (no side effects)
     * 此工具是否只读（无副作用）
     */
    fun isReadOnly(input: Input): Boolean = false

    /**
     * Whether this tool is destructive (may cause data loss)
     * 此工具是否具有破坏性（可能导致数据丢失）
     */
    fun isDestructive(input: Input): Boolean = false

    /**
     * Whether this tool represents a search or read command (for UI collapsing)
     * 此工具是否表示搜索或读取命令（用于 UI 折叠）
     */
    fun isSearchOrReadCommand(input: Input): Boolean = false

    /**
     * Whether this tool should be deferred (requires ToolSearch first)
     * 此工具是否应延迟（需要先进行 ToolSearch）
     */
    val shouldDefer: Boolean
        get() = false
}

/**
 * Type alias for a list of tools
 * 工具列表的类型别名
 */
typealias Tools = List<Tool<*, *>>

/**
 * Function type for checking if a tool can be used
 * 检查工具是否可用的函数类型
 */
typealias CanUseToolFn = suspend (toolName: String, input: JsonObject) -> PermissionResult

/**
 * Options for generating tool descriptions
 * 生成工具描述的选项
 */
data class DescriptionOptions(
    /** Whether this is a non-interactive session / 是否为非交互式会话 */
    val isNonInteractiveSession: Boolean = false,
    /** Current tool permission context / 当前工具权限上下文 */
    val toolPermissionContext: ToolPermissionContext = emptyToolPermissionContext(),
    /** Available tools / 可用工具 */
    val tools: Tools = emptyList()
)

/**
 * Context for tool execution - carries all state and callbacks needed during execution
 * 工具执行上下文 - 携带执行期间所需的所有状态和回调
 *
 * Maps from TypeScript ToolUseContext.
 * This is the primary dependency injection mechanism for tools.
 * 映射自 TypeScript ToolUseContext。
 * 这是工具的主要依赖注入机制。
 */
data class ToolUseContext(
    /** Execution options / 执行选项 */
    val options: ToolUseOptions,
    /** Job for cancellation support / 用于取消支持的 Job */
    val job: Job,
    /** Function to get current app state / 获取当前应用状态的函数 */
    val getAppState: () -> AppState,
    /** Function to update app state / 更新应用状态的函数 */
    val setAppState: ((AppState) -> AppState) -> Unit,
    /** Current messages in the conversation / 对话中的当前消息 */
    val messages: List<Message>,
    /** File reading limits / 文件读取限制 */
    val fileReadingLimits: FileReadingLimits? = null,
    /** Glob result limits / Glob 结果限制 */
    val globLimits: GlobLimits? = null,
    /** Agent ID for subagents / 子代理的代理 ID */
    val agentId: AgentId? = null,
    /** Agent type / 代理类型 */
    val agentType: String? = null,
    /** Query chain tracking / 查询链跟踪 */
    val queryTracking: QueryChainTracking? = null,
    /** Tool use ID / 工具使用 ID */
    val toolUseId: String? = null
)

/**
 * Options passed to tool execution context
 * 传递给工具执行上下文的选项
 */
data class ToolUseOptions(
    /** Available commands / 可用命令 */
    val commands: List<Any> = emptyList(),
    /** Debug mode / 调试模式 */
    val debug: Boolean = false,
    /** Main loop model name / 主循环模型名称 */
    val mainLoopModel: String = "",
    /** Available tools / 可用工具 */
    val tools: Tools = emptyList(),
    /** Verbose output / 详细输出 */
    val verbose: Boolean = false,
    /** Whether this is a non-interactive session / 是否为非交互式会话 */
    val isNonInteractiveSession: Boolean = false,
    /** Maximum budget in USD / 最大预算（美元） */
    val maxBudgetUsd: Double? = null,
    /** Custom system prompt / 自定义系统提示 */
    val customSystemPrompt: String? = null,
    /** Additional system prompt / 额外系统提示 */
    val appendSystemPrompt: String? = null
)

/**
 * File reading limits for Read tool
 * Read 工具的文件读取限制
 */
data class FileReadingLimits(
    /** Maximum tokens / 最大令牌数 */
    val maxTokens: Int? = null,
    /** Maximum file size in bytes / 最大文件大小（字节） */
    val maxSizeBytes: Long? = null
)

/**
 * Glob result limits
 * Glob 结果限制
 */
data class GlobLimits(
    /** Maximum results / 最大结果数 */
    val maxResults: Int? = null
)

/**
 * Application state (simplified placeholder - will be expanded in state module)
 * 应用状态（简化占位符 - 将在 state 模块中扩展）
 */
data class AppState(
    /** Current settings / 当前设置 */
    val verbose: Boolean = false,
    /** Main model / 主模型 */
    val mainLoopModel: String = "claude-sonnet-4-20250514",
    /** Tool permission context / 工具权限上下文 */
    val toolPermissionContext: ToolPermissionContext = emptyToolPermissionContext(),
    /** API client for subagent spawning / 用于子代理生成的 API 客户端 */
    val apiClient: com.anthropic.claudecode.services.api.AnthropicClient? = null
)
