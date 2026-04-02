package com.anthropic.claudecode.ui.state

import com.googlecode.lanterna.TerminalSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * UI state - represents the current state of the terminal UI
 * UI 状态 - 表示终端 UI 的当前状态
 *
 * Maps from TypeScript AppState (UI-relevant portions).
 * Uses Kotlin's data class for immutability and StateFlow for reactivity.
 * 映射自 TypeScript AppState（UI 相关部分）。
 * 使用 Kotlin 的 data class 实现不可变性，StateFlow 实现响应式。
 */
data class UiState(
    /** Terminal dimensions / 终端尺寸 */
    val terminalSize: TerminalSize = TerminalSize(80, 24),
    /** Whether the terminal is focused / 终端是否处于焦点 */
    val isTerminalFocused: Boolean = true,
    /** Current error (error boundary) / 当前错误（错误边界） */
    val error: Throwable? = null,
    /** Whether a query is in progress / 是否正在进行查询 */
    val isQueryInProgress: Boolean = false,
    /** Current input buffer text / 当前输入缓冲区文本 */
    val inputBuffer: String = "",
    /** Status line text / 状态行文本 */
    val statusLineText: String = "",
    /** Whether in fullscreen mode / 是否处于全屏模式 */
    val isFullscreen: Boolean = false,
    /** Current spinner text / 当前加载动画文本 */
    val spinnerText: String? = null,
    /** Whether verbose mode is on / 是否处于详细模式 */
    val verbose: Boolean = false
)

/**
 * UI state store - manages and provides reactive access to UI state
 * UI 状态存储 - 管理并提供 UI 状态的响应式访问
 *
 * Maps from TypeScript AppStateStore pattern.
 * Uses Kotlin StateFlow for reactive updates (equivalent to React useState/useEffect).
 * 映射自 TypeScript AppStateStore 模式。
 * 使用 Kotlin StateFlow 进行响应式更新（等价于 React useState/useEffect）。
 */
class UiStateStore {
    /**
     * Mutable state flow (internal)
     * 可变状态流（内部）
     */
    private val _state = MutableStateFlow(UiState())

    /**
     * Read-only state flow (external)
     * 只读状态流（外部）
     */
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Get current state value / 获取当前状态值
     */
    val currentState: UiState
        get() = _state.value

    /**
     * Update state with a transformation function
     * 使用转换函数更新状态
     *
     * Equivalent to TypeScript setAppState(f: (prev) => next)
     * 等价于 TypeScript setAppState(f: (prev) => next)
     *
     * @param updater Function to transform current state to next state
     *                将当前状态转换为下一状态的函数
     */
    fun update(updater: (UiState) -> UiState) {
        _state.update(updater)
    }

    /**
     * Reset state to default values / 重置状态为默认值
     */
    fun reset() {
        _state.value = UiState()
    }
}
