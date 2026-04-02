package com.anthropic.claudecode.commands.impl

import com.anthropic.claudecode.commands.Command
import com.anthropic.claudecode.commands.CommandContext
import com.anthropic.claudecode.commands.CommandResult

/**
 * /review command - code review via AI
 * /review 命令 - 通过 AI 进行代码审查
 */
class ReviewCommand : Command {
    override val name = "review"
    override val description = "Review code changes using AI analysis / 使用 AI 分析审查代码变更"
    override val aliases = listOf("cr")

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        return CommandResult(
            output = "Starting code review... Use 'git diff' to see pending changes, then ask for a review."
        )
    }
}

/**
 * /diff command - show current git diff
 * /diff 命令 - 显示当前 git diff
 */
class DiffCommand : Command {
    override val name = "diff"
    override val description = "Show current git diff / 显示当前 git 差异"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        return try {
            val cmd = if (args.isBlank()) listOf("git", "diff") else listOf("git", "diff") + args.split(" ")
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            CommandResult(output = if (output.isBlank()) "No changes." else output.take(8000))
        } catch (e: Exception) {
            CommandResult(output = "Failed: ${e.message}")
        }
    }
}

/**
 * /status command - show git status
 * /status 命令 - 显示 git 状态
 */
class StatusCommand : Command {
    override val name = "status"
    override val description = "Show git status / 显示 git 状态"
    override val aliases = listOf("st")

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        return try {
            val process = ProcessBuilder("git", "status", "--short").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            CommandResult(output = if (output.isBlank()) "Working tree clean." else output)
        } catch (e: Exception) {
            CommandResult(output = "Failed: ${e.message}")
        }
    }
}

/**
 * /permissions command - show permission settings
 * /permissions 命令 - 显示权限设置
 */
class PermissionsCommand : Command {
    override val name = "permissions"
    override val description = "View current permission settings / 查看当前权限设置"
    override val aliases = listOf("perms")

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        val sb = StringBuilder()
        sb.appendLine("Permission Settings / 权限设置:")
        sb.appendLine("  (Use settings.json or CLI flags to configure)")
        sb.appendLine("  Modes: default, acceptEdits, bypassPermissions, plan")
        return CommandResult(output = sb.toString())
    }
}

/**
 * /config command - show configuration
 * /config 命令 - 显示配置
 */
class ConfigCommand : Command {
    override val name = "config"
    override val description = "Show or edit configuration / 显示或编辑配置"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        val configDir = System.getenv("CLAUDE_CONFIG_DIR")
            ?: "${System.getProperty("user.home")}/.claude"
        val sb = StringBuilder()
        sb.appendLine("Configuration / 配置:")
        sb.appendLine("  Config dir: $configDir")
        sb.appendLine("  Settings: $configDir/settings.json")
        sb.appendLine("  User memory: $configDir/CLAUDE.md")
        sb.appendLine()
        sb.appendLine("  Project: ${System.getProperty("user.dir")}/.claude/")
        return CommandResult(output = sb.toString())
    }
}

/**
 * /exit command - exit the session
 * /exit 命令 - 退出会话
 */
class ExitCommand : Command {
    override val name = "exit"
    override val description = "Exit the session / 退出会话"
    override val aliases = listOf("quit", "q")

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        return CommandResult(output = "Goodbye!", shouldExit = true)
    }
}

/**
 * /theme command - switch color theme
 * /theme 命令 - 切换颜色主题
 */
class ThemeCommand : Command {
    override val name = "theme"
    override val description = "Switch color theme / 切换颜色主题"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        val themes = listOf("dark", "light", "dracula", "solarized", "monokai")
        if (args.isBlank()) {
            return CommandResult(output = "Available themes: ${themes.joinToString(", ")}")
        }
        val target = args.trim().lowercase()
        return if (target in themes) {
            CommandResult(output = "Theme switched to: $target")
        } else {
            CommandResult(output = "Unknown theme: $target. Available: ${themes.joinToString(", ")}")
        }
    }
}
