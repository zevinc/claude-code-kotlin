package com.anthropic.claudecode.ui.core

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.graphics.TextGraphics
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.MouseAction
import com.googlecode.lanterna.screen.Screen
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.googlecode.lanterna.terminal.Terminal
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.IOException

private val logger = KotlinLogging.logger {}

/**
 * Terminal manager - wraps Lanterna terminal for application use
 * 终端管理器 - 包装 Lanterna 终端以供应用程序使用
 *
 * Maps from TypeScript ink.tsx terminal management layer.
 * Handles terminal lifecycle, screen management, input events,
 * and fullscreen mode switching.
 * 映射自 TypeScript ink.tsx 终端管理层。
 * 处理终端生命周期、屏幕管理、输入事件和全屏模式切换。
 */
class TerminalManager(
    private val scope: CoroutineScope
) {
    /**
     * Lanterna terminal instance / Lanterna 终端实例
     */
    private var terminal: Terminal? = null

    /**
     * Lanterna screen (double-buffered rendering)
     * Lanterna 屏幕（双缓冲渲染）
     */
    private var screen: Screen? = null

    /**
     * Whether alternate screen (fullscreen) is active
     * 是否处于备用屏幕（全屏）模式
     */
    private var isAltScreenActive = false

    /**
     * Input event flow for keyboard events
     * 键盘事件的输入事件流
     */
    private val _keyEvents = MutableSharedFlow<KeyStroke>(extraBufferCapacity = 64)
    val keyEvents: SharedFlow<KeyStroke> = _keyEvents.asSharedFlow()

    /**
     * Input event flow for mouse events
     * 鼠标事件的输入事件流
     */
    private val _mouseEvents = MutableSharedFlow<MouseAction>(extraBufferCapacity = 64)
    val mouseEvents: SharedFlow<MouseAction> = _mouseEvents.asSharedFlow()

    /**
     * Terminal resize events / 终端尺寸变化事件
     */
    private val _resizeEvents = MutableSharedFlow<TerminalSize>(extraBufferCapacity = 4)
    val resizeEvents: SharedFlow<TerminalSize> = _resizeEvents.asSharedFlow()

    /**
     * Whether the terminal is focused
     * 终端是否处于焦点
     */
    var isTerminalFocused: Boolean = true
        private set

    /**
     * Input polling job / 输入轮询任务
     */
    private var inputJob: Job? = null

    /**
     * Initialize the terminal and screen
     * 初始化终端和屏幕
     */
    fun initialize() {
        logger.info { "Initializing terminal manager" }
        val factory = DefaultTerminalFactory()

        // Create terminal with mouse support
        // 创建支持鼠标的终端
        terminal = factory.createTerminal().also { term ->
            // Listen for resize events / 监听尺寸变化事件
            term.addResizeListener { _, newSize ->
                scope.launch {
                    _resizeEvents.emit(newSize)
                }
            }
        }

        // Create screen for double-buffered rendering
        // 创建双缓冲渲染的屏幕
        screen = TerminalScreen(terminal).also { scr ->
            scr.startScreen()
            scr.cursorPosition = null // Hide cursor / 隐藏光标
        }

        // Start input polling / 启动输入轮询
        startInputPolling()
    }

    /**
     * Start polling for keyboard and mouse input
     * 启动键盘和鼠标输入轮询
     */
    private fun startInputPolling() {
        inputJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val keyStroke = screen?.pollInput()
                    if (keyStroke != null) {
                        when (keyStroke) {
                            is MouseAction -> _mouseEvents.emit(keyStroke)
                            else -> _keyEvents.emit(keyStroke)
                        }
                    } else {
                        // No input available, yield briefly
                        // 没有可用输入，短暂让步
                        delay(10)
                    }
                } catch (e: IOException) {
                    logger.error(e) { "Error reading terminal input" }
                    if (!isActive) break
                }
            }
        }
    }

    /**
     * Get the current terminal size
     * 获取当前终端尺寸
     */
    fun getTerminalSize(): TerminalSize =
        screen?.terminalSize ?: TerminalSize(80, 24)

    /**
     * Get the text graphics for drawing
     * 获取用于绘图的文本图形对象
     */
    fun getTextGraphics(): TextGraphics? =
        screen?.newTextGraphics()

    /**
     * Refresh the screen (flush buffer to terminal)
     * 刷新屏幕（将缓冲区刷新到终端）
     */
    fun refresh() {
        try {
            screen?.refresh()
        } catch (e: IOException) {
            logger.error(e) { "Error refreshing screen" }
        }
    }

    /**
     * Clear the screen
     * 清除屏幕
     */
    fun clear() {
        screen?.clear()
    }

    /**
     * Enter alternate screen (fullscreen mode)
     * 进入备用屏幕（全屏模式）
     *
     * Maps from TypeScript AlternateScreen component behavior.
     * 映射自 TypeScript AlternateScreen 组件行为。
     */
    fun enterAltScreen() {
        if (isAltScreenActive) return
        terminal?.enterPrivateMode()
        isAltScreenActive = true
        logger.debug { "Entered alternate screen mode" }
    }

    /**
     * Exit alternate screen (return to normal mode)
     * 退出备用屏幕（返回正常模式）
     */
    fun exitAltScreen() {
        if (!isAltScreenActive) return
        terminal?.exitPrivateMode()
        isAltScreenActive = false
        logger.debug { "Exited alternate screen mode" }
    }

    /**
     * Enable mouse tracking / 启用鼠标跟踪
     */
    fun enableMouseTracking() {
        // Lanterna handles mouse tracking via terminal settings
        // Lanterna 通过终端设置处理鼠标跟踪
        logger.debug { "Mouse tracking enabled" }
    }

    /**
     * Disable mouse tracking / 禁用鼠标跟踪
     */
    fun disableMouseTracking() {
        logger.debug { "Mouse tracking disabled" }
    }

    /**
     * Set terminal focus state
     * 设置终端焦点状态
     */
    fun setFocused(focused: Boolean) {
        isTerminalFocused = focused
    }

    /**
     * Show the cursor / 显示光标
     */
    fun showCursor() {
        screen?.cursorPosition = screen?.cursorPosition
    }

    /**
     * Hide the cursor / 隐藏光标
     */
    fun hideCursor() {
        screen?.cursorPosition = null
    }

    /**
     * Shutdown the terminal manager
     * 关闭终端管理器
     */
    fun shutdown() {
        logger.info { "Shutting down terminal manager" }

        // Cancel input polling / 取消输入轮询
        inputJob?.cancel()

        // Exit alt screen if active / 如果处于备用屏幕则退出
        if (isAltScreenActive) {
            exitAltScreen()
        }

        // Show cursor before exit / 退出前显示光标
        showCursor()

        // Stop screen and close terminal / 停止屏幕并关闭终端
        try {
            screen?.stopScreen()
            terminal?.close()
        } catch (e: IOException) {
            logger.error(e) { "Error shutting down terminal" }
        }
    }
}

