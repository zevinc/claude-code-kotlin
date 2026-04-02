package com.anthropic.claudecode.engine.agent

import com.anthropic.claudecode.engine.execution.ToolExecutor
import com.anthropic.claudecode.engine.execution.ToolExecutionResult
import com.anthropic.claudecode.services.api.*
import com.anthropic.claudecode.tools.ToolRegistry
import com.anthropic.claudecode.tools.ToolUseContext
import com.anthropic.claudecode.tools.ToolUseOptions
import com.anthropic.claudecode.tools.AppState
import com.anthropic.claudecode.tools.CanUseToolFn
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * AgentRunner - manages subagent lifecycle with multi-turn tool use
 * AgentRunner - 管理具有多轮工具使用的子代理生命周期
 *
 * Maps from TypeScript services/agent.
 * Each subagent runs with isolated message history, its own tool subset,
 * and can iterate through tool-use loops up to MAX_AGENT_ITERATIONS.
 * 映射自 TypeScript services/agent。
 * 每个子代理运行在隔离的消息历史中，拥有自己的工具子集，
 * 并可以迭代工具使用循环最多 MAX_AGENT_ITERATIONS 次。
 */
class AgentRunner(
    private val apiClient: AnthropicClient,
    private val parentToolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor? = null,
    private val model: String = "claude-sonnet-4-20250514"
) {
    private val runningAgents = ConcurrentHashMap<String, AgentState>()
    private val agentCounter = AtomicInteger(0)

    /**
     * Spawn a new subagent with multi-turn tool use support
     * 生成支持多轮工具使用的新子代理
     */
    suspend fun spawn(
        prompt: String,
        agentType: String = "general-purpose",
        parentMessages: List<Message> = emptyList(),
        onProgress: ((AgentProgress) -> Unit)? = null
    ): AgentResult {
        val agentId = "agent-${agentCounter.incrementAndGet()}-${System.currentTimeMillis()}"
        logger.info { "Spawning agent $agentId (type=$agentType)" }

        val state = AgentState(agentId = agentId, agentType = agentType, status = AgentStatus.RUNNING)
        runningAgents[agentId] = state

        return try {
            onProgress?.invoke(AgentProgress(agentId, "spawning", "Initializing agent..."))

            val systemPrompt = buildAgentSystemPrompt(agentType, prompt)
            val agentTools = getToolsForAgentType(agentType)
            val agentToolDefs = agentTools.map { tool ->
                ApiTool(
                    name = tool.name,
                    description = tool.inputJSONSchema.description ?: "",
                    inputSchema = tool.inputJSONSchema.schema
                )
            }

            // Build initial messages / 构建初始消息
            val messages = mutableListOf(ApiMessage(role = "user", content = prompt))

            onProgress?.invoke(AgentProgress(agentId, "running", "Processing..."))

            var finalText = ""
            var iteration = 0
            val allToolResults = mutableListOf<ToolExecutionResult>()

            // Multi-turn tool use loop / 多轮工具使用循环
            while (iteration < MAX_AGENT_ITERATIONS) {
                iteration++
                onProgress?.invoke(AgentProgress(agentId, "iteration_$iteration",
                    "Agent iteration $iteration/$MAX_AGENT_ITERATIONS"))

                val request = MessageRequest(
                    model = model,
                    messages = messages.toList(),
                    system = systemPrompt,
                    maxTokens = 16000,
                    tools = if (agentToolDefs.isNotEmpty()) agentToolDefs else null,
                    stream = false
                )

                val response = apiClient.sendMessage(request)

                // Parse response content blocks / 解析响应内容块
                val textParts = mutableListOf<String>()
                val toolUseBlocks = mutableListOf<AgentToolUseBlock>()

                for (block in response.content) {
                    val blockType = block["type"]?.toString()?.removeSurrounding("\"") ?: ""
                    when (blockType) {
                        "text" -> {
                            val text = block["text"]?.toString()?.removeSurrounding("\"") ?: ""
                            textParts.add(text)
                        }
                        "tool_use" -> {
                            val toolId = block["id"]?.toString()?.removeSurrounding("\"") ?: ""
                            val toolName = block["name"]?.toString()?.removeSurrounding("\"") ?: ""
                            val toolInput = block["input"] as? JsonObject ?: JsonObject(emptyMap())
                            toolUseBlocks.add(AgentToolUseBlock(toolId, toolName, toolInput))
                        }
                    }
                }

                val responseText = textParts.joinToString("\n")
                messages.add(ApiMessage(role = "assistant", content = responseText))

                // If no tool use, conversation is done / 如果没有工具使用，对话结束
                if (toolUseBlocks.isEmpty()) {
                    finalText = responseText
                    break
                }

                // Execute tools / 执行工具
                val toolResultParts = mutableListOf<String>()

                for (toolBlock in toolUseBlocks) {
                    logger.info { "Agent $agentId executing tool: ${toolBlock.name}" }
                    onProgress?.invoke(AgentProgress(agentId, "tool_use",
                        "Using tool: ${toolBlock.name}"))

                    val result = executeAgentTool(agentId, toolBlock)
                    allToolResults.add(result)

                    val resultText = if (result.isError) {
                        "Error: ${result.error}"
                    } else {
                        result.output ?: "Tool executed successfully"
                    }
                    toolResultParts.add("[${toolBlock.name}]: $resultText")
                }

                // Add tool results as user message for next iteration
                // 将工具结果作为用户消息添加到下一次迭代
                messages.add(ApiMessage(role = "user",
                    content = toolResultParts.joinToString("\n\n")))

                finalText = responseText
            }

            state.status = AgentStatus.COMPLETED
            onProgress?.invoke(AgentProgress(agentId, "completed",
                "Completed in $iteration iteration(s)"))

            AgentResult(
                agentId = agentId,
                agentType = agentType,
                status = "completed",
                result = finalText.ifEmpty { "Agent completed with no text output" },
                iterations = iteration,
                toolResults = allToolResults.size
            )
        } catch (e: CancellationException) {
            state.status = AgentStatus.CANCELLED
            AgentResult(agentId = agentId, agentType = agentType,
                status = "cancelled", error = "Cancelled")
        } catch (e: Exception) {
            logger.error(e) { "Agent $agentId failed" }
            state.status = AgentStatus.FAILED
            AgentResult(agentId = agentId, agentType = agentType,
                status = "failed", error = e.message)
        } finally {
            runningAgents.remove(agentId)
        }
    }

    /**
     * Execute a tool within agent context / 在代理上下文中执行工具
     */
    private suspend fun executeAgentTool(
        agentId: String,
        toolBlock: AgentToolUseBlock
    ): ToolExecutionResult {
        if (toolExecutor == null) {
            return ToolExecutionResult(
                toolName = toolBlock.name,
                output = "Tool execution not available (no ToolExecutor configured)",
                isError = true,
                error = "No ToolExecutor"
            )
        }

        val context = ToolUseContext(
            options = ToolUseOptions(
                tools = parentToolRegistry.tools,
                mainLoopModel = model
            ),
            job = Job(),
            getAppState = { AppState() },
            setAppState = {},
            messages = emptyList(),
            agentId = AgentId(agentId),
            agentType = "subagent"
        )

        val parentMessage = Message.Assistant(
            uuid = "agent-msg-${System.currentTimeMillis()}",
            timestamp = java.time.Instant.now().toString(),
            content = listOf(ContentBlock.Text("Agent tool use"))
        )

        val canUseTool: CanUseToolFn = { _, _ -> PermissionResult.Allow() }

        return toolExecutor.execute(
            toolName = toolBlock.name,
            rawInput = toolBlock.input,
            context = context,
            canUseTool = canUseTool,
            parentMessage = parentMessage
        )
    }

    /** Cancel a running agent / 取消运行中的代理 */
    fun cancel(agentId: String): Boolean {
        val state = runningAgents[agentId] ?: return false
        state.status = AgentStatus.CANCELLED
        return true
    }

    /** Get running agent count / 获取运行中的代理数量 */
    fun getRunningCount(): Int = runningAgents.size

    private fun getToolsForAgentType(agentType: String): List<com.anthropic.claudecode.tools.Tool<*, *>> {
        val allTools = parentToolRegistry.tools
        val allowedToolNames = when (agentType) {
            "explore-agent" -> setOf("Read", "FileRead", "Glob", "GlobTool", "Grep", "GrepTool", "Bash")
            "code-reviewer" -> setOf("Read", "FileRead", "Glob", "GlobTool", "Grep", "GrepTool", "Bash", "WebSearch", "WebFetch")
            "browser-agent" -> setOf("Read", "FileRead", "Glob", "GlobTool", "Grep", "GrepTool", "WebFetch", "WebSearch")
            "plan-agent" -> setOf("Read", "FileRead", "Glob", "GlobTool", "Grep", "GrepTool", "Bash")
            else -> allTools.map { it.name }.toSet()
        }
        return allTools.filter { it.name in allowedToolNames }
    }

    private fun buildAgentSystemPrompt(agentType: String, taskPrompt: String): String {
        val roleDescription = when (agentType) {
            "explore-agent" -> "You are a fast codebase exploration agent. Find files, search code, and answer questions quickly. Use tools to explore."
            "code-reviewer" -> "You are a code review agent. Review changes for correctness, security, performance, and test impact. Use tools to read code."
            "plan-agent" -> "You are a software architect agent. Design implementation plans with step-by-step approaches. Use tools to explore the codebase."
            "browser-agent" -> "You are a browser automation agent. Navigate websites, fill forms, and extract information."
            "design-agent" -> "You are a design agent. Handle requirements gathering, design documentation, and task breakdown."
            else -> "You are a general-purpose coding agent. Handle complex multi-step tasks autonomously. Use tools as needed."
        }

        return """$roleDescription

Task: $taskPrompt

Work autonomously to complete the task. Use tools to gather information and make changes.
Return a concise result when done."""
    }

    companion object {
        const val MAX_AGENT_ITERATIONS = 15
    }
}

private data class AgentToolUseBlock(
    val id: String,
    val name: String,
    val input: JsonObject
)

data class AgentState(
    val agentId: String,
    val agentType: String,
    var status: AgentStatus = AgentStatus.PENDING
)

enum class AgentStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

@Serializable
data class AgentProgress(
    val agentId: String,
    val status: String,
    val message: String
)

@Serializable
data class AgentResult(
    val agentId: String,
    val agentType: String,
    val status: String = "pending",
    val result: String? = null,
    val error: String? = null,
    val iterations: Int = 0,
    val toolResults: Int = 0
)
