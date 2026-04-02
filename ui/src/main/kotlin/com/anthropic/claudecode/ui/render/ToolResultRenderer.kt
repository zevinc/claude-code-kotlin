package com.anthropic.claudecode.ui.render

import com.anthropic.claudecode.engine.execution.ToolExecutionResult
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * ToolResultRenderer - formats tool execution results for terminal display
 * ToolResultRenderer - 格式化工具执行结果以在终端显示
 *
 * Maps from TypeScript components/tool-results/ rendering components.
 * Handles collapsing, truncation, syntax highlighting hints,
 * and diff display for file edit results.
 * 映射自 TypeScript components/tool-results/ 渲染组件。
 * 处理折叠、截断、语法高亮提示和文件编辑结果的 diff 显示。
 */
class ToolResultRenderer(
    private val maxOutputLines: Int = MAX_LINES,
    private val maxOutputChars: Int = MAX_CHARS,
    private val colorEnabled: Boolean = true
) {
    private val ansi = if (colorEnabled) AnsiColors.ENABLED else AnsiColors.DISABLED

    /**
     * Render a tool execution result to terminal string
     * 将工具执行结果渲染为终端字符串
     */
    fun render(result: ToolExecutionResult): String {
        val sb = StringBuilder()
        val duration = if (result.durationMs > 0) " (${formatDuration(result.durationMs)})" else ""

        if (result.isError) {
            sb.appendLine("${ansi.red}${ansi.bold}${result.toolName}${ansi.reset} ${ansi.dim}failed${duration}${ansi.reset}")
            result.error?.let { error ->
                sb.appendLine("${ansi.red}  ${truncate(error)}${ansi.reset}")
            }
        } else {
            sb.appendLine("${ansi.green}${ansi.bold}${result.toolName}${ansi.reset}${ansi.dim}${duration}${ansi.reset}")
            result.output?.let { output ->
                val rendered = renderOutput(result.toolName, output)
                sb.append(rendered)
            }
        }
        return sb.toString()
    }

    /**
     * Render a collapsed (summary) view of the result
     * 渲染结果的折叠（摘要）视图
     */
    fun renderCollapsed(result: ToolExecutionResult): String {
        val icon = if (result.isError) "${ansi.red}x${ansi.reset}" else "${ansi.green}+${ansi.reset}"
        val duration = if (result.durationMs > 0) " ${ansi.dim}${formatDuration(result.durationMs)}${ansi.reset}" else ""
        return "$icon ${result.toolName}$duration"
    }

    /**
     * Render output based on tool type for specialized formatting
     * 根据工具类型渲染输出以进行专门格式化
     */
    private fun renderOutput(toolName: String, output: String): String {
        return when {
            toolName.contains("Bash", ignoreCase = true) -> renderBashOutput(output)
            toolName.contains("FileRead", ignoreCase = true) -> renderFileOutput(output)
            toolName.contains("FileEdit", ignoreCase = true) -> renderDiffOutput(output)
            toolName.contains("Grep", ignoreCase = true) -> renderGrepOutput(output)
            toolName.contains("Glob", ignoreCase = true) -> renderFileListOutput(output)
            else -> renderGenericOutput(output)
        }
    }

    private fun renderBashOutput(output: String): String {
        val lines = output.lines()
        val truncated = truncateLines(lines)
        return truncated.joinToString("\n") { "  ${ansi.dim}|${ansi.reset} $it" } + "\n"
    }

    private fun renderFileOutput(output: String): String {
        val lines = output.lines()
        val truncated = truncateLines(lines)
        return truncated.joinToString("\n") { line ->
            if (line.matches(Regex("^\\s*\\d+[\\t|].*"))) {
                "  ${ansi.dim}${line}${ansi.reset}"
            } else {
                "  $line"
            }
        } + "\n"
    }

    private fun renderDiffOutput(output: String): String {
        val lines = output.lines()
        return lines.take(maxOutputLines).joinToString("\n") { line ->
            when {
                line.startsWith("+") -> "  ${ansi.green}${line}${ansi.reset}"
                line.startsWith("-") -> "  ${ansi.red}${line}${ansi.reset}"
                line.startsWith("@") -> "  ${ansi.cyan}${line}${ansi.reset}"
                else -> "  $line"
            }
        } + "\n"
    }

    private fun renderGrepOutput(output: String): String {
        val lines = output.lines()
        val truncated = truncateLines(lines)
        return truncated.joinToString("\n") { line ->
            // Highlight file:line format / 高亮 file:line 格式
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0 && colonIdx < 200) {
                val file = line.substring(0, colonIdx)
                val rest = line.substring(colonIdx)
                "  ${ansi.cyan}${file}${ansi.reset}${rest}"
            } else {
                "  $line"
            }
        } + "\n"
    }

    private fun renderFileListOutput(output: String): String {
        val lines = output.lines()
        val truncated = truncateLines(lines)
        return truncated.joinToString("\n") { "  ${ansi.dim}${it}${ansi.reset}" } + "\n"
    }

    private fun renderGenericOutput(output: String): String {
        val truncated = truncate(output)
        return truncated.lines().joinToString("\n") { "  $it" } + "\n"
    }

    private fun truncateLines(lines: List<String>): List<String> {
        return if (lines.size > maxOutputLines) {
            lines.take(maxOutputLines) + listOf("${ansi.dim}... (${lines.size - maxOutputLines} more lines)${ansi.reset}")
        } else {
            lines
        }
    }

    private fun truncate(text: String): String {
        return if (text.length > maxOutputChars) {
            text.take(maxOutputChars) + "...(truncated)"
        } else {
            text
        }
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${"%.1f".format(ms / 1000.0)}s"
        else -> "${"%.1f".format(ms / 60_000.0)}m"
    }

    companion object {
        const val MAX_LINES = 50
        const val MAX_CHARS = 5000
    }
}

/**
 * ANSI color codes for terminal output
 * 终端输出的 ANSI 颜色代码
 */
data class AnsiColors(
    val red: String = "\u001b[31m",
    val green: String = "\u001b[32m",
    val yellow: String = "\u001b[33m",
    val blue: String = "\u001b[34m",
    val cyan: String = "\u001b[36m",
    val bold: String = "\u001b[1m",
    val dim: String = "\u001b[2m",
    val reset: String = "\u001b[0m"
) {
    companion object {
        val ENABLED = AnsiColors()
        val DISABLED = AnsiColors(red="", green="", yellow="", blue="", cyan="", bold="", dim="", reset="")
    }
}
