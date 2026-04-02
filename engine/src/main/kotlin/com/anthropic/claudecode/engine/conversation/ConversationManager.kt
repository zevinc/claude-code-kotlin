package com.anthropic.claudecode.engine.conversation

import com.anthropic.claudecode.engine.execution.ToolExecutor
import com.anthropic.claudecode.engine.execution.ToolExecutionResult
import com.anthropic.claudecode.engine.stream.CompletedBlock
import com.anthropic.claudecode.engine.stream.StreamHandler
import com.anthropic.claudecode.engine.stream.StreamOutput
import com.anthropic.claudecode.services.api.*
import com.anthropic.claudecode.services.hooks.HookEngine
import com.anthropic.claudecode.services.hooks.HookEvent
import com.anthropic.claudecode.tools.ToolRegistry
import com.anthropic.claudecode.tools.ToolUseContext
import com.anthropic.claudecode.tools.CanUseToolFn
import com.anthropic.claudecode.types.Message
import com.anthropic.claudecode.types.PermissionResult
import com.anthropic.claudecode.utils.tokens.TokenCounter
import com.anthropic.claudecode.utils.tokens.CostCalculator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val logger = KotlinLogging.logger {}

/**
 * ConversationManager - orchestrates the full conversation loop
 * ConversationManager - 协调完整的对话循环
 *
 * Maps from TypeScript services/conversation/conversationManager.ts.
 * Manages: message history, API calls, stream processing,
 * tool execution, and the iterative tool-use loop.
 * 映射自 TypeScript services/conversation/conversationManager.ts。
 * 管理：消息历史、API 调用、流处理、工具执行和迭代工具使用循环。
 */
