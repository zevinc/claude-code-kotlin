package com.anthropic.claudecode.commands

import com.anthropic.claudecode.commands.impl.*
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * CommandRegistry - manages available slash commands
 * CommandRegistry - 管理可用的斜杠命令
 *
 * Maps from TypeScript commands.ts command registration.
 * 映射自 TypeScript commands.ts 命令注册。
 */
class CommandRegistry : CommandRegistryRef {
    private val commands = mutableMapOf<String, Command>()
    private val aliasMap = mutableMapOf<String, String>()

    init {
        // Register built-in commands / 注册内置命令
        register(HelpCommand())
        register(ClearCommand())
        register(CostCommand())
        register(CompactCommand())
        register(InitCommand())
        register(DoctorCommand())
        register(MemoryCommand())
        register(ModelCommand())

        // Advanced commands / 高级命令
        register(ReviewCommand())
        register(DiffCommand())
        register(StatusCommand())
        register(PermissionsCommand())
        register(ConfigCommand())
        register(ExitCommand())
        register(ThemeCommand())
    }

    /**
     * Register a command / 注册命令
     */
    fun register(command: Command) {
        commands[command.name] = command
        for (alias in command.aliases) {
            aliasMap[alias] = command.name
        }
        logger.debug { "Registered command: /${command.name}" }
    }

    /**
     * Find a command by name or alias / 按名称或别名查找命令
     */
    fun findCommand(name: String): Command? {
        val cleanName = name.removePrefix("/").lowercase()
        return commands[cleanName] ?: commands[aliasMap[cleanName]]
    }

    /**
     * Execute a slash command / 执行斜杠命令
     */
    suspend fun executeCommand(input: String, context: CommandContext): CommandResult? {
        if (!input.startsWith("/")) return null

        val parts = input.removePrefix("/").split(" ", limit = 2)
        val cmdName = parts[0]
        val args = parts.getOrElse(1) { "" }

        val command = findCommand(cmdName)
            ?: return CommandResult(output = "Unknown command: /$cmdName. Type /help for available commands.")

        return try {
            command.execute(args, context)
        } catch (e: Exception) {
            logger.error(e) { "Command /$cmdName failed" }
            CommandResult(output = "Command failed: ${e.message}")
        }
    }

    override fun getAllCommands(): List<Command> {
        return commands.values.filter { !it.isHidden }.toList()
    }
}
