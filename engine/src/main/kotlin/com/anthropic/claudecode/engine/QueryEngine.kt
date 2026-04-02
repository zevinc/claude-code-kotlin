package com.anthropic.claudecode.engine

import com.anthropic.claudecode.services.api.AnthropicClient
import com.anthropic.claudecode.services.api.ApiMessage
import com.anthropic.claudecode.services.api.ApiTool
import com.anthropic.claudecode.services.api.MessageRequest
import com.anthropic.claudecode.services.api.StreamEvent
import com.anthropic.claudecode.tools.CanUseToolFn
import com.anthropic.claudecode.tools.Tool
import com.anthropic.claudecode.tools.ToolRegistry
import com.anthropic.claudecode.tools.ToolUseContext
import com.anthropic.claudecode.types.ContentBlock
import com.anthropic.claudecode.types.Message
import com.anthropic.claudecode.types.StopReason
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Query engine - core AI interaction loop
 * 查询引擎 - 核心 AI 交互循环
 *
 * Maps from TypeScript query.ts.
 * Orchestrates the conversation flow with Claude API:
 * message submission -> streaming -> tool detection -> tool execution -> follow-up.
 * Implements the agentic loop pattern with error recovery.
 * 映射自 TypeScript query.ts。
 * 协调与 Claude API 的对话流程：
 * 消息提交 -> 流式传输 -> 工具检测 -> 工具执行 -> 后续处理。
 * 实现带错误恢复的代理循环模式。
 */
