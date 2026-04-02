package com.anthropic.claudecode

import com.anthropic.claudecode.cli.ConfigCommand
import com.anthropic.claudecode.cli.DoctorCommand
import com.anthropic.claudecode.cli.InitCommand
import com.anthropic.claudecode.commands.CommandRegistry
import com.anthropic.claudecode.engine.conversation.ConversationManager
import com.anthropic.claudecode.services.api.AnthropicClient
import com.anthropic.claudecode.services.api.ApiConfig
import com.anthropic.claudecode.services.api.MessageRequest
import com.anthropic.claudecode.services.api.ApiMessage
import com.anthropic.claudecode.services.api.StreamEvent
import com.anthropic.claudecode.services.auth.AuthService
import com.anthropic.claudecode.services.hooks.HookEngine
import com.anthropic.claudecode.services.session.SessionManager
import com.anthropic.claudecode.services.update.AutoUpdater
import com.anthropic.claudecode.services.TelemetryService
import com.anthropic.claudecode.engine.execution.ToolExecutor
import com.anthropic.claudecode.engine.context.ContextWindowManager
import com.anthropic.claudecode.tools.ToolRegistry
import com.anthropic.claudecode.tools.impl.*
import com.anthropic.claudecode.ui.components.App
import com.anthropic.claudecode.ui.controller.ReplController
import com.anthropic.claudecode.ui.console.ConsoleRepl
import com.anthropic.claudecode.ui.core.TerminalManager
import com.anthropic.claudecode.ui.core.TerminalTheme
import com.anthropic.claudecode.utils.config.ClaudeMdParser
import com.anthropic.claudecode.utils.config.SettingsLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*

private val logger = KotlinLogging.logger {}

/**
 * Claude Code CLI - main entry point
 * Claude Code CLI - 主入口点
 *
 * Maps from TypeScript main.tsx.
 * Uses Clikt for CLI argument parsing (replaces Commander.js).
 * Supports subcommands: doctor, init, config.
 * 映射自 TypeScript main.tsx。
 * 使用 Clikt 进行 CLI 参数解析（替代 Commander.js）。
 * 支持子命令：doctor、init、config。
 */
