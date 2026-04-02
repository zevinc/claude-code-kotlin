package com.anthropic.claudecode.engine.prompt

import com.anthropic.claudecode.utils.config.ClaudeMdParser
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * SystemPromptBuilder - constructs system prompts from configuration
 * SystemPromptBuilder - 从配置构建系统提示
 *
 * Maps from TypeScript services/claude/systemPrompt.ts.
 * Merges base system prompt with CLAUDE.md project instructions,
 * tool descriptions, and permission rules.
 * 映射自 TypeScript services/claude/systemPrompt.ts。
 * 将基础系统提示与 CLAUDE.md 项目指令、工具描述和权限规则合并。
 */
class SystemPromptBuilder(
    private val model: String = "claude-sonnet-4-20250514",
    private val claudeMdConfig: ClaudeMdParser.ClaudeMdConfig? = null,
    private val customInstructions: String? = null
) {
    /**
     * Build the full system prompt / 构建完整的系统提示
     */
    fun build(): String {
        val parts = mutableListOf<String>()

        // Base identity / 基础身份
        parts.add(BASE_PROMPT)

        // CLAUDE.md project instructions / CLAUDE.md 项目指令
        claudeMdConfig?.let { config ->
            if (config.sections.isNotEmpty()) {
                parts.add("\n# Project Instructions")
                for (section in config.sections) {
                    parts.add("## From ${section.sourcePath}")
                    parts.add(section.content)
                }
            }
        }

        // Custom user instructions / 自定义用户指令
        customInstructions?.let {
            parts.add("\n# Custom Instructions")
            parts.add(it)
        }

        // Environment info / 环境信息
        parts.add(buildEnvironmentSection())

        val prompt = parts.joinToString("\n\n")
        logger.debug { "System prompt built: ${prompt.length} chars" }
        return prompt
    }

    private fun buildEnvironmentSection(): String {
        return buildString {
            appendLine("\n# Environment")
            appendLine("- Platform: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
            appendLine("- Working directory: ${System.getProperty("user.dir")}")
            appendLine("- Home: ${System.getProperty("user.home")}")
            appendLine("- Model: $model")
            appendLine("- Today: ${java.time.LocalDate.now()}")
        }
    }

    companion object {
        val BASE_PROMPT = """You are Claude, an AI assistant by Anthropic, operating as a coding agent in the user's terminal.
You help with software engineering tasks: writing code, debugging, refactoring, explaining code, running tests, and more.

Key behaviors:
- Always read files before modifying them
- Use tools to gather information before making changes
- Be concise in responses
- Follow existing code patterns and style
- Run tests after making changes when possible
- Never commit changes unless explicitly asked"""
    }
}
