package com.anthropic.claudecode.ui.render

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import java.io.PrintStream

private val logger = KotlinLogging.logger {}

/**
 * SpinnerRenderer - animated progress indicator for tool execution
 * SpinnerRenderer - 工具执行的动画进度指示器
 *
 * Maps from TypeScript components/Spinner.tsx and ToolUseLoader.
 * Shows animated spinner with activity description while tools execute.
 * 映射自 TypeScript components/Spinner.tsx 和 ToolUseLoader。
 * 在工具执行时显示带有活动描述的动画旋转器。
 */
class SpinnerRenderer(
    private val output: PrintStream = System.err,
    private val style: SpinnerStyle = SpinnerStyle.DOTS
) {
    private var job: Job? = null
    private var currentMessage: String = ""
    private var frameIndex = 0

    /**
     * Start spinning with a message
     * 开始旋转并显示消息
     */
    fun start(scope: CoroutineScope, message: String = "") {
        currentMessage = message
        frameIndex = 0

        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                render()
                delay(style.intervalMs)
                frameIndex = (frameIndex + 1) % style.frames.size
            }
            clearLine()
        }
    }

    /**
     * Update the spinner message / 更新旋转器消息
     */
    fun updateMessage(message: String) {
        currentMessage = message
    }

    /**
     * Stop the spinner / 停止旋转器
     */
    fun stop() {
        job?.cancel()
        job = null
        clearLine()
    }

    /**
     * Stop with a final message / 停止并显示最终消息
     */
    fun stopWithMessage(message: String) {
        stop()
        output.println(message)
    }

    private fun render() {
        val frame = style.frames[frameIndex]
        clearLine()
        if (currentMessage.isNotBlank()) {
            output.print("\r${style.color}$frame${AnsiColors.ENABLED.reset} $currentMessage")
        } else {
            output.print("\r${style.color}$frame${AnsiColors.ENABLED.reset}")
        }
        output.flush()
    }

    private fun clearLine() {
        output.print("\r\u001b[2K")
        output.flush()
    }
}

/**
 * Spinner visual styles / 旋转器视觉样式
 */
data class SpinnerStyle(
    val frames: List<String>,
    val intervalMs: Long,
    val color: String = AnsiColors.ENABLED.cyan
) {
    companion object {
        val DOTS = SpinnerStyle(
            frames = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"),
            intervalMs = 80
        )

        val LINE = SpinnerStyle(
            frames = listOf("|", "/", "-", "\\"),
            intervalMs = 120
        )

        val ARROW = SpinnerStyle(
            frames = listOf("←", "↖", "↑", "↗", "→", "↘", "↓", "↙"),
            intervalMs = 120
        )

        val PULSE = SpinnerStyle(
            frames = listOf("◐", "◓", "◑", "◒"),
            intervalMs = 150
        )
    }
}
