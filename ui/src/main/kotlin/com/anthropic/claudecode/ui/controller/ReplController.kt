package com.anthropic.claudecode.ui.controller

import com.anthropic.claudecode.commands.CommandRegistry
import com.anthropic.claudecode.engine.conversation.ConversationManager
import com.anthropic.claudecode.services.session.SessionManager
import com.anthropic.claudecode.services.session.appendMessage
import com.anthropic.claudecode.tools.ToolRegistry
import com.anthropic.claudecode.types.*
import com.anthropic.claudecode.ui.components.*
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.graphics.TextGraphics
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private val logger = KotlinLogging.logger {}

/**
 * ReplController - orchestrates the REPL interaction loop
 * ReplController - 协调 REPL 交互循环
 *
 * This is the integration bridge between:
 * - UI (App/ReplScreen) for rendering and input
 * - ConversationManager for API communication
 * - CommandRegistry for slash command handling
 * - SessionManager for persistence
 *
 * 这是以下组件之间的集成桥梁：
 * - UI (App/ReplScreen) 用于渲染和输入
 * - ConversationManager 用于 API 通信
 * - CommandRegistry 用于斜杠命令处理
 * - SessionManager 用于持久化
 */
class ReplController(
    private val conversationManager: ConversationManager,
    private val commandRegistry: CommandRegistry,
    private val sessionManager: SessionManager,
    private val toolRegistry: ToolRegistry,
    private val model: String,
    private val scope: CoroutineScope,
    private val sessionId: String? = null
) : Screen {

    private val replScreen = ReplScreen()
    private val inputHistory = mutableListOf<String>()
    private var historyIndex = -1
    private var currentQueryJob: Job? = null

    /** Current REPL state / 当前 REPL 状态 */
    private val _state = MutableStateFlow(ReplScreenState(model = model))
    val state: StateFlow<ReplScreenState> = _state.asStateFlow()

    /** Messages in the conversation / 对话中的消息 */
    private val messages = mutableListOf<Message>()

    override fun render(graphics: TextGraphics, size: TerminalSize) {
        replScreen.render(graphics, size, _state.value)
    }

    override fun onKeyStroke(keyStroke: KeyStroke) {
        when (keyStroke.keyType) {
            KeyType.Enter -> handleSubmit()
            KeyType.Backspace -> handleBackspace()
            KeyType.ArrowUp -> handleHistoryUp()
            KeyType.ArrowDown -> handleHistoryDown()
            KeyType.Escape -> handleEscape()
            KeyType.Character -> handleCharacter(keyStroke.character)
            else -> {}
        }
    }

    /**
     * Process initial prompt if provided / 如果提供了初始提示则处理
     */
    fun processInitialPrompt(prompt: String) {
        scope.launch {
            submitQuery(prompt)
        }
    }

    private fun handleCharacter(char: Char) {
        if (_state.value.isQueryInProgress) return
        _state.update { it.copy(
            inputBuffer = it.inputBuffer + char,
            cursorPosition = it.cursorPosition + 1
        )}
    }

    private fun handleBackspace() {
        if (_state.value.isQueryInProgress) return
        val current = _state.value.inputBuffer
        if (current.isNotEmpty()) {
            _state.update { it.copy(
                inputBuffer = current.dropLast(1),
                cursorPosition = (it.cursorPosition - 1).coerceAtLeast(0)
            )}
        }
    }

    private fun handleHistoryUp() {
        if (inputHistory.isEmpty()) return
        historyIndex = (historyIndex + 1).coerceAtMost(inputHistory.size - 1)
        val historyItem = inputHistory[inputHistory.size - 1 - historyIndex]
        _state.update { it.copy(inputBuffer = historyItem, cursorPosition = historyItem.length) }
    }

    private fun handleHistoryDown() {
        if (historyIndex <= 0) {
            historyIndex = -1
            _state.update { it.copy(inputBuffer = "", cursorPosition = 0) }
            return
        }
        historyIndex--
        val historyItem = inputHistory[inputHistory.size - 1 - historyIndex]
        _state.update { it.copy(inputBuffer = historyItem, cursorPosition = historyItem.length) }
    }

    private fun handleEscape() {
        // Cancel current query / 取消当前查询
        if (_state.value.isQueryInProgress) {
            currentQueryJob?.cancel()
            _state.update { it.copy(
                isQueryInProgress = false,
                isStreaming = false,
                statusText = "Query cancelled"
            )}
        }
    }

    private fun handleSubmit() {
        val input = _state.value.inputBuffer.trim()
        if (input.isEmpty() || _state.value.isQueryInProgress) return

        // Save to history / 保存到历史
        inputHistory.add(input)
        historyIndex = -1

        // Clear input / 清除输入
        _state.update { it.copy(inputBuffer = "", cursorPosition = 0) }

        // Check for slash commands / 检查斜杠命令
        if (input.startsWith("/")) {
            handleSlashCommand(input)
            return
        }

        // Submit query / 提交查询
        scope.launch {
            submitQuery(input)
        }
    }

    /**
     * Handle slash command input / 处理斜杠命令输入
     */
    private fun handleSlashCommand(input: String) {
        val parts = input.removePrefix("/").split(" ", limit = 2)
        val commandName = parts[0]
        val args = parts.getOrNull(1) ?: ""

        val command = commandRegistry.findCommand(commandName)
        if (command == null) {
            addSystemMessage("Unknown command: /$commandName. Type /help for available commands.")
            return
        }

        scope.launch {
            try {
                val context = com.anthropic.claudecode.commands.CommandContext(
                    commandRegistry = commandRegistry,
                    sessionStats = com.anthropic.claudecode.commands.SessionStats(),
                    currentModel = model
                )
                val result = command.execute(args, context)
                addSystemMessage(result.output)
            } catch (e: Exception) {
                addSystemMessage("Command error: ${e.message}")
            }
        }
    }

    /**
     * Submit a user query to ConversationManager
     * 向 ConversationManager 提交用户查询
     */
    private suspend fun submitQuery(input: String) {
        // Add user message / 添加用户消息
        val userMsg = Message.User(
            uuid = "user-${System.currentTimeMillis()}",
            timestamp = java.time.Instant.now().toString(),
            content = listOf(ContentBlock.Text(input))
        )
        messages.add(userMsg)

        _state.update { it.copy(
            messages = messages.toList(),
            isQueryInProgress = true,
            isStreaming = true,
            streamingText = "",
            statusText = "Thinking..."
        )}

        // Persist user message / 持久化用户消息
        sessionId?.let { id ->
            try { sessionManager.appendMessage(id, userMsg) } catch (_: Exception) {}
        }

        currentQueryJob = scope.launch {
            try {
                val turnResult = conversationManager.sendMessage(input)

                // Build assistant message / 构建助手消息
                val assistantMsg = Message.Assistant(
                    uuid = "asst-${System.currentTimeMillis()}",
                    timestamp = java.time.Instant.now().toString(),
                    content = listOf(ContentBlock.Text(turnResult.text))
                )
                messages.add(assistantMsg)

                // Persist assistant message / 持久化助手消息
                sessionId?.let { id ->
                    try { sessionManager.appendMessage(id, assistantMsg) } catch (_: Exception) {}
                }

                _state.update { it.copy(
                    messages = messages.toList(),
                    isQueryInProgress = false,
                    isStreaming = false,
                    streamingText = "",
                    statusText = "Ready"
                )}
            } catch (e: CancellationException) {
                _state.update { it.copy(
                    isQueryInProgress = false,
                    isStreaming = false,
                    statusText = "Cancelled"
                )}
            } catch (e: Exception) {
                logger.error(e) { "Query failed" }
                addSystemMessage("Error: ${e.message}")
                _state.update { it.copy(
                    isQueryInProgress = false,
                    isStreaming = false,
                    statusText = "Error"
                )}
            }
        }
    }

    private fun addSystemMessage(text: String) {
        messages.add(Message.System(
            uuid = "sys-${System.currentTimeMillis()}",
            timestamp = java.time.Instant.now().toString(),
            content = text
        ))
        _state.update { it.copy(messages = messages.toList()) }
    }
}
