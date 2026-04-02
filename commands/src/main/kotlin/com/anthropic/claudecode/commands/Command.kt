package com.anthropic.claudecode.commands

/**
 * Slash command interface - base contract for all slash commands
 * 斜杠命令接口 - 所有斜杠命令的基础契约
 *
 * Maps from TypeScript types/command.ts.
 * 映射自 TypeScript types/command.ts。
 */
interface Command {
    /** Command name (without slash) / 命令名称（不含斜杠） */
    val name: String

    /** Human-readable description / 人类可读的描述 */
    val description: String

    /** Alternative names / 别名 */
    val aliases: List<String>
        get() = emptyList()

    /** Whether this command is hidden from help / 是否在帮助中隐藏 */
    val isHidden: Boolean
        get() = false

    /** Execute the command / 执行命令 */
    suspend fun execute(args: String, context: CommandContext): CommandResult
}

/**
 * Command execution context / 命令执行上下文
 */
data class CommandContext(
    /** Command registry for /help / 用于 /help 的命令注册表 */
    val commandRegistry: CommandRegistryRef,
    /** Current session statistics / 当前会话统计 */
    val sessionStats: SessionStats = SessionStats(),
    /** Current model name / 当前模型名称 */
    val currentModel: String = "claude-sonnet-4-20250514"
)

/**
 * Interface to access command registry (avoids circular dependency)
 * 访问命令注册表的接口（避免循环依赖）
 */
interface CommandRegistryRef {
    fun getAllCommands(): List<Command>
}

/**
 * Session statistics for /cost command
 * /cost 命令的会话统计
 */
data class SessionStats(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheCreateTokens: Long = 0,
    val totalCostUsd: Double = 0.0,
    val apiCalls: Int = 0
)

/**
 * Command execution result / 命令执行结果
 */
data class CommandResult(
    /** Output to display / 要显示的输出 */
    val output: String = "",
    /** Whether to clear conversation history / 是否清除对话历史 */
    val shouldClearHistory: Boolean = false,
    /** Whether to compact conversation / 是否压缩对话 */
    val shouldCompact: Boolean = false,
    /** Custom instruction for compaction / 压缩的自定义指令 */
    val compactInstruction: String? = null,
    /** Model to switch to / 要切换到的模型 */
    val switchModel: String? = null,
    /** Whether to exit the session / 是否退出会话 */
    val shouldExit: Boolean = false
)
