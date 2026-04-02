package com.anthropic.claudecode.ui.input

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.BufferedReader
import java.io.InputStreamReader

private val logger = KotlinLogging.logger {}

/**
 * InputHandler - handles user input with history and multi-line support
 * InputHandler - 处理用户输入，支持历史记录和多行输入
 *
 * Maps from TypeScript components/Input.tsx and hooks/useInputHistory.
 * Provides line editing, history navigation, tab completion hooks,
 * and multi-line input (backslash continuation).
 * 映射自 TypeScript components/Input.tsx 和 hooks/useInputHistory。
 * 提供行编辑、历史导航、Tab 补全钩子和多行输入（反斜杠续行）。
 */
class InputHandler(
    private val maxHistorySize: Int = MAX_HISTORY
) {
    private val history = mutableListOf<String>()
    private var historyIndex: Int = -1
    private val reader: BufferedReader = BufferedReader(InputStreamReader(System.`in`))

    /**
     * Read a single line of input with prompt
     * 带提示读取单行输入
     *
     * @param prompt The prompt to display / 要显示的提示
     * @return User input, or null if EOF / 用户输入，如果 EOF 则返回 null
     */
    fun readLine(prompt: String = "> "): String? {
        print(prompt)
        System.out.flush()

        val line = reader.readLine() ?: return null
        val trimmed = line.trim()

        // Handle multi-line continuation / 处理多行续行
        if (trimmed.endsWith("\\")) {
            val multiLine = StringBuilder(trimmed.dropLast(1))
            while (true) {
                print("... ")
                System.out.flush()
                val nextLine = reader.readLine() ?: break
                val nextTrimmed = nextLine.trim()
                if (nextTrimmed.endsWith("\\")) {
                    multiLine.append("\n").append(nextTrimmed.dropLast(1))
                } else {
                    multiLine.append("\n").append(nextTrimmed)
                    break
                }
            }
            val result = multiLine.toString()
            addToHistory(result)
            return result
        }

        if (trimmed.isNotBlank()) {
            addToHistory(trimmed)
        }
        return trimmed
    }

    /**
     * Read multi-line input (terminated by empty line or Ctrl+D)
     * 读取多行输入（以空行或 Ctrl+D 结束）
     */
    fun readMultiLine(prompt: String = "> ", continuation: String = "... "): String? {
        print(prompt)
        System.out.flush()

        val lines = mutableListOf<String>()
        var first = true
        while (true) {
            if (!first) {
                print(continuation)
                System.out.flush()
            }
            first = false

            val line = reader.readLine() ?: break
            if (line.isBlank() && lines.isNotEmpty()) break
            lines.add(line)
        }

        if (lines.isEmpty()) return null
        val result = lines.joinToString("\n")
        addToHistory(result)
        return result
    }

    /**
     * Add input to history / 将输入添加到历史
     */
    private fun addToHistory(input: String) {
        // Avoid duplicates at the end / 避免末尾重复
        if (history.lastOrNull() == input) return

        history.add(input)
        if (history.size > maxHistorySize) {
            history.removeFirst()
        }
        historyIndex = -1
    }

    /**
     * Navigate history up (previous) / 向上导航历史（上一条）
     */
    fun previousHistory(): String? {
        if (history.isEmpty()) return null
        if (historyIndex < 0) historyIndex = history.size
        historyIndex--
        return if (historyIndex >= 0) history[historyIndex] else null
    }

    /**
     * Navigate history down (next) / 向下导航历史（下一条）
     */
    fun nextHistory(): String? {
        if (history.isEmpty()) return null
        historyIndex++
        return if (historyIndex < history.size) history[historyIndex] else {
            historyIndex = -1
            null
        }
    }

    /**
     * Get full history / 获取完整历史
     */
    fun getHistory(): List<String> = history.toList()

    /**
     * Clear history / 清除历史
     */
    fun clearHistory() {
        history.clear()
        historyIndex = -1
    }

    companion object {
        const val MAX_HISTORY = 500
    }
}

/**
 * TabCompletionProvider - provides tab completion suggestions
 * TabCompletionProvider - 提供 Tab 补全建议
 */
interface TabCompletionProvider {
    /**
     * Get completions for the current input
     * 获取当前输入的补全建议
     *
     * @param input Current input text / 当前输入文本
     * @param cursorPosition Cursor position / 光标位置
     * @return List of completion suggestions / 补全建议列表
     */
    fun getCompletions(input: String, cursorPosition: Int): List<Completion>
}

data class Completion(
    val text: String,
    val displayText: String = text,
    val description: String? = null,
    val type: CompletionType = CompletionType.GENERAL
)

enum class CompletionType { COMMAND, FILE, TOOL, GENERAL }

/**
 * SlashCommandCompletion - completes slash commands
 * SlashCommandCompletion - 补全斜杠命令
 */
class SlashCommandCompletion(
    private val commandNames: List<String>
) : TabCompletionProvider {
    override fun getCompletions(input: String, cursorPosition: Int): List<Completion> {
        if (!input.startsWith("/")) return emptyList()
        val prefix = input.removePrefix("/").lowercase()
        return commandNames
            .filter { it.startsWith(prefix) }
            .map { Completion(text = "/$it", type = CompletionType.COMMAND) }
    }
}