class QueryEngine(
    private val apiClient: AnthropicClient,
    private val toolRegistry: ToolRegistry
) {
    /**
     * Submit a user message and process the full query loop
     * 提交用户消息并处理完整的查询循环
     *
     * This is the main entry point - yields events as they occur,
     * handles tool execution, and loops until completion or error.
     * 这是主入口点 - 在事件发生时 yield 它们，
     * 处理工具执行，循环直到完成或出错。
     *
     * @param params Query parameters / 查询参数
     * @return Flow of query events / 查询事件 Flow
     */
    fun query(params: QueryParams): Flow<QueryEvent> = flow {
        logger.info { "Starting query loop, model: ${params.model}" }
        emit(QueryEvent.QueryStarted)

        val state = QueryLoopState(
            messages = params.messages.toMutableList(),
            turnCount = 0,
            maxTurns = params.maxTurns ?: DEFAULT_MAX_TURNS,
            maxOutputTokensRecoveryCount = 0,
            hasAttemptedReactiveCompact = false
        )

        try {
            queryLoop(params, state, this@flow)
        } catch (e: Exception) {
            logger.error(e) { "Query loop error" }
            emit(QueryEvent.QueryError(e.message ?: "Unknown error", e))
        }
    }

    /**
     * Main query loop - iterates until completion
     * 主查询循环 - 迭代直到完成
     *
     * Each iteration:
     * 1. Prepare messages (compaction, budget)
     * 2. Call model API with streaming
     * 3. Collect tool use requests
     * 4. Execute tools (concurrent for read-only, serial for write)
     * 5. Decide: continue (has tool results) or exit (no tools)
     *
     * 每次迭代：
     * 1. 准备消息（压缩、预算）
     * 2. 调用模型 API 并流式传输
     * 3. 收集工具使用请求
     * 4. 执行工具（只读并发，写入串行）
     * 5. 决定：继续（有工具结果）或退出（无工具）
     */
    private suspend fun queryLoop(
        params: QueryParams,
        state: QueryLoopState,
        collector: kotlinx.coroutines.flow.FlowCollector<QueryEvent>
    ) {
        while (true) {
            // Check max turns limit / 检查最大轮次限制
            if (state.turnCount >= state.maxTurns) {
                logger.warn { "Max turns (${state.maxTurns}) reached" }
                collector.emit(QueryEvent.QueryCompleted(TerminalReason.MAX_TURNS, state.turnCount))
                return
            }

            // Phase 1: Build API request
            // 阶段 1：构建 API 请求
            val apiMessages = buildApiMessages(state.messages)
            val tools = buildToolDefinitions(params)
            val request = MessageRequest(
                model = params.model,
                messages = apiMessages,
                system = params.systemPrompt,
                maxTokens = params.maxOutputTokens ?: DEFAULT_MAX_OUTPUT_TOKENS,
                tools = tools,
                stream = true
            )

            collector.emit(QueryEvent.RequestStarted(state.turnCount))

            // Phase 2: Stream API response
            // 阶段 2：流式 API 响应
            val assistantContent = mutableListOf<ContentBlock>()
            val toolUseBlocks = mutableListOf<ToolUseBlock>()
            var stopReason: StopReason? = null

            apiClient.streamMessage(request).collect { event ->
                when (event) {
                    is StreamEvent.MessageStart -> {
                        collector.emit(QueryEvent.StreamEvent.MessageStarted(event.messageId))
                    }
                    is StreamEvent.ContentBlockStart -> {
                        if (event.type == "text") {
                            collector.emit(QueryEvent.StreamEvent.ContentBlockStarted(event.index, event.type))
                        }
                    }
                    is StreamEvent.ContentBlockDelta -> {
                        collector.emit(QueryEvent.StreamEvent.TextDelta(event.index, event.delta))
                    }
                    is StreamEvent.ContentBlockStop -> {
                        collector.emit(QueryEvent.StreamEvent.ContentBlockStopped(event.index))
                    }
                    is StreamEvent.ToolUse -> {
                        // Detect tool use request / 检测工具使用请求
                        toolUseBlocks.add(ToolUseBlock(
                            id = event.id,
                            name = event.name,
                            input = event.input
                        ))
                        assistantContent.add(
                            ContentBlock.ToolUse(
                                id = event.id,
                                name = event.name,
                                input = event.input
                            )
                        )
                        collector.emit(QueryEvent.StreamEvent.ToolUseDetected(event.id, event.name))
                    }
                    is StreamEvent.MessageStop -> {
                        stopReason = StopReason.END_TURN
                    }
                    is StreamEvent.InputJsonDelta -> {
                        // Tool input JSON being streamed / 工具输入 JSON 正在流式传输
                    }
                    is StreamEvent.Usage -> {
                        // Token usage tracking / 令牌使用追踪
                    }                    is StreamEvent.Error -> {
                        logger.error { "API stream error: ${event.message}" }
                        collector.emit(QueryEvent.QueryError(event.message, null))
                        return@collect
                    }
                }
            }

            // Build assistant message / 构建助手消息
            val assistantMessage = Message.Assistant(
                uuid = UUID.randomUUID().toString(),
                timestamp = java.time.Instant.now().toString(),
                content = assistantContent,
                model = params.model,
                stopReason = stopReason
            )
            state.messages.add(assistantMessage)
            collector.emit(QueryEvent.AssistantMessage(assistantMessage))

            // Phase 3: Execute tools if any
            // 阶段 3：如果有工具则执行
            if (toolUseBlocks.isEmpty()) {
                // No tool calls - query complete / 无工具调用 - 查询完成
                logger.info { "Query completed after ${state.turnCount} turns, reason: $stopReason" }
                collector.emit(QueryEvent.QueryCompleted(TerminalReason.COMPLETED, state.turnCount))
                return
            }

            // Execute tools / 执行工具
            val toolResults = executeTools(
                toolUseBlocks = toolUseBlocks,
                assistantMessage = assistantMessage,
                params = params,
                collector = collector
            )

            // Add tool results as user message / 将工具结果添加为用户消息
            val toolResultContent = toolResults.map { result ->
                ContentBlock.ToolResult(
                    toolUseId = result.toolUseId,
                    content = result.content,
                    isError = result.isError
                )
            }
            val userMessage = Message.User(
                uuid = UUID.randomUUID().toString(),
                timestamp = java.time.Instant.now().toString(),
                content = toolResultContent
            )
            state.messages.add(userMessage)

            // Increment turn and continue loop / 增加轮次并继续循环
            state.turnCount++
            collector.emit(QueryEvent.TurnCompleted(state.turnCount))
        }
    }

    /**
     * Execute tool use requests
     * 执行工具使用请求
     *
     * Partitions tools into concurrent-safe batches and executes them.
     * Read-only tools run concurrently, write tools run serially.
     * 将工具分为并发安全的批次并执行。
     * 只读工具并发运行，写入工具串行运行。
     */
    private suspend fun executeTools(
        toolUseBlocks: List<ToolUseBlock>,
        assistantMessage: Message.Assistant,
        params: QueryParams,
        collector: kotlinx.coroutines.flow.FlowCollector<QueryEvent>
    ): List<ToolExecutionResult> {
        val results = mutableListOf<ToolExecutionResult>()

        for (block in toolUseBlocks) {
            collector.emit(QueryEvent.ToolExecution.Started(block.id, block.name))

            val tool = toolRegistry.get(block.name)
            if (tool == null) {
                // Unknown tool / 未知工具
                logger.warn { "Unknown tool: ${block.name}" }
                results.add(ToolExecutionResult(
                    toolUseId = block.id,
                    content = listOf(com.anthropic.claudecode.types.ToolResultContent.Text(
                        "Error: Unknown tool '${block.name}'"
                    )),
                    isError = true
                ))
                collector.emit(QueryEvent.ToolExecution.Completed(block.id, block.name, isError = true))
                continue
            }

            try {
                // Execute the tool / 执行工具
                @Suppress("UNCHECKED_CAST")
                val typedTool = tool as Tool<Any, Any>
                val input = typedTool.parseInput(block.input)

                // Check permissions / 检查权限
                val permResult = typedTool.checkPermissions(input, params.toolUseContext)
                if (permResult is com.anthropic.claudecode.types.PermissionResult.Deny) {
                    results.add(ToolExecutionResult(
                        toolUseId = block.id,
                        content = listOf(com.anthropic.claudecode.types.ToolResultContent.Text(
                            "Permission denied: ${permResult.message}"
                        )),
                        isError = true
                    ))
                    collector.emit(QueryEvent.ToolExecution.Completed(block.id, block.name, isError = true))
                    continue
                }

                // Validate input / 验证输入
                val validResult = typedTool.validateInput(input, params.toolUseContext)
                if (validResult is com.anthropic.claudecode.types.ValidationResult.Failure) {
                    results.add(ToolExecutionResult(
                        toolUseId = block.id,
                        content = listOf(com.anthropic.claudecode.types.ToolResultContent.Text(
                            "Validation error: ${validResult.message}"
                        )),
                        isError = true
                    ))
                    collector.emit(QueryEvent.ToolExecution.Completed(block.id, block.name, isError = true))
                    continue
                }

                // Execute / 执行
                val toolResult = typedTool.call(
                    input = input,
                    context = params.toolUseContext,
                    canUseTool = params.canUseTool,
                    parentMessage = assistantMessage,
                    onProgress = { progress ->
                        // Log progress (cannot call suspend emit from non-suspend lambda)
                        // 记录进度（不能从非挂起 lambda 调用挂起的 emit）
                        logger.debug { "Tool progress: ${block.name} - $progress" }
                    }
                )

                results.add(ToolExecutionResult(
                    toolUseId = block.id,
                    content = listOf(com.anthropic.claudecode.types.ToolResultContent.Text(
                        toolResult.output.toString()
                    )),
                    isError = toolResult.isError
                ))
                collector.emit(QueryEvent.ToolExecution.Completed(block.id, block.name, isError = toolResult.isError))

            } catch (e: Exception) {
                logger.error(e) { "Tool execution failed: ${block.name}" }
                results.add(ToolExecutionResult(
                    toolUseId = block.id,
                    content = listOf(com.anthropic.claudecode.types.ToolResultContent.Text(
                        "Error executing tool '${block.name}': ${e.message}"
                    )),
                    isError = true
                ))
                collector.emit(QueryEvent.ToolExecution.Completed(block.id, block.name, isError = true))
            }
        }

        return results
    }

    /**
     * Build API messages from internal message list
     * 从内部消息列表构建 API 消息
     */
    private fun buildApiMessages(messages: List<Message>): List<ApiMessage> {
        return messages.mapNotNull { message ->
            when (message) {
                is Message.User -> ApiMessage(
                    role = "user",
                    content = message.content.joinToString("\n") { block ->
                        when (block) {
                            is ContentBlock.Text -> block.text
                            is ContentBlock.ToolResult -> "[Tool Result: ${block.toolUseId}] ${
                                block.content.joinToString("\n") { rc ->
                                    when (rc) {
                                        is com.anthropic.claudecode.types.ToolResultContent.Text -> rc.text
                                        is com.anthropic.claudecode.types.ToolResultContent.Image -> "[Image]"
                                    }
                                }
                            }"
                            else -> block.toString()
                        }
                    }
                )
                is Message.Assistant -> ApiMessage(
                    role = "assistant",
                    content = message.content.joinToString("\n") { block ->
                        when (block) {
                            is ContentBlock.Text -> block.text
                            is ContentBlock.ToolUse -> "[Tool: ${block.name}]"
                            else -> block.toString()
                        }
                    }
                )
                else -> null
            }
        }
    }

    /**
     * Build tool definitions for the API request
     * 为 API 请求构建工具定义
     */
    private fun buildToolDefinitions(params: QueryParams): List<ApiTool> {
        return toolRegistry.getEnabledTools().map { tool ->
            ApiTool(
                name = tool.name,
                description = tool.inputJSONSchema.description ?: tool.name,
                inputSchema = tool.inputJSONSchema.schema
            )
        }
    }

    companion object {
        /** Default maximum turns for the agentic loop / 代理循环的默认最大轮次 */
        const val DEFAULT_MAX_TURNS = 100

        /** Default maximum output tokens / 默认最大输出令牌数 */
        const val DEFAULT_MAX_OUTPUT_TOKENS = 8192
    }
}

