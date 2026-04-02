package com.anthropic.claudecode.ui.console

import com.anthropic.claudecode.commands.CommandContext
import com.anthropic.claudecode.commands.CommandRegistry
import com.anthropic.claudecode.commands.SessionStats
import com.anthropic.claudecode.engine.conversation.ConversationManager
import com.anthropic.claudecode.services.session.SessionManager
import com.anthropic.claudecode.services.session.appendMessage
import com.anthropic.claudecode.tools.ToolRegistry
import com.anthropic.claudecode.types.ContentBlock
import com.anthropic.claudecode.types.Message
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.io.BufferedReader
import java.io.InputStreamReader

private val logger = KotlinLogging.logger {}

/**
 * Simple console-based REPL that uses stdin/stdout directly
 * 基于控制台的简单 REPL，直接使用 stdin/stdout
 *
 * This is the primary REPL mode, working in any terminal environment
 * without requiring /dev/tty or Lanterna screen mode.
 * 这是主要的 REPL 模式，在任何终端环境中都可以工作，
 * 不需要 /dev/tty 或 Lanterna 屏幕模式。
 */
class ConsoleRepl(
    private val conversationManager: ConversationManager,
    private val commandRegistry: CommandRegistry,
    private val sessionManager: SessionManager,
    private val toolRegistry: ToolRegistry,
    private val model: String,
    private val sessionId: String? = null
) {
    private val messages = mutableListOf<Message>()
    private val reader = BufferedReader(InputStreamReader(System.`in`))

    // ANSI color codes
    // ANSI 颜色代码
    companion object {
        private const val RESET = "\u001B[0m"
        private const val BOLD = "\u001B[1m"
        private const val DIM = "\u001B[2m"
        private const val CYAN = "\u001B[36m"
        private const val GREEN = "\u001B[32m"
        private const val YELLOW = "\u001B[33m"
        private const val RED = "\u001B[31m"
        private const val BLUE = "\u001B[34m"
        private const val MAGENTA = "\u001B[35m"
    }

    /**
     * Run the REPL loop / 运行 REPL 循环
     */
    suspend fun run(initialPrompt: String? = null) {
        printWelcome()

        // Handle initial prompt if provided / 如果提供了初始提示则处理
        if (initialPrompt != null) {
            println("${DIM}> $initialPrompt${RESET}")
            handleQuery(initialPrompt)
        }

        // Main REPL loop / 主 REPL 循环
        while (true) {
            print("${BOLD}${CYAN}> ${RESET}")
            System.out.flush()

            val input = try {
                reader.readLine()
            } catch (e: Exception) {
                null
            }

            // EOF (Ctrl+D) / 文件结束
            if (input == null) {
                println("\n${DIM}Goodbye!${RESET}")
                break
            }

            val trimmed = input.trim()
            if (trimmed.isEmpty()) continue

            // Handle exit commands / 处理退出命令
            if (trimmed == "/exit" || trimmed == "/quit") {
                println("${DIM}Goodbye!${RESET}")
                break
            }

            // Handle slash commands / 处理斜杠命令
            if (trimmed.startsWith("/")) {
                handleSlashCommand(trimmed)
                continue
            }

            // Submit query / 提交查询
            handleQuery(trimmed)
        }
    }

    private fun printWelcome() {
        println()
        println("${BOLD}${CYAN}╭─────────────────────────────────────╮${RESET}")
        println("${BOLD}${CYAN}│  Claude Code ${DIM}(Kotlin)${RESET}${BOLD}${CYAN}               │${RESET}")
        println("${BOLD}${CYAN}│  ${RESET}${DIM}Model: $model${RESET}${BOLD}${CYAN}  │${RESET}")
        println("${BOLD}${CYAN}╰─────────────────────────────────────╯${RESET}")
        println()
        println("${DIM}Type your message, or /help for commands. Ctrl+D to exit.${RESET}")
        println()
    }

    private suspend fun handleSlashCommand(input: String) {
        val parts = input.removePrefix("/").split(" ", limit = 2)
        val commandName = parts[0]
        val args = parts.getOrNull(1) ?: ""

        val command = commandRegistry.findCommand(commandName)
        if (command == null) {
            println("${RED}Unknown command: /$commandName${RESET}")
            println("${DIM}Type /help for available commands.${RESET}")
            return
        }

        try {
            val context = CommandContext(
                commandRegistry = commandRegistry,
                sessionStats = SessionStats(),
                currentModel = model
            )
            val result = command.execute(args, context)
            println(result.output)
        } catch (e: Exception) {
            println("${RED}Command error: ${e.message}${RESET}")
            logger.error(e) { "Slash command failed: /$commandName" }
        }
    }

    private suspend fun handleQuery(input: String) {
        // Add user message / 添加用户消息
        val userMsg = Message.User(
            uuid = "user-${System.currentTimeMillis()}",
            timestamp = java.time.Instant.now().toString(),
            content = listOf(ContentBlock.Text(input))
        )
        messages.add(userMsg)

        // Persist user message / 持久化用户消息
        sessionId?.let { id ->
            try {
                sessionManager.appendMessage(id, userMsg)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to persist user message" }
            }
        }

        // Show thinking indicator / 显示思考指示器
        print("${DIM}Thinking...${RESET}")
        System.out.flush()

        try {
            val turnResult = conversationManager.sendMessage(input)

            // Clear thinking indicator / 清除思考指示器
            print("\r${" ".repeat(20)}\r")

            // Print assistant response / 打印助手回复
            println()
            println("${BOLD}${GREEN}Assistant:${RESET}")
            println(turnResult.text)
            println()

            // Show tool results summary if any / 显示工具结果摘要
            if (turnResult.toolResults.isNotEmpty()) {
                println("${DIM}[${turnResult.toolResults.size} tool(s) used, ${turnResult.iterations} iteration(s)]${RESET}")
            }

            // Show cost if available / 显示成本
            if (turnResult.costUsd > 0) {
                println("${DIM}[Cost: $${String.format("%.4f", turnResult.costUsd)}]${RESET}")
            }
            println()

            // Build and persist assistant message / 构建并持久化助手消息
            val assistantMsg = Message.Assistant(
                uuid = "asst-${System.currentTimeMillis()}",
                timestamp = java.time.Instant.now().toString(),
                content = listOf(ContentBlock.Text(turnResult.text))
            )
            messages.add(assistantMsg)

            sessionId?.let { id ->
                try {
                    sessionManager.appendMessage(id, assistantMsg)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to persist assistant message" }
                }
            }

        } catch (e: CancellationException) {
            print("\r${" ".repeat(20)}\r")
            println("${YELLOW}Query cancelled.${RESET}")
        } catch (e: Exception) {
            print("\r${" ".repeat(20)}\r")
            println("${RED}Error: ${e.message}${RESET}")
            logger.error(e) { "Query failed" }
        }
    }
}