class ClaudeCodeCommand : CliktCommand(
    name = "claude-code",
    help = """
        Claude Code - AI-powered coding assistant in your terminal
        Claude Code - 终端中的 AI 编程助手
    """.trimIndent(),
    invokeWithoutSubcommand = true
) {
    private val prompt by argument(
        help = "Initial prompt to send to Claude / 发送给 Claude 的初始提示"
    ).optional()

    private val model by option(
        "--model", "-m",
        help = "Model to use / 使用的模型"
    ).default("claude-sonnet-4-20250514")

    private val verbose by option(
        "--verbose", "-v",
        help = "Enable verbose output / 启用详细输出"
    ).flag(default = false)

    private val debug by option(
        "--debug",
        help = "Enable debug mode / 启用调试模式"
    ).flag(default = false)

    private val print by option(
        "--print", "-p",
        help = "Print mode: non-interactive / 打印模式：非交互式"
    ).flag(default = false)

    private val resume by option(
        "--resume", "-r",
        help = "Resume the most recent session / 恢复最近的会话"
    ).flag(default = false)

    private val sessionId by option(
        "--session-id",
        help = "Specific session ID to resume / 要恢复的特定会话 ID"
    )

    private val noColor by option(
        "--no-color",
        help = "Disable colored output / 禁用彩色输出"
    ).flag(default = false)

    private val tui by option(
        "--tui",
        help = "Use full-screen TUI mode (requires real terminal) / 使用全屏 TUI 模式（需要真实终端）"
    ).flag(default = false)

    private val maxTurns by option(
        "--max-turns",
        help = "Maximum conversation turns / 最大对话轮数"
    )

    override fun run() {
        // If a subcommand was invoked, skip main logic
        // 如果调用了子命令，跳过主逻辑
        if (currentContext.invokedSubcommand != null) return

        logger.info { "Claude Code Kotlin v0.1.0 starting..." }
        logger.info { "Model: $model, Verbose: $verbose, Debug: $debug" }

        runBlocking {
            try {
                startApplication()
            } catch (e: Exception) {
                logger.error(e) { "Fatal error in Claude Code" }
                echo("Error: ${e.message}", err = true)
            }
        }
    }

    private suspend fun startApplication() {
        // Phase 1: Authentication & configuration
        // 阶段 1：认证和配置
        val authService = AuthService()
        val apiKey = authService.getApiKey() ?: ""
        if (apiKey.isBlank()) {
            logger.warn { "No API key found. Set ANTHROPIC_API_KEY or run setup." }
        }

        val projectDir = System.getProperty("user.dir")
        val apiConfig = ApiConfig(apiKey = apiKey, defaultModel = model)
        val settings = SettingsLoader.load(projectDir)

        // Phase 1.5: Load CLAUDE.md configuration
        // 阶段 1.5: 加载 CLAUDE.md 配置
        val claudeMdConfig = ClaudeMdParser.loadAll(projectDir)
        if (claudeMdConfig.sections.isNotEmpty()) {
            logger.info { "Loaded ${claudeMdConfig.sections.size} CLAUDE.md section(s)" }
        }

        // Phase 2: Initialize services
        // 阶段 2：初始化服务
        val apiClient = AnthropicClient(apiConfig)
        val toolRegistry = ToolRegistry()
        val commandRegistry = CommandRegistry()
        val hookEngine = HookEngine()
        val sessionManager = SessionManager()
        val telemetry = TelemetryService()

        // Phase 2.5: Load hooks from settings
        // 阶段 2.5：从设置加载钩子
        if (settings.hooks.isNotEmpty()) {
            val hookEntries = settings.hooks.mapValues { (_, configs) ->
                configs.map { com.anthropic.claudecode.services.hooks.HookSettingsEntry(command = it.command, timeout = it.timeout) }
            }
            hookEngine.registerFromSettings(hookEntries)
        }

        // Phase 3: Register built-in tools
        // 阶段 3：注册内置工具
        registerAllTools(toolRegistry)
        logger.info { "Registered ${toolRegistry.size} tools" }

        // Phase 4: Session & version check
        // 阶段 4：会话和版本检查
        val session = if (resume || sessionId != null) {
            if (sessionId != null) sessionManager.resumeSession(sessionId!!)
            else sessionManager.resumeLatest()
        } else {
            sessionManager.createSession()
        }
        logger.info { "Session: ${session?.id ?: "none"}" }

        val updateCheck = AutoUpdater().checkForUpdates()
        if (updateCheck.updateAvailable) {
            echo("Update available: ${updateCheck.latestVersion} (current: ${updateCheck.currentVersion})")
        }

        // Phase 4.5: Initialize conversation manager with CLAUDE.md context
        // 阶段 4.5：使用 CLAUDE.md 上下文初始化对话管理器
        val toolExecutor = ToolExecutor(toolRegistry, hookEngine)
        val conversationManager = ConversationManager(
            apiClient = apiClient,
            toolRegistry = toolRegistry,
            toolExecutor = toolExecutor,
            hookEngine = hookEngine,
            model = model
        )

        // Phase 5: Launch REPL or Print mode
        // 阶段 5：启动 REPL 或打印模式
        if (print && prompt != null) {
            runPrintMode(prompt!!, apiClient)
        } else {
            runReplMode(apiClient, toolRegistry, commandRegistry, conversationManager, sessionManager, session?.id)
        }
    }

    private suspend fun runPrintMode(initialPrompt: String, apiClient: AnthropicClient) {
        logger.info { "Running in print mode with prompt: ${initialPrompt.take(50)}..." }

        try {
            val request = MessageRequest(
                model = model,
                messages = listOf(ApiMessage(role = "user", content = initialPrompt)),
                system = "You are Claude, an AI assistant. Respond concisely.",
                maxTokens = 16000,
                stream = true
            )
            val responseFlow = apiClient.streamMessage(request)
            responseFlow.collect { event ->
                when (event) {
                    is StreamEvent.ContentBlockDelta -> {
                        print(event.delta)
                        System.out.flush()
                    }
                    is StreamEvent.Error -> {
                        System.err.println("Error: ${event.message}")
                    }
                    else -> {}
                }
            }
            println()
        } catch (e: Exception) {
            logger.error(e) { "Print mode error" }
            echo("Error: ${e.message}", err = true)
        }
    }

    private suspend fun runReplMode(
        apiClient: AnthropicClient,
        toolRegistry: ToolRegistry,
        commandRegistry: CommandRegistry,
        conversationManager: ConversationManager,
        sessionManager: SessionManager,
        activeSessionId: String?
    ) {
        if (tui) {
            // Full-screen TUI mode using Lanterna (requires /dev/tty)
            // 全屏 TUI 模式使用 Lanterna（需要 /dev/tty）
            runTuiMode(toolRegistry, commandRegistry, conversationManager, sessionManager, activeSessionId)
        } else {
            // Default: simple console REPL using stdin/stdout
            // 默认：使用 stdin/stdout 的简单控制台 REPL
            val repl = ConsoleRepl(
                conversationManager = conversationManager,
                commandRegistry = commandRegistry,
                sessionManager = sessionManager,
                toolRegistry = toolRegistry,
                model = model,
                sessionId = activeSessionId
            )
            repl.run(initialPrompt = prompt)
        }
    }

    private suspend fun runTuiMode(
        toolRegistry: ToolRegistry,
        commandRegistry: CommandRegistry,
        conversationManager: ConversationManager,
        sessionManager: SessionManager,
        activeSessionId: String?
    ) {
        coroutineScope {
            logger.info { "Starting TUI mode with Lanterna" }

            val terminalManager = TerminalManager(this)
            terminalManager.initialize()

            val app = App(
                terminalManager = terminalManager,
                scope = this,
                theme = TerminalTheme.DEFAULT
            )

            val replController = ReplController(
                conversationManager = conversationManager,
                commandRegistry = commandRegistry,
                sessionManager = sessionManager,
                toolRegistry = toolRegistry,
                model = model,
                scope = this,
                sessionId = activeSessionId
            )

            app.setActiveScreen(replController)
            app.onExit = { error ->
                if (error != null) {
                    logger.error(error) { "Application exited with error" }
                }
                terminalManager.shutdown()
            }

            if (prompt != null) {
                replController.processInitialPrompt(prompt!!)
            }

            app.start()

            try {
                awaitCancellation()
            } finally {
                terminalManager.shutdown()
            }
        }
    }
}

private fun registerAllTools(registry: ToolRegistry) {
    registry.registerAll(
        FileReadTool(), FileEditTool(), FileWriteTool(), DeleteFileTool(),
        GlobTool(), GrepTool(), ListFilesTool(),
        BashTool(), REPLTool(),
        WebFetchTool(), WebSearchTool(),
        NotebookEditTool(),
        AgentTool(), TaskCreateTool(), TodoWriteTool(),
        AskUserQuestionTool(),
        EnterPlanModeTool(), ExitPlanModeTool(),
        SkillTool(), LoadMcpTool(), ConnectSupabaseTool(),
        SleepTool(), ToolSearchTool(), CheckRuntimeTool(), ImageGenTool()
    )
}

/**
 * Application entry point / 应用程序入口点
 */
fun main(args: Array<String>) = ClaudeCodeCommand()
    .subcommands(DoctorCommand(), InitCommand(), ConfigCommand())
    .main(args)