class ConversationManager(
    private val apiClient: AnthropicClient,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val hookEngine: HookEngine? = null,
    private val model: String = "claude-sonnet-4-20250514",
    private val systemPrompt: String? = null,
    private val maxToolUseIterations: Int = MAX_ITERATIONS
) {
    private val messages = mutableListOf<ApiMessage>()
    private val streamHandler = StreamHandler()
    val tokenCounter = TokenCounter()

    /**
     * Send a user message and process the full response cycle
     * 发送用户消息并处理完整的响应周期
     *
     * This implements the core conversation loop:
     * 1. Add user message to history
     * 2. Send to API with streaming
     * 3. Process stream into content blocks
     * 4. If tool_use blocks present, execute tools and loop
     * 5. Return final text response
     *
     * 实现核心对话循环：
     * 1. 将用户消息添加到历史
     * 2. 以流式发送到 API
     * 3. 将流处理为内容块
     * 4. 如果存在 tool_use 块，执行工具并循环
     * 5. 返回最终文本响应
     *
     * @param userMessage The user's input text / 用户的输入文本
     * @param onTextDelta Callback for streaming text / 流式文本的回调
     * @param onToolUse Callback when a tool is being used / 工具被使用时的回调
     * @return The final conversation turn result / 最终的对话轮次结果
     */
    suspend fun sendMessage(
        userMessage: String,
        onTextDelta: ((String) -> Unit)? = null,
        onToolUse: ((String, String) -> Unit)? = null
    ): ConversationTurnResult {
        // Execute user prompt hooks / 执行用户提示钩子
        hookEngine?.execute(HookEvent.USER_PROMPT_SUBMIT, buildJsonObject {
            put("message", userMessage)
        })

        messages.add(ApiMessage(role = "user", content = userMessage))

        var iteration = 0
        val allToolResults = mutableListOf<ToolExecutionResult>()
        var finalText = ""

        while (iteration < maxToolUseIterations) {
            iteration++

            // Build API request / 构建 API 请求
            val request = MessageRequest(
                model = model,
                messages = messages.toList(),
                system = systemPrompt,
                tools = buildToolList(),
                stream = true
            )

            // Stream and collect response / 流式收集响应
            val blocks = mutableListOf<CompletedBlock>()
            var streamError: String? = null

            streamHandler.process(apiClient.streamMessage(request))
                .onEach { output ->
                    when (output) {
                        is StreamOutput.TextDelta -> {
                            onTextDelta?.invoke(output.text)
                        }
                        is StreamOutput.ToolUseStart -> {
                            onToolUse?.invoke(output.toolName, output.toolUseId)
                        }
                        is StreamOutput.Completed -> {
                            blocks.addAll(output.blocks)
                            tokenCounter.recordUsage(outputTokens = output.outputTokens)
                        }
                        is StreamOutput.StreamError -> {
                            streamError = output.message
                        }
                        else -> {} // Other events handled internally
                    }
                }
                .collect()

            if (streamError != null) {
                return ConversationTurnResult(
                    text = "",
                    error = streamError,
                    toolResults = allToolResults,
                    iterations = iteration
                )
            }

            // Collect text and tool uses / 收集文本和工具使用
            val textBlocks = blocks.filterIsInstance<CompletedBlock.Text>()
            val toolUseBlocks = blocks.filterIsInstance<CompletedBlock.ToolUse>()

            val responseText = textBlocks.joinToString("") { it.text }

            // Add assistant message to history / 将助手消息添加到历史
            messages.add(ApiMessage(role = "assistant", content = responseText))

            if (toolUseBlocks.isEmpty()) {
                // No tool use - conversation turn complete / 无工具使用 - 对话轮次完成
                finalText = responseText
                break
            }

            // Execute tools / 执行工具
            val toolResultTexts = mutableListOf<String>()

            for (toolBlock in toolUseBlocks) {
                logger.info { "Executing tool: ${toolBlock.name} (${toolBlock.id})" }

                val context = createToolContext()
                val parentMessage = Message.Assistant(
                    uuid = "msg_${System.currentTimeMillis()}",
                    timestamp = java.time.Instant.now().toString(),
                    content = listOf(com.anthropic.claudecode.types.ContentBlock.Text(responseText))
                )

                val canUseTool: CanUseToolFn = { _, _ -> PermissionResult.Allow() }

                val result = toolExecutor.execute(
                    toolName = toolBlock.name,
                    rawInput = toolBlock.input,
                    context = context,
                    canUseTool = canUseTool,
                    parentMessage = parentMessage
                )

                allToolResults.add(result)

                val resultText = if (result.isError) {
                    "Error: ${result.error}"
                } else {
                    result.output ?: "Tool executed successfully"
                }
                toolResultTexts.add("[${ toolBlock.name}]: $resultText")
            }

            // Add tool results as user message for next iteration
            // 将工具结果作为用户消息添加到下一次迭代
            val toolResultMessage = toolResultTexts.joinToString("\n\n")
            messages.add(ApiMessage(role = "user", content = toolResultMessage))

            finalText = responseText
        }

        // Calculate cost / 计算成本
        val cost = CostCalculator.calculate(model, tokenCounter.getSummary())

        return ConversationTurnResult(
            text = finalText,
            toolResults = allToolResults,
            iterations = iteration,
            costUsd = cost.totalCost
        )
    }

    /**
     * Get conversation history / 获取对话历史
     */
    fun getHistory(): List<ApiMessage> = messages.toList()

    /**
     * Clear conversation history / 清除对话历史
     */
    fun clearHistory() {
        messages.clear()
    }

    /**
     * Compact conversation history (summarize older messages)
     * 压缩对话历史（总结较旧的消息）
     */
    suspend fun compact(instruction: String? = null) {
        if (messages.size <= COMPACT_THRESHOLD) return

        logger.info { "Compacting conversation: ${messages.size} messages" }

        // Keep the most recent messages, summarize the rest
        // 保留最近的消息，总结其余部分
        val keepCount = COMPACT_KEEP_RECENT
        val toSummarize = messages.take(messages.size - keepCount)
        val toKeep = messages.takeLast(keepCount)

        val summaryPrompt = instruction ?: "Summarize the previous conversation context concisely."
        val summaryRequest = MessageRequest(
            model = model,
            messages = listOf(
                ApiMessage(role = "user", content = "Previous context:\n${toSummarize.joinToString("\n") { "${it.role}: ${it.content.take(200)}" }}\n\n$summaryPrompt"),
            ),
            stream = false,
            maxTokens = 2000
        )

        try {
            val response = apiClient.sendMessage(summaryRequest)
            val summary = response.content.firstOrNull()?.toString() ?: ""

            messages.clear()
            if (summary.isNotBlank()) {
                messages.add(ApiMessage(role = "user", content = "[Context Summary] $summary"))
                messages.add(ApiMessage(role = "assistant", content = "I understand the context. How can I help?"))
            }
            messages.addAll(toKeep)

            logger.info { "Compacted to ${messages.size} messages" }
        } catch (e: Exception) {
            logger.error(e) { "Compaction failed, keeping original messages" }
        }
    }

    private fun buildToolList(): List<ApiTool> {
        return toolRegistry.getEnabledTools().map { tool ->
            ApiTool(
                name = tool.name,
                description = tool.inputJSONSchema.description ?: "",
                inputSchema = tool.inputJSONSchema.schema
            )
        }
    }

    private fun createToolContext(): ToolUseContext {
        return ToolUseContext(
            options = com.anthropic.claudecode.tools.ToolUseOptions(
                tools = toolRegistry.tools,
                mainLoopModel = model
            ),
            job = kotlinx.coroutines.Job(),
            getAppState = { com.anthropic.claudecode.tools.AppState() },
            setAppState = {},
            messages = emptyList()
        )
    }

    companion object {
        const val MAX_ITERATIONS = 25
        const val COMPACT_THRESHOLD = 20
        const val COMPACT_KEEP_RECENT = 6
    }
}

/**
 * Result of a conversation turn (may include multiple tool iterations)
 * 对话轮次的结果（可能包含多次工具迭代）
 */
data class ConversationTurnResult(
    val text: String,
    val error: String? = null,
    val toolResults: List<ToolExecutionResult> = emptyList(),
    val iterations: Int = 1,
    val costUsd: Double = 0.0
)