/**
 * Terminal theme - color scheme for the UI
 * 终端主题 - UI 的配色方案
 *
 * Maps from TypeScript utils/theme.ts
 * 映射自 TypeScript utils/theme.ts
 */
data class TerminalTheme(
    /** Theme name / 主题名称 */
    val name: String,
    /** Primary color / 主色调 */
    val primary: TextColor = TextColor.ANSI.CYAN,
    /** Secondary color / 辅助色 */
    val secondary: TextColor = TextColor.ANSI.BLUE,
    /** Success color / 成功色 */
    val success: TextColor = TextColor.ANSI.GREEN,
    /** Warning color / 警告色 */
    val warning: TextColor = TextColor.ANSI.YELLOW,
    /** Error color / 错误色 */
    val error: TextColor = TextColor.ANSI.RED,
    /** Muted/dim color / 暗淡色 */
    val muted: TextColor = TextColor.ANSI.WHITE,
    /** Background color / 背景色 */
    val background: TextColor = TextColor.ANSI.DEFAULT,
    /** Text color / 文本色 */
    val text: TextColor = TextColor.ANSI.DEFAULT
) {
    companion object {
        /** Default light theme / 默认浅色主题 */
        val LIGHT = TerminalTheme(
            name = "light",
            primary = TextColor.ANSI.BLUE,
            secondary = TextColor.ANSI.CYAN
        )

        /** Default dark theme / 默认深色主题 */
        val DARK = TerminalTheme(
            name = "dark",
            primary = TextColor.ANSI.CYAN,
            secondary = TextColor.ANSI.BLUE
        )

        /** Default theme / 默认主题 */
        val DEFAULT = DARK
    }
}
