package com.anthropic.claudecode.ui.input

import com.anthropic.claudecode.commands.CommandRegistry
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * InputProcessor - handles advanced input features
 * InputProcessor - 处理高级输入功能
 *
 * Provides multi-line input support, tab completion for
 * slash commands, and input history navigation.
 * 提供多行输入支持、斜杠命令的 Tab 补全和输入历史导航。
 */
class InputProcessor(
    private val commandRegistry: CommandRegistry? = null
) {
    private val history = mutableListOf<String>()
    private var historyIndex = -1
    private var multiLineBuffer = StringBuilder()
    var isMultiLineMode = false
        private set

    /**
     * Process a character input / 处理字符输入
     */
    fun processChar(char: Char, currentInput: String): InputAction {
        return InputAction.Append(char)
    }

    /**
     * Process Enter key / 处理回车键
     * Returns whether to submit or continue multi-line
     */
    fun processEnter(currentInput: String): InputAction {
        // Check for multi-line continuation (trailing backslash or open brackets)
        // 检查多行延续（尾部反斜杠或未关闭的括号）
        if (currentInput.endsWith("\\")) {
            multiLineBuffer.append(currentInput.dropLast(1)).append("\n")
            isMultiLineMode = true
            return InputAction.ContinueLine
        }

        if (isMultiLineMode) {
            multiLineBuffer.append(currentInput)
            val fullInput = multiLineBuffer.toString()
            multiLineBuffer.clear()
            isMultiLineMode = false
            addToHistory(fullInput)
            return InputAction.Submit(fullInput)
        }

        if (currentInput.isNotBlank()) {
            addToHistory(currentInput)
        }
        return InputAction.Submit(currentInput)
    }

    /**
     * Tab completion for slash commands / 斜杠命令的 Tab 补全
     */
    fun tabComplete(currentInput: String): String? {
        if (!currentInput.startsWith("/")) return null

        val prefix = currentInput.removePrefix("/").lowercase()
        val commands = commandRegistry?.getAllCommands() ?: return null
        val matches = commands.filter {
            it.name.startsWith(prefix) || it.aliases.any { a -> a.startsWith(prefix) }
        }

        return when {
            matches.isEmpty() -> null
            matches.size == 1 -> "/${matches.first().name} "
            else -> {
                // Find common prefix / 查找公共前缀
                val names = matches.map { it.name }
                val commonPrefix = names.reduce { acc, s ->
                    acc.commonPrefixWith(s)
                }
                if (commonPrefix.length > prefix.length) "/$commonPrefix" else null
            }
        }
    }

    /**
     * Navigate history up / 向上导航历史
     */
    fun historyUp(): String? {
        if (history.isEmpty()) return null
        historyIndex = (historyIndex + 1).coerceAtMost(history.size - 1)
        return history[history.size - 1 - historyIndex]
    }

    /**
     * Navigate history down / 向下导航历史
     */
    fun historyDown(): String? {
        if (historyIndex <= 0) {
            historyIndex = -1
            return ""
        }
        historyIndex--
        return history[history.size - 1 - historyIndex]
    }

    private fun addToHistory(input: String) {
        if (input.isBlank()) return
        // Avoid duplicates / 避免重复
        if (history.lastOrNull() != input) {
            history.add(input)
        }
        historyIndex = -1
    }

    fun getHistorySize(): Int = history.size
}

sealed class InputAction {
    data class Append(val char: Char) : InputAction()
    data class Submit(val text: String) : InputAction()
    data object ContinueLine : InputAction()
    data object NoOp : InputAction()
}
