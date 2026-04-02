package com.anthropic.claudecode.ui.render

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * MarkdownRenderer - renders markdown content for terminal display
 * MarkdownRenderer - 在终端中渲染 Markdown 内容
 *
 * Maps from TypeScript components/Markdown.tsx.
 * Supports: headings, bold/italic, code blocks, inline code,
 * lists, links, horizontal rules, and blockquotes.
 * 映射自 TypeScript components/Markdown.tsx。
 * 支持：标题、粗体/斜体、代码块、行内代码、
 * 列表、链接、水平线和块引用。
 */
class MarkdownRenderer(
    private val colorEnabled: Boolean = true,
    private val terminalWidth: Int = 80
) {
    private val esc = if (colorEnabled) "\u001b[" else ""
    private val reset = if (colorEnabled) "\u001b[0m" else ""

    /**
     * Render markdown text to ANSI-colored terminal output
     * 将 Markdown 文本渲染为 ANSI 彩色终端输出
     */
    fun render(markdown: String): String {
        val lines = markdown.lines()
        val result = StringBuilder()
        var inCodeBlock = false
        var codeBlockLang = ""

        for (line in lines) {
            // Code block toggle / 代码块切换
            if (line.trimStart().startsWith("```")) {
                if (!inCodeBlock) {
                    codeBlockLang = line.trimStart().removePrefix("```").trim()
                    inCodeBlock = true
                    result.appendLine("${esc}2m${esc}36m--- ${codeBlockLang.ifBlank { "code" }} ---${reset}")
                } else {
                    inCodeBlock = false
                    result.appendLine("${esc}2m${esc}36m---${reset}")
                }
                continue
            }

            if (inCodeBlock) {
                result.appendLine("${esc}36m  $line${reset}")
                continue
            }

            result.appendLine(renderLine(line))
        }

        return result.toString()
    }

    private fun renderLine(line: String): String {
        val trimmed = line.trimStart()

        // Headings / 标题
        if (trimmed.startsWith("# ")) return "${esc}1m${esc}4m${trimmed.removePrefix("# ")}${reset}"
        if (trimmed.startsWith("## ")) return "${esc}1m${trimmed.removePrefix("## ")}${reset}"
        if (trimmed.startsWith("### ")) return "${esc}1m${esc}2m${trimmed.removePrefix("### ")}${reset}"

        // Horizontal rule / 水平线
        if (trimmed.matches(Regex("^-{3,}$")) || trimmed.matches(Regex("^\\*{3,}$"))) {
            return "${esc}2m${"─".repeat((terminalWidth * 0.6).toInt())}${reset}"
        }

        // Blockquote / 块引用
        if (trimmed.startsWith("> ")) {
            return "${esc}2m│${reset} ${renderInline(trimmed.removePrefix("> "))}"
        }

        // Unordered list / 无序列表
        if (trimmed.matches(Regex("^[-*+]\\s.*"))) {
            val indent = line.length - trimmed.length
            val content = trimmed.substring(2)
            return " ".repeat(indent) + "  • ${renderInline(content)}"
        }

        // Ordered list / 有序列表
        val olMatch = Regex("^(\\d+)\\.\\s(.*)").find(trimmed)
        if (olMatch != null) {
            val indent = line.length - trimmed.length
            val num = olMatch.groupValues[1]
            val content = olMatch.groupValues[2]
            return " ".repeat(indent) + "  $num. ${renderInline(content)}"
        }

        return renderInline(line)
    }

    /**
     * Render inline markdown formatting
     * 渲染行内 Markdown 格式
     */
    fun renderInline(text: String): String {
        if (!colorEnabled) return text

        var result = text

        // Inline code / 行内代码
        result = result.replace(Regex("`([^`]+)`")) { m ->
            "${esc}36m${m.groupValues[1]}${reset}"
        }

        // Bold / 粗体
        result = result.replace(Regex("\\*\\*([^*]+)\\*\\*")) { m ->
            "${esc}1m${m.groupValues[1]}${reset}"
        }

        // Italic / 斜体
        result = result.replace(Regex("(?<![*])\\*([^*]+)\\*(?![*])")) { m ->
            "${esc}3m${m.groupValues[1]}${reset}"
        }

        // Links [text](url) / 链接
        result = result.replace(Regex("\\[([^]]+)]\\(([^)]+)\\)")) { m ->
            "${esc}4m${esc}34m${m.groupValues[1]}${reset} ${esc}2m(${m.groupValues[2]})${reset}"
        }

        // Strikethrough / 删除线
        result = result.replace(Regex("~~([^~]+)~~")) { m ->
            "${esc}9m${m.groupValues[1]}${reset}"
        }

        return result
    }
}
