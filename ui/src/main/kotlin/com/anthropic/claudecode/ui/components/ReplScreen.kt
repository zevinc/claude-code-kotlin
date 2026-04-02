package com.anthropic.claudecode.ui.components

import com.anthropic.claudecode.types.ContentBlock
import com.anthropic.claudecode.types.Message
import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.graphics.TextGraphics
import com.googlecode.lanterna.TerminalSize
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * ReplScreen - main REPL screen layout with messages, input, and status
 * ReplScreen - 主 REPL 屏幕布局，包含消息、输入和状态
 *
 * Maps from TypeScript ink/components/REPL.tsx.
 * Renders: header, message list, prompt input, status bar.
 * 映射自 TypeScript ink/components/REPL.tsx。
 * 渲染：头部、消息列表、提示输入、状态栏。
 */
class ReplScreen {

    private val messageList = MessageList()
    private val promptInput = PromptInput()
    private val statusBar = StatusBar()

    /**
     * Render the full REPL screen
     * 渲染完整的 REPL 屏幕
     */
    fun render(
        graphics: TextGraphics,
        size: TerminalSize,
        state: ReplScreenState
    ) {
        // Layout: header(1) + messages(dynamic) + input(3) + status(1)
        // 布局：头部(1) + 消息(动态) + 输入(3) + 状态(1)
        val headerHeight = 1
        val inputHeight = 3
        val statusHeight = 1
        val messageHeight = size.rows - headerHeight - inputHeight - statusHeight

        // Render header / 渲染头部
        renderHeader(graphics, size, state)

        // Render messages / 渲染消息
        messageList.render(
            graphics = graphics,
            offsetY = headerHeight,
            width = size.columns,
            height = messageHeight,
            messages = state.messages,
            isStreaming = state.isStreaming,
            streamingText = state.streamingText
        )

        // Render prompt input / 渲染提示输入
        promptInput.render(
            graphics = graphics,
            offsetY = headerHeight + messageHeight,
            width = size.columns,
            height = inputHeight,
            inputText = state.inputBuffer,
            cursorPosition = state.cursorPosition,
            isEnabled = !state.isQueryInProgress
        )

        // Render status bar / 渲染状态栏
        statusBar.render(
            graphics = graphics,
            offsetY = size.rows - statusHeight,
            width = size.columns,
            model = state.model,
            statusText = state.statusText,
            messageCount = state.messages.size
        )
    }

    /**
     * Render the header bar / 渲染头部栏
     */
    private fun renderHeader(graphics: TextGraphics, size: TerminalSize, state: ReplScreenState) {
        val title = " Claude Code (Kotlin) "
        val modelInfo = " [${state.model}] "
        val padding = size.columns - title.length - modelInfo.length

        graphics.foregroundColor = TextColor.ANSI.WHITE
        graphics.backgroundColor = TextColor.ANSI.BLUE
        graphics.enableModifiers(SGR.BOLD)
        graphics.putString(0, 0, title + " ".repeat(padding.coerceAtLeast(0)) + modelInfo)
        graphics.disableModifiers(SGR.BOLD)
        graphics.backgroundColor = TextColor.ANSI.DEFAULT
    }
}

/**
 * REPL screen state / REPL 屏幕状态
 */
data class ReplScreenState(
    val messages: List<Message> = emptyList(),
    val inputBuffer: String = "",
    val cursorPosition: Int = 0,
    val model: String = "claude-sonnet-4-20250514",
    val isQueryInProgress: Boolean = false,
    val isStreaming: Boolean = false,
    val streamingText: String = "",
    val statusText: String = ""
)

/**
 * MessageList - renders conversation messages
 * MessageList - 渲染对话消息
 *
 * Maps from TypeScript ink/components/MessageList.tsx.
 * Supports: user/assistant messages, tool use blocks, streaming text.
 * 映射自 TypeScript ink/components/MessageList.tsx。
 * 支持：用户/助手消息、工具使用块、流式文本。
 */
class MessageList {

    /** Scroll offset for long message lists / 长消息列表的滚动偏移 */
    private var scrollOffset = 0

    /**
     * Render messages within the given area
     * 在给定区域内渲染消息
     */
    fun render(
        graphics: TextGraphics,
        offsetY: Int,
        width: Int,
        height: Int,
        messages: List<Message>,
        isStreaming: Boolean,
        streamingText: String
    ) {
        // Build render lines from messages / 从消息构建渲染行
        val lines = mutableListOf<RenderLine>()

        for (message in messages) {
            lines.addAll(messageToLines(message, width))
            lines.add(RenderLine("", TextColor.ANSI.DEFAULT)) // Separator
        }

        // Add streaming text if active / 如果活跃则添加流式文本
        if (isStreaming && streamingText.isNotEmpty()) {
            lines.add(RenderLine("  Claude > ", TextColor.ANSI.GREEN))
            streamingText.lines().forEach { line ->
                lines.add(RenderLine("    $line", TextColor.ANSI.WHITE))
            }
            lines.add(RenderLine("    ...", TextColor.ANSI.YELLOW))
        }

        // Auto-scroll to bottom / 自动滚动到底部
        if (lines.size > height) {
            scrollOffset = lines.size - height
        }

        // Render visible lines / 渲染可见行
        val visibleLines = lines.drop(scrollOffset).take(height)
        for ((idx, line) in visibleLines.withIndex()) {
            graphics.foregroundColor = line.color
            graphics.backgroundColor = TextColor.ANSI.DEFAULT
            val displayText = if (line.text.length > width) {
                line.text.take(width - 3) + "..."
            } else {
                line.text + " ".repeat((width - line.text.length).coerceAtLeast(0))
            }
            graphics.putString(0, offsetY + idx, displayText)
        }

        // Fill remaining space / 填充剩余空间
        graphics.foregroundColor = TextColor.ANSI.DEFAULT
        for (i in visibleLines.size until height) {
            graphics.putString(0, offsetY + i, " ".repeat(width))
        }
    }

