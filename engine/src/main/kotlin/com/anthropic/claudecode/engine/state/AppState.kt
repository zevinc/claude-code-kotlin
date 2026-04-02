package com.anthropic.claudecode.engine.state

import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Full application state - represents the complete state of the application
 * 完整应用状态 - 表示应用程序的完整状态
 *
 * Maps from TypeScript AppState (src/state/AppState.tsx, src/state/AppStateStore.ts).
 * Contains all immutable state fields + mutable task/agent state.
 * 映射自 TypeScript AppState。
 * 包含所有不可变状态字段 + 可变任务/代理状态。
 */
data class AppState(
    // === Core settings / 核心设置 ===
    /** Verbose output mode / 详细输出模式 */
    val verbose: Boolean = false,
    /** Debug mode / 调试模式 */
    val debug: Boolean = false,
    /** Current model for the main loop / 主循环当前模型 */
    val mainLoopModel: String = "claude-sonnet-4-20250514",
    /** Session-specific model override / 会话特定模型覆盖 */
    val mainLoopModelForSession: String? = null,
    /** Brief-only output mode / 简短输出模式 */
    val isBriefOnly: Boolean = false,

    // === Permission state / 权限状态 ===
    /** Tool permission context with rules and mode / 带规则和模式的工具权限上下文 */
    val toolPermissionContext: ToolPermissionContext = emptyToolPermissionContext(),

    // === Session state / 会话状态 ===
    /** Session ID / 会话 ID */
    val sessionId: SessionId? = null,
    /** Messages in current conversation / 当前对话中的消息 */
    val messages: List<Message> = emptyList(),
    /** Whether a query is in progress / 是否正在进行查询 */
    val isQueryInProgress: Boolean = false,
    /** Status line text displayed at bottom / 底部显示的状态行文本 */
    val statusLineText: String? = null,
    /** Current input buffer text / 当前输入缓冲区文本 */
    val inputBuffer: String = "",

    // === Task state / 任务状态 ===
    /** Running/completed background tasks / 运行中/已完成的后台任务 */
    val tasks: Map<String, TaskState> = emptyMap(),
    /** Foregrounded task ID / 前台任务 ID */
    val foregroundedTaskId: String? = null,

    // === Agent state / 代理状态 ===
    /** Available agent definitions / 可用代理定义 */
    val agentDefinitions: AgentDefinitions = AgentDefinitions(),

    // === File state / 文件状态 ===
    /** Tracked file read states for dedup / 用于去重的已跟踪文件读取状态 */
    val readFileState: Map<String, ReadFileState> = emptyMap(),

    // === UI state / UI 状态 ===
    /** Expanded sidebar view / 展开的侧边栏视图 */
    val expandedView: ExpandedView = ExpandedView.NONE,

    // === Notification state / 通知状态 ===
    /** Current notification / 当前通知 */
    val currentNotification: Notification? = null,
    /** Notification queue / 通知队列 */
    val notificationQueue: List<Notification> = emptyList()
)

/**
 * Background task state
 * 后台任务状态
 */
data class TaskState(
    /** Task ID / 任务 ID */
    val id: String,
    /** Task description / 任务描述 */
    val description: String,
    /** Task status / 任务状态 */
    val status: TaskStatus,
    /** Output lines / 输出行 */
    val output: List<String> = emptyList(),
    /** Error message / 错误消息 */
    val error: String? = null,
    /** Creation timestamp / 创建时间戳 */
    val createdAt: Long = System.currentTimeMillis(),
    /** Completion timestamp / 完成时间戳 */
    val completedAt: Long? = null
)

/**
 * Task status enum
 * 任务状态枚举
 */
enum class TaskStatus {
    /** Task is running / 任务运行中 */
    RUNNING,
    /** Task completed successfully / 任务成功完成 */
    COMPLETED,
    /** Task failed with error / 任务失败 */
    FAILED,
    /** Task was cancelled / 任务已取消 */
    CANCELLED
}

/**
 * Read file state for dedup tracking
 * 用于去重跟踪的文件读取状态
 */
data class ReadFileState(
    /** File content / 文件内容 */
    val content: String,
    /** File modification timestamp / 文件修改时间戳 */
    val mtime: Long,
    /** Read offset / 读取偏移量 */
    val offset: Int? = null,
    /** Read limit / 读取限制 */
    val limit: Int? = null,
    /** Whether this was a partial view / 是否为部分视图 */
    val isPartialView: Boolean = false
)

/**
 * Agent definitions container
 * 代理定义容器
 */
data class AgentDefinitions(
    /** Built-in agent types / 内置代理类型 */
    val builtinAgentTypes: List<String> = listOf("explore", "code-reviewer", "general-purpose"),
    /** Allowed agent types / 允许的代理类型 */
    val allowedAgentTypes: List<String> = builtinAgentTypes,
    /** Agent name registry / 代理名称注册表 */
    val agentNameRegistry: Map<String, AgentId> = emptyMap()
)