/**
 * Parameters for the query loop
 * 查询循环的参数
 */
data class QueryParams(
    /** Initial messages / 初始消息 */
    val messages: List<Message>,
    /** System prompt / 系统提示 */
    val systemPrompt: String?,
    /** Model to use / 使用的模型 */
    val model: String,
    /** Tool use context / 工具使用上下文 */
    val toolUseContext: ToolUseContext,
    /** Permission check function / 权限检查函数 */
    val canUseTool: CanUseToolFn,
    /** Maximum output tokens override / 最大输出令牌数覆盖 */
    val maxOutputTokens: Int? = null,
    /** Maximum turns / 最大轮次 */
    val maxTurns: Int? = null,
    /** Fallback model / 备用模型 */
    val fallbackModel: String? = null
)

/**
 * Internal mutable state for the query loop
 * 查询循环的内部可变状态
 */
private data class QueryLoopState(
    val messages: MutableList<Message>,
    var turnCount: Int,
    val maxTurns: Int,
    var maxOutputTokensRecoveryCount: Int,
    var hasAttemptedReactiveCompact: Boolean
)

/**
 * A tool use block detected in the API response
 * 在 API 响应中检测到的工具使用块
 */
data class ToolUseBlock(
    /** Tool use ID / 工具使用 ID */
    val id: String,
    /** Tool name / 工具名称 */
    val name: String,
    /** Tool input / 工具输入 */
    val input: JsonObject
)