    /**
     * Convert a message to render lines / 将消息转换为渲染行
     */
    private fun messageToLines(message: Message, width: Int): List<RenderLine> {
        val lines = mutableListOf<RenderLine>()

        when (message) {
            is Message.User -> {
                lines.add(RenderLine("  You > ", TextColor.ANSI.CYAN))
                for (block in message.content) {
                    when (block) {
                        is ContentBlock.Text -> {
                            block.text.lines().forEach { line ->
                                lines.add(RenderLine("    $line", TextColor.ANSI.WHITE))
                            }
                        }
                        is ContentBlock.ToolResult -> {
                            lines.add(RenderLine("    [Tool Result: ${block.toolUseId}]",
                                if (block.isError) TextColor.ANSI.RED else TextColor.ANSI.GREEN))
                        }
                        else -> {}
                    }
                }
            }
            is Message.Assistant -> {
                lines.add(RenderLine("  Claude > ", TextColor.ANSI.GREEN))
                for (block in message.content) {
                    when (block) {
                        is ContentBlock.Text -> {
                            block.text.lines().forEach { line ->
                                lines.add(RenderLine("    $line", TextColor.ANSI.WHITE))
                            }
                        }
                        is ContentBlock.ToolUse -> {
                            lines.add(RenderLine("    [Tool: ${block.name}]", TextColor.ANSI.YELLOW))
                        }
                        else -> {}
                    }
                }
            }
            is Message.System -> {
                // Determine display based on subtype / 根据子类型确定显示方式
                val color = if (message.subtype == "error") TextColor.ANSI.RED else TextColor.ANSI.BLUE
                val prefix = if (message.subtype == "error") "  Error: " else "  System: "
                lines.add(RenderLine("$prefix${message.content}", color))
            }
            is Message.Progress -> {
                lines.add(RenderLine("  [Progress: ${message.toolUseId}]", TextColor.ANSI.YELLOW))
            }
            is Message.Attachment -> {
                lines.add(RenderLine("  [Attachment]", TextColor.ANSI.MAGENTA))
            }
            is Message.Tombstone -> {
                // Skip tombstone messages / 跳过墓碑消息
            }
        }

        return lines
    }

    fun scrollUp(amount: Int = 3) {
        scrollOffset = (scrollOffset - amount).coerceAtLeast(0)
    }

    fun scrollDown(amount: Int = 3) {
        scrollOffset += amount
    }
}

/**
 * A single line to render / 要渲染的单行
 */
private data class RenderLine(val text: String, val color: TextColor)

/**
 * PromptInput - text input component for user prompts
 * PromptInput - 用户提示的文本输入组件
 *
 * Maps from TypeScript ink/components/PromptInput.tsx.
 * Supports: multi-line editing, cursor movement, input history.
 * 映射自 TypeScript ink/components/PromptInput.tsx。
 * 支持：多行编辑、光标移动、输入历史。
 */
class PromptInput {

    /**
     * Render the prompt input area
     * 渲染提示输入区域
     */
    fun render(
        graphics: TextGraphics,
        offsetY: Int,
        width: Int,
        height: Int,
        inputText: String,
        cursorPosition: Int,
        isEnabled: Boolean
    ) {
        // Top border / 上边框
        graphics.foregroundColor = TextColor.ANSI.WHITE
        graphics.putString(0, offsetY, "─".repeat(width))

        // Prompt indicator / 提示指示器
        val promptPrefix = if (isEnabled) " > " else " ⏳ "
        graphics.foregroundColor = if (isEnabled) TextColor.ANSI.CYAN else TextColor.ANSI.YELLOW
        graphics.putString(0, offsetY + 1, promptPrefix)

        // Input text / 输入文本
        val displayWidth = width - promptPrefix.length
        val displayText = if (inputText.length > displayWidth) {
            "..." + inputText.takeLast(displayWidth - 3)
        } else {
            inputText + " ".repeat((displayWidth - inputText.length).coerceAtLeast(0))
        }
        graphics.foregroundColor = TextColor.ANSI.WHITE
        graphics.putString(promptPrefix.length, offsetY + 1, displayText)

        // Bottom line (hint) / 底部行（提示）
        val hint = if (isEnabled) {
            "Enter to submit | Ctrl+C to exit | /help for commands"
        } else {
            "Waiting for response..."
        }
        graphics.foregroundColor = TextColor.ANSI.BLACK_BRIGHT
        graphics.putString(0, offsetY + 2,
            hint.take(width) + " ".repeat((width - hint.length).coerceAtLeast(0)))
    }
}

/**
 * StatusBar - bottom status line
 * StatusBar - 底部状态行
 *
 * Maps from TypeScript ink/components/StatusLine.tsx.
 * Shows: model, message count, status text.
 * 映射自 TypeScript ink/components/StatusLine.tsx。
 * 显示：模型、消息数、状态文本。
 */
class StatusBar {

    /**
     * Render the status bar / 渲染状态栏
     */
    fun render(
        graphics: TextGraphics,
        offsetY: Int,
        width: Int,
        model: String,
        statusText: String,
        messageCount: Int
    ) {
        val left = " $statusText"
        val right = "$messageCount msgs | $model "
        val padding = width - left.length - right.length

        graphics.foregroundColor = TextColor.ANSI.WHITE
        graphics.backgroundColor = TextColor.ANSI.BLACK_BRIGHT
        graphics.putString(0, offsetY,
            left + " ".repeat(padding.coerceAtLeast(1)) + right)
        graphics.backgroundColor = TextColor.ANSI.DEFAULT
    }
}
