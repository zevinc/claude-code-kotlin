package com.anthropic.claudecode.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Prompt request for user elicitation
 * 用户提示请求
 */
@Serializable
data class PromptRequest(
    /** Request identifier / 请求标识符 */
    val prompt: String,
    /** Message to display to user / 显示给用户的消息 */
    val message: String,
    /** Available options / 可用选项 */
    val options: List<PromptOption>
)

/**
 * Individual option in a prompt request
 * 提示请求中的单个选项
 */
@Serializable
data class PromptOption(
    /** Option key / 选项键 */
    val key: String,
    /** Display label / 显示标签 */
    val label: String,
    /** Optional description / 可选描述 */
    val description: String? = null
)

/**
 * Response to a prompt request
 * 对提示请求的响应
 */
@Serializable
data class PromptResponse(
    /** Request identifier (matches PromptRequest.prompt) / 请求标识符（匹配 PromptRequest.prompt） */
    @SerialName("prompt_response")
    val promptResponse: String,
    /** Selected option key / 选择的选项键 */
    val selected: String
)

/**
 * Hook event types - all possible hook points in the system
 * 钩子事件类型 - 系统中所有可能的钩子点
 */
@Serializable
enum class HookEvent {
    @SerialName("PreToolUse")
    PRE_TOOL_USE,

    @SerialName("PostToolUse")
    POST_TOOL_USE,

    @SerialName("PostToolUseFailure")
    POST_TOOL_USE_FAILURE,

    @SerialName("UserPromptSubmit")
    USER_PROMPT_SUBMIT,

    @SerialName("SessionStart")
    SESSION_START,

    @SerialName("Setup")
    SETUP,

    @SerialName("SubagentStart")
    SUBAGENT_START,

    @SerialName("PermissionDenied")
    PERMISSION_DENIED,

    @SerialName("Notification")
    NOTIFICATION,

    @SerialName("PermissionRequest")
    PERMISSION_REQUEST,

    @SerialName("Elicitation")
    ELICITATION,

    @SerialName("ElicitationResult")
    ELICITATION_RESULT,

    @SerialName("CwdChanged")
    CWD_CHANGED,

    @SerialName("FileChanged")
    FILE_CHANGED,

    @SerialName("WorktreeCreate")
    WORKTREE_CREATE
}

/**
 * Hook progress - progress update from a hook execution
 * 钩子进度 - 来自钩子执行的进度更新
 */
@Serializable
data class HookProgress(
    /** Always "hook_progress" / 始终为 "hook_progress" */
    val type: String = "hook_progress",
    /** Which hook event / 哪个钩子事件 */
    val hookEvent: HookEvent,
    /** Hook name / 钩子名称 */
    val hookName: String,
    /** Command being hooked / 被钩住的命令 */
    val command: String,
    /** Optional prompt text / 可选提示文本 */
    val promptText: String? = null,
    /** Status message / 状态消息 */
    val statusMessage: String? = null
)

/**
 * Hook blocking error - when a hook blocks execution
 * 钩子阻塞错误 - 当钩子阻止执行时
 */
@Serializable
data class HookBlockingError(
    /** Error message / 错误消息 */
    val blockingError: String,
    /** Command that was blocked / 被阻止的命令 */
    val command: String
)

/**
 * Hook result - outcome of hook execution
 * 钩子结果 - 钩子执行的结果
 */
@Serializable
data class HookResult(
    /** Optional message to add to conversation / 可选的添加到对话的消息 */
    val message: Message? = null,
    /** Optional system message / 可选的系统消息 */
    val systemMessage: Message? = null,
    /** Blocking error details / 阻塞错误详情 */
    val blockingError: HookBlockingError? = null,
    /** Outcome of the hook execution / 钩子执行的结果 */
    val outcome: HookOutcome = HookOutcome.SUCCESS,
    /** Whether to prevent continuation / 是否阻止继续 */
    val preventContinuation: Boolean = false,
    /** Reason for stopping / 停止原因 */
    val stopReason: String? = null,
    /** Permission behavior override / 权限行为覆盖 */
    val permissionBehavior: PermissionBehavior? = null,
    /** Permission decision reason from hook / 来自钩子的权限决策原因 */
    val hookPermissionDecisionReason: String? = null,
    /** Additional context to inject / 要注入的额外上下文 */
    val additionalContext: String? = null,
    /** Initial user message override / 初始用户消息覆盖 */
    val initialUserMessage: String? = null,
    /** Updated tool input / 更新后的工具输入 */
    val updatedInput: JsonObject? = null,
    /** Updated MCP tool output / 更新后的 MCP 工具输出 */
    val updatedMCPToolOutput: JsonObject? = null,
    /** Whether to retry the operation / 是否重试操作 */
    val retry: Boolean = false
)

/**
 * Hook execution outcome / 钩子执行结果
 */
@Serializable
enum class HookOutcome {
    @SerialName("success")
    SUCCESS,

    @SerialName("blocking")
    BLOCKING,

    @SerialName("non_blocking_error")
    NON_BLOCKING_ERROR,

    @SerialName("cancelled")
    CANCELLED
}

/**
 * Aggregated hook result - combined results from multiple hooks
 * 聚合钩子结果 - 来自多个钩子的组合结果
 */
@Serializable
data class AggregatedHookResult(
    /** Optional message to add to conversation / 可选的添加到对话的消息 */
    val message: Message? = null,
    /** Collected blocking errors / 收集的阻塞错误 */
    val blockingErrors: List<HookBlockingError>? = null,
    /** Whether to prevent continuation / 是否阻止继续 */
    val preventContinuation: Boolean = false,
    /** Reason for stopping / 停止原因 */
    val stopReason: String? = null,
    /** Permission decision reason / 权限决策原因 */
    val hookPermissionDecisionReason: String? = null,
    /** Permission behavior / 权限行为 */
    val permissionBehavior: PermissionBehavior? = null,
    /** Additional contexts from all hooks / 来自所有钩子的额外上下文 */
    val additionalContexts: List<String>? = null,
    /** Initial user message override / 初始用户消息覆盖 */
    val initialUserMessage: String? = null,
    /** Updated tool input / 更新后的工具输入 */
    val updatedInput: JsonObject? = null,
    /** Updated MCP tool output / 更新后的 MCP 工具输出 */
    val updatedMCPToolOutput: JsonObject? = null,
    /** Whether to retry the operation / 是否重试操作 */
    val retry: Boolean = false
)