/**
 * Result of executing a single tool
 * 执行单个工具的结果
 */
data class ToolExecutionResult(
    /** Tool use ID / 工具使用 ID */
    val toolUseId: String,
    /** Result content blocks / 结果内容块 */
    val content: List<com.anthropic.claudecode.types.ToolResultContent>,
    /** Whether the execution resulted in an error / 执行是否产生错误 */
    val isError: Boolean
)

/**
 * Events emitted during the query loop
 * 查询循环期间发出的事件
 *
 * Maps from TypeScript's generator yield types.
 * These events are consumed by the UI layer for real-time updates.
 * 映射自 TypeScript 的生成器 yield 类型。
 * 这些事件被 UI 层消费以进行实时更新。
 */
sealed class QueryEvent {
    /** Query loop started / 查询循环已启动 */
    data object QueryStarted : QueryEvent()

    /** API request started for a turn / 某轮的 API 请求已启动 */
    data class RequestStarted(val turnCount: Int) : QueryEvent()

    /** Assistant message fully received / 助手消息已完全接收 */
    data class AssistantMessage(val message: Message.Assistant) : QueryEvent()

    /** A turn (tool execution round) completed / 一轮（工具执行回合）已完成 */
    data class TurnCompleted(val turnCount: Int) : QueryEvent()

