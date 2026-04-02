package com.anthropic.claudecode.commands.impl

import com.anthropic.claudecode.commands.Command
import com.anthropic.claudecode.commands.CommandContext
import com.anthropic.claudecode.commands.CommandResult

/**
 * /help command - displays available commands and usage information
 * /help 命令 - 显示可用命令和使用信息
 */
class HelpCommand : Command {
    override val name = "help"
    override val description = "Show available commands and usage information / 显示可用命令和使用信息"
    override val aliases = listOf("?", "h")

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        val commands = context.commandRegistry.getAllCommands().sortedBy { it.name }
        val sb = StringBuilder()
        sb.appendLine("Available commands / 可用命令:")
        sb.appendLine()
        for (cmd in commands) {
            val aliasStr = if (cmd.aliases.isNotEmpty()) " (${cmd.aliases.joinToString(", ") { "/$it" }})" else ""
            sb.appendLine("  /${cmd.name}$aliasStr - ${cmd.description}")
        }
        sb.appendLine()
        sb.appendLine("Type /help <command> for detailed help on a specific command.")
        return CommandResult(output = sb.toString())
    }
}

/**
 * /clear command - clears conversation history
 * /clear 命令 - 清除对话历史
 */
class ClearCommand : Command {
    override val name = "clear"
    override val description = "Clear conversation history / 清除对话历史"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        // Signal to the engine to clear messages / 发信号给引擎清除消息
        return CommandResult(
            output = "Conversation history cleared. / 对话历史已清除。",
            shouldClearHistory = true
        )
    }
}

/**
 * /cost command - shows current session cost
 * /cost 命令 - 显示当前会话费用
 */
class CostCommand : Command {
    override val name = "cost"
    override val description = "Show current session cost and token usage / 显示当前会话费用和令牌使用量"
    override val aliases = listOf("usage")

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        val stats = context.sessionStats
        val sb = StringBuilder()
        sb.appendLine("Session Cost / 会话费用:")
        sb.appendLine("  Input tokens:  ${stats.inputTokens}")
        sb.appendLine("  Output tokens: ${stats.outputTokens}")
        sb.appendLine("  Cache read:    ${stats.cacheReadTokens}")
        sb.appendLine("  Cache create:  ${stats.cacheCreateTokens}")
        sb.appendLine("  Total cost:    $${String.format("%.4f", stats.totalCostUsd)}")
        sb.appendLine("  API calls:     ${stats.apiCalls}")
        return CommandResult(output = sb.toString())
    }
}

/**
 * /compact command - compacts conversation history
 * /compact 命令 - 压缩对话历史
 */
class CompactCommand : Command {
    override val name = "compact"
    override val description = "Compact conversation history to reduce context size / 压缩对话历史以减少上下文大小"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        return CommandResult(
            output = "Compacting conversation history... / 正在压缩对话历史...",
            shouldCompact = true,
            compactInstruction = args.ifBlank { null }
        )
    }
}

/**
 * /init command - initializes project configuration
 * /init 命令 - 初始化项目配置
 */
class InitCommand : Command {
    override val name = "init"
    override val description = "Initialize CLAUDE.md for this project / 为此项目初始化 CLAUDE.md"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        val projectDir = System.getProperty("user.dir")
        val claudeMd = java.io.File("$projectDir/CLAUDE.md")

        if (claudeMd.exists()) {
            return CommandResult(output = "CLAUDE.md already exists in this project. Use /memory to edit it.")
        }

        val template = """
            |# Project Instructions
            |
            |## Overview
            |Describe your project here.
            |
            |## Development
            |- Build: `./gradlew build`
            |- Test: `./gradlew test`
            |- Lint: `./gradlew lint`
            |
            |## Code Style
            |Describe coding conventions here.
        """.trimMargin()

        claudeMd.writeText(template)
        return CommandResult(output = "Created CLAUDE.md in $projectDir. Edit it to customize project instructions.")
    }
}

/**
 * /doctor command - diagnostic checks
 * /doctor 命令 - 诊断检查
 */
class DoctorCommand : Command {
    override val name = "doctor"
    override val description = "Run diagnostic checks / 运行诊断检查"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        val sb = StringBuilder()
        sb.appendLine("Doctor / 诊断检查:")
        sb.appendLine()

        // Check API key / 检查 API 密钥
        val apiKey = System.getenv("ANTHROPIC_API_KEY")
        sb.appendLine("  API Key: ${if (apiKey != null) "✓ Set" else "✗ Not set"}")

        // Check git / 检查 git
        val gitOk = try {
            ProcessBuilder("git", "--version").start().waitFor() == 0
        } catch (_: Exception) { false }
        sb.appendLine("  Git: ${if (gitOk) "✓ Available" else "✗ Not found"}")

        // Check ripgrep / 检查 ripgrep
        val rgOk = try {
            ProcessBuilder("rg", "--version").start().waitFor() == 0
        } catch (_: Exception) { false }
        sb.appendLine("  Ripgrep: ${if (rgOk) "✓ Available" else "⚠ Not found (grep fallback)"}")

        // Check CLAUDE.md / 检查 CLAUDE.md
        val claudeMd = java.io.File("${System.getProperty("user.dir")}/CLAUDE.md")
        sb.appendLine("  CLAUDE.md: ${if (claudeMd.exists()) "✓ Found" else "⚠ Not found (use /init)"}")

        // Check settings / 检查设置
        val settingsFile = java.io.File("${System.getProperty("user.home")}/.claude/settings.json")
        sb.appendLine("  Settings: ${if (settingsFile.exists()) "✓ Found" else "⚠ Not found"}")

        sb.appendLine()
        sb.appendLine("All checks complete. / 所有检查完成。")
        return CommandResult(output = sb.toString())
    }
}

/**
 * /memory command - view/edit CLAUDE.md
 * /memory 命令 - 查看/编辑 CLAUDE.md
 */
class MemoryCommand : Command {
    override val name = "memory"
    override val description = "View or edit CLAUDE.md project memory / 查看或编辑 CLAUDE.md 项目记忆"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        val projectDir = System.getProperty("user.dir")
        val claudeMd = java.io.File("$projectDir/CLAUDE.md")

        if (!claudeMd.exists()) {
            return CommandResult(output = "No CLAUDE.md found. Use /init to create one.")
        }

        val content = claudeMd.readText()
        return CommandResult(
            output = "CLAUDE.md contents (${content.length} chars):\n\n$content"
        )
    }
}

/**
 * /model command - view/switch model
 * /model 命令 - 查看/切换模型
 */
class ModelCommand : Command {
    override val name = "model"
    override val description = "View or switch the current model / 查看或切换当前模型"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        if (args.isBlank()) {
            return CommandResult(output = "Current model: ${context.currentModel}")
        }

        val availableModels = listOf(
            "claude-sonnet-4-20250514",
            "claude-opus-4-20250514",
            "claude-haiku-3-5-20241022"
        )

        val target = args.trim()
        val matched = availableModels.find { it.contains(target, ignoreCase = true) }

        return if (matched != null) {
            CommandResult(
                output = "Switching to model: $matched",
                switchModel = matched
            )
        } else {
            CommandResult(
                output = "Unknown model: $target\nAvailable: ${availableModels.joinToString(", ")}"
            )
        }
    }
}
