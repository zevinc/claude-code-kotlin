package com.anthropic.claudecode.ui.components

import com.anthropic.claudecode.ui.core.TerminalManager
import com.anthropic.claudecode.ui.core.TerminalTheme
import com.anthropic.claudecode.ui.state.UiState
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.graphics.TextGraphics
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private val logger = KotlinLogging.logger {}

/**
 * App component - root UI container for the Claude Code application
 * App 组件 - Claude Code 应用程序的根 UI 容器
 *
 * Maps from TypeScript ink/components/App.tsx.
 * This is the top-level component that manages:
 * - Terminal lifecycle (cursor visibility, raw mode)
 * - Input handling (keyboard events, mouse events)
 * - Screen rendering loop
 * - Error boundary
 * - Context providers (terminal size, theme, focus state)
 *
 * 映射自 TypeScript ink/components/App.tsx。
 * 这是管理以下内容的顶级组件：
 * - 终端生命周期（光标可见性、原始模式）
 * - 输入处理（键盘事件、鼠标事件）
 * - 屏幕渲染循环
 * - 错误边界
 * - 上下文提供者（终端尺寸、主题、焦点状态）
 */
class App(
    private val terminalManager: TerminalManager,
    private val scope: CoroutineScope,
    private val theme: TerminalTheme = TerminalTheme.DEFAULT
) {
    /**
     * UI state flow / UI 状态流
     */
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * Whether the app is running / 应用是否正在运行
     */
    private var isRunning = false

    /**
     * Render loop job / 渲染循环任务
     */
    private var renderJob: Job? = null

    /**
     * Child screens / 子屏幕
     */
    private val screens = mutableListOf<Screen>()

    /**
     * Active screen / 活动屏幕
     */
    private var activeScreen: Screen? = null

    /**
     * Current error (error boundary state)
     * 当前错误（错误边界状态）
     *
     * Maps from TypeScript App.state.error
     * 映射自 TypeScript App.state.error
     */
    private var currentError: Throwable? = null

    /**
     * Exit callback / 退出回调
     */
    var onExit: ((Throwable?) -> Unit)? = null

    /**
     * Exit on Ctrl+C / 按 Ctrl+C 退出
     */
    var exitOnCtrlC: Boolean = true

    /**
     * Initialize and start the application
     * 初始化并启动应用程序
     *
     * Maps from TypeScript App.componentDidMount()
     * 映射自 TypeScript App.componentDidMount()
     */
    fun start() {
        logger.info { "Starting Claude Code application" }
        isRunning = true

        // Hide cursor on start (unless accessibility mode)
        // 启动时隐藏光标（除非辅助功能模式）
        terminalManager.hideCursor()

        // Start input handling / 启动输入处理
        startInputHandling()

        // Start render loop / 启动渲染循环
        startRenderLoop()
    }

    /**
     * Stop the application
     * 停止应用程序
     *
     * Maps from TypeScript App.componentWillUnmount()
     * 映射自 TypeScript App.componentWillUnmount()
     */
    fun stop(error: Throwable? = null) {
        logger.info { "Stopping Claude Code application${error?.let { ": ${it.message}" } ?: ""}" }
        isRunning = false
        renderJob?.cancel()

        // Show cursor before exit / 退出前显示光标
        terminalManager.showCursor()

        // Invoke exit callback / 调用退出回调
        onExit?.invoke(error)
    }

    /**
     * Start handling keyboard and mouse input
     * 启动键盘和鼠标输入处理
     *
     * Maps from TypeScript App.handleReadable() and processInput()
     * 映射自 TypeScript App.handleReadable() 和 processInput()
     */
    private fun startInputHandling() {
        // Handle keyboard events / 处理键盘事件
        scope.launch {
            terminalManager.keyEvents.collect { keyStroke ->
                try {
                    handleKeyStroke(keyStroke)
                } catch (e: Exception) {
                    logger.error(e) { "Error handling key input" }
                    handleError(e)
                }
            }
        }

        // Handle mouse events / 处理鼠标事件
        scope.launch {
            terminalManager.mouseEvents.collect { mouseAction ->
                try {
                    handleMouseAction(mouseAction)
                } catch (e: Exception) {
                    logger.error(e) { "Error handling mouse input" }
                }
            }
        }

        // Handle terminal resize / 处理终端尺寸变化
        scope.launch {
            terminalManager.resizeEvents.collect { newSize ->
                _uiState.update { it.copy(terminalSize = newSize) }
                requestRender()
            }
        }
    }

    /**
     * Handle a keyboard event
     * 处理键盘事件
     *
     * Maps from TypeScript App.handleInput() and processKeysInBatch()
     * 映射自 TypeScript App.handleInput() 和 processKeysInBatch()
     */
    private fun handleKeyStroke(keyStroke: com.googlecode.lanterna.input.KeyStroke) {
        // Exit on Ctrl+C / 按 Ctrl+C 退出
        if (exitOnCtrlC && keyStroke.character == 'c' && keyStroke.isCtrlDown) {
            stop()
            return
        }

        // Handle Ctrl+Z (suspend) on Unix platforms
        // 在 Unix 平台上处理 Ctrl+Z（挂起）
        if (keyStroke.character == 'z' && keyStroke.isCtrlDown &&
            com.anthropic.claudecode.utils.EnvUtils.supportsSuspend
        ) {
            handleSuspend()
            return
        }

        // Forward to active screen / 转发到活动屏幕
        activeScreen?.onKeyStroke(keyStroke)
    }

    /**
     * Handle a mouse action
     * 处理鼠标操作
     *
     * Maps from TypeScript handleMouseEvent()
     * 映射自 TypeScript handleMouseEvent()
     */
    private fun handleMouseAction(mouseAction: com.googlecode.lanterna.input.MouseAction) {
        // Forward to active screen / 转发到活动屏幕
        activeScreen?.onMouseAction(mouseAction)
    }

    /**
     * Handle process suspension (Ctrl+Z)
     * 处理进程挂起（Ctrl+Z）
     *
     * Maps from TypeScript App.handleSuspend()
     * 映射自 TypeScript App.handleSuspend()
     */
    private fun handleSuspend() {
        logger.debug { "Handling suspend (Ctrl+Z)" }

        // Show cursor before suspending / 挂起前显示光标
        terminalManager.showCursor()

        // Note: JVM doesn't directly support SIGSTOP like Node.js
        // On JVM, we can emit an event for the outer process to handle
        // 注意：JVM 不像 Node.js 那样直接支持 SIGSTOP
        // 在 JVM 上，我们可以发出事件让外部进程处理
        logger.info { "Suspend requested (not natively supported on JVM)" }
    }

    /**
     * Handle an error (error boundary)
     * 处理错误（错误边界）
     *
     * Maps from TypeScript App.getDerivedStateFromError() and componentDidCatch()
     * 映射自 TypeScript App.getDerivedStateFromError() 和 componentDidCatch()
     */
    private fun handleError(error: Throwable) {
        currentError = error
        _uiState.update { it.copy(error = error) }
        requestRender()
    }

    /**
     * Start the render loop
     * 启动渲染循环
     */
    private fun startRenderLoop() {
        renderJob = scope.launch {
            while (isActive && isRunning) {
                render()
                delay(16) // ~60 FPS / 约 60 帧每秒
            }
        }
    }

    /**
     * Request an immediate render
     * 请求立即渲染
     */
    fun requestRender() {
        // The render loop will pick up changes on next tick
        // 渲染循环将在下一次tick时获取变更
    }

    /**
     * Render the current state to the terminal
     * 将当前状态渲染到终端
     */
    private fun render() {
        val graphics = terminalManager.getTextGraphics() ?: return
        val size = terminalManager.getTerminalSize()

        // Clear screen / 清除屏幕
        terminalManager.clear()

        // Render error screen if there's an error
        // 如果有错误则渲染错误屏幕
        if (currentError != null) {
            renderError(graphics, size, currentError!!)
        } else {
            // Render active screen / 渲染活动屏幕
            activeScreen?.render(graphics, size)
        }

        // Flush to terminal / 刷新到终端
        terminalManager.refresh()
    }

    /**
     * Render error overview
     * 渲染错误概览
     *
     * Maps from TypeScript ErrorOverview component
     * 映射自 TypeScript ErrorOverview 组件
     */
    private fun renderError(graphics: TextGraphics, size: TerminalSize, error: Throwable) {
        graphics.foregroundColor = TextColor.ANSI.RED
        graphics.putString(0, 0, "Error: ${error.message}")
        graphics.foregroundColor = TextColor.ANSI.DEFAULT

        // Render stack trace / 渲染堆栈跟踪
        val stackLines = error.stackTraceToString().lines()
        for ((index, line) in stackLines.take(size.rows - 2).withIndex()) {
            graphics.putString(0, index + 2, line.take(size.columns))
        }
    }

    /**
     * Set the active screen
     * 设置活动屏幕
     */
    fun setActiveScreen(screen: Screen) {
        activeScreen = screen
        requestRender()
    }

    /**
     * Update the UI state
     * 更新 UI 状态
     */
    fun updateState(updater: (UiState) -> UiState) {
        _uiState.update(updater)
        requestRender()
    }
}

/**
 * Screen interface - base for all full-screen views
 * Screen 接口 - 所有全屏视图的基础
 */
interface Screen {
    /**
     * Render this screen to the graphics context
     * 将此屏幕渲染到图形上下文
     */
    fun render(graphics: TextGraphics, size: TerminalSize)

    /**
     * Handle keyboard input / 处理键盘输入
     */
    fun onKeyStroke(keyStroke: com.googlecode.lanterna.input.KeyStroke) {}

    /**
     * Handle mouse input / 处理鼠标输入
     */
    fun onMouseAction(mouseAction: com.googlecode.lanterna.input.MouseAction) {}
}