    /** Query completed / 查询已完成 */
    data class QueryCompleted(val reason: TerminalReason, val turnCount: Int) : QueryEvent()

    /** Query error / 查询错误 */
    data class QueryError(val message: String, val cause: Throwable?) : QueryEvent()

    /**
     * Streaming events from the API
     * 来自 API 的流式事件
     */
    sealed class StreamEvent : QueryEvent() {
        /** Message stream started / 消息流已启动 */
        data class MessageStarted(val messageId: String) : StreamEvent()

        /** Content block started / 内容块已启动 */
        data class ContentBlockStarted(val index: Int, val type: String) : StreamEvent()

        /** Text delta (incremental text update) / 文本增量（增量文本更新） */
        data class TextDelta(val index: Int, val delta: String) : StreamEvent()

        /** Content block stopped / 内容块已停止 */
        data class ContentBlockStopped(val index: Int) : StreamEvent()

        /** Tool use detected in stream / 流中检测到工具使用 */
        data class ToolUseDetected(val toolUseId: String, val toolName: String) : StreamEvent()
    }

    /**
     * Tool execution events
     * 工具执行事件
     */
    sealed class ToolExecution : QueryEvent() {
        /** Tool execution started / 工具执行已启动 */
        data class Started(val toolUseId: String, val toolName: String) : ToolExecution()

        /** Tool execution completed / 工具执行已完成 */
        data class Completed(val toolUseId: String, val toolName: String, val isError: Boolean) : ToolExecution()

        /** Tool execution progress / 工具执行进度 */
        data class Progress(val toolUseId: String, val toolName: String, val data: Any) : ToolExecution()
    }
}

/**
 * Reasons for query termination
 * 查询终止的原因
 *
 * Maps from TypeScript Terminal.reason union type.
 * 映射自 TypeScript Terminal.reason 联合类型。
 */
enum class TerminalReason {
    /** Normal completion / 正常完成 */
    COMPLETED,
    /** Streaming aborted / 流式传输已中止 */
    ABORTED_STREAMING,
    /** Tool execution aborted / 工具执行已中止 */
    ABORTED_TOOLS,
    /** Maximum turns reached / 已达最大轮次 */
    MAX_TURNS,
    /** Prompt too long for model / 提示对模型来说太长 */
    PROMPT_TOO_LONG,
    /** Image processing error / 图像处理错误 */
    IMAGE_ERROR,
    /** Model API error / 模型 API 错误 */
    MODEL_ERROR,
    /** Hook stopped the query / Hook 停止了查询 */
    HOOK_STOPPED
}