/**
 * Sidebar expanded view state
 * 侧边栏展开视图状态
 */
enum class ExpandedView {
    /** No sidebar expanded / 无侧边栏展开 */
    NONE,
    /** Tasks panel expanded / 任务面板展开 */
    TASKS,
    /** Teammates panel expanded / 队友面板展开 */
    TEAMMATES
}

/**
 * Application notification
 * 应用通知
 */
data class Notification(
    /** Notification message / 通知消息 */
    val message: String,
    /** Notification level / 通知级别 */
    val level: NotificationLevel = NotificationLevel.INFO,
    /** Auto-dismiss after ms / 自动关闭时间（毫秒） */
    val autoDismissMs: Long? = 3000
)

/**
 * Notification severity level
 * 通知严重程度级别
 */
enum class NotificationLevel {
    /** Informational notification / 信息通知 */
    INFO,
    /** Warning notification / 警告通知 */
    WARNING,
    /** Error notification / 错误通知 */
    ERROR,
    /** Success notification / 成功通知 */
    SUCCESS
}

/**
 * Application state store - manages and provides reactive access to the full app state
 * 应用状态存储 - 管理并提供对完整应用状态的响应式访问
 *
 * Maps from TypeScript AppStateStore.
 * Uses Kotlin StateFlow for reactive updates.
 * Thread-safe: all state updates are atomic via MutableStateFlow.update.
 * 映射自 TypeScript AppStateStore。
 * 使用 Kotlin StateFlow 进行响应式更新。
 * 线程安全：所有状态更新通过 MutableStateFlow.update 原子操作。
 */
class AppStateStore(initialState: AppState = AppState()) {
    /**
     * Internal mutable state flow / 内部可变状态流
     */
    private val _state = MutableStateFlow(initialState)

    /**
     * Public read-only state flow / 公开只读状态流
     */
    val state: StateFlow<AppState> = _state.asStateFlow()

    /**
     * Get current state snapshot / 获取当前状态快照
     */
    fun getState(): AppState = _state.value

    /**
     * Update state with a transformation function (atomic)
     * 使用转换函数更新状态（原子操作）
     *
     * @param updater Function to transform current state to next state
     *                将当前状态转换为下一状态的函数
     */
    fun setState(updater: (AppState) -> AppState) {
        _state.update(updater)
    }

    /**
     * Subscribe to state changes via a selector
     * 通过选择器订阅状态变化
     *
     * Similar to TypeScript useAppState(selector).
     * Returns a StateFlow that only emits when the selected value changes.
     * 类似于 TypeScript useAppState(selector)。
     * 返回一个仅在选中值变化时发出的 StateFlow。
     */
    fun <T> select(selector: (AppState) -> T): StateFlow<T> {
        // Note: For a full implementation, use a derived StateFlow
        // 注意：完整实现应使用派生的 StateFlow
        return kotlinx.coroutines.flow.MutableStateFlow(selector(getState())).asStateFlow()
    }

    /**
     * Add a message to the conversation / 向对话添加消息
     */
    fun addMessage(message: Message) {
        setState { state ->
            state.copy(messages = state.messages + message)
        }
    }

    /**
     * Update a background task state / 更新后台任务状态
     */
    fun updateTask(taskId: String, updater: (TaskState) -> TaskState) {
        setState { state ->
            val task = state.tasks[taskId] ?: return@setState state
            state.copy(tasks = state.tasks + (taskId to updater(task)))
        }
    }

    /**
     * Register a background task / 注册后台任务
     */
    fun registerTask(task: TaskState) {
        setState { state ->
            state.copy(tasks = state.tasks + (task.id to task))
        }
    }

    /**
     * Update read file state for dedup / 更新文件读取状态用于去重
     */
    fun updateReadFileState(filePath: String, fileState: ReadFileState) {
        setState { state ->
            state.copy(readFileState = state.readFileState + (filePath to fileState))
        }
    }

    /**
     * Show a notification / 显示通知
     */
    fun showNotification(notification: Notification) {
        setState { state ->
            if (state.currentNotification != null) {
                state.copy(notificationQueue = state.notificationQueue + notification)
            } else {
                state.copy(currentNotification = notification)
            }
        }
    }

    /**
     * Dismiss the current notification / 关闭当前通知
     */
    fun dismissNotification() {
        setState { state ->
            val next = state.notificationQueue.firstOrNull()
            state.copy(
                currentNotification = next,
                notificationQueue = if (next != null) state.notificationQueue.drop(1) else emptyList()
            )
        }
    }

    /**
     * Reset state to defaults / 重置状态为默认值
     */
    fun reset() {
        _state.value = AppState()
    }
}
