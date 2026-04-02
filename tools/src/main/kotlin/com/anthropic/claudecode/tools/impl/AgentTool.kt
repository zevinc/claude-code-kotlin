package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.services.api.AnthropicClient
import com.anthropic.claudecode.services.api.ApiMessage
import com.anthropic.claudecode.services.api.ApiTool
import com.anthropic.claudecode.services.api.MessageRequest
import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * AgentTool - spawns subagents with multi-turn tool use
 * AgentTool - 生成具有多轮工具使用的子代理
 *
 * Maps from TypeScript tools/AgentTool/AgentTool.ts.
 * Each subagent runs in its own context with isolated message history.
 * Uses AnthropicClient directly to avoid circular dependency with engine.
 * 映射自 TypeScript tools/AgentTool/AgentTool.ts。
 * 每个子代理在自己的上下文中运行，具有隔离的消息历史。
 * 直接使用 AnthropicClient 以避免与 engine 的循环依赖。
 */
class AgentTool : Tool<AgentInput, AgentOutput> {
    override val name = "AgentTool"
    override val aliases = listOf("Agent", "Task")

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Launch a subagent to handle a complex, multi-step task autonomously. The subagent has its own context, can use tools, and iterates until the task is done.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): AgentInput {
        return AgentInput(
            prompt = rawInput["prompt"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing: prompt"),
            description = rawInput["description"]?.jsonPrimitive?.content,
            subagentType = rawInput["subagent_type"]?.jsonPrimitive?.content ?: "general-purpose"
        )
    }

    override suspend fun call(
        input: AgentInput,
        context: ToolUseContext,
        canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant,
        onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<AgentOutput> {
        logger.info { "Launching subagent: type=${input.subagentType}, prompt=${input.prompt.take(80)}" }

        val agentId = "agent-${System.currentTimeMillis()}"
        val apiClient = context.getAppState().apiClient

        if (apiClient == null) {
            val output = AgentOutput(
                agentId = agentId,
                subagentType = input.subagentType,
                status = "error",
                error = "Agent execution requires API client in AppState"
            )
            return ToolResult(data = output, output = output, isError = true)
        }

        onProgress?.invoke(ToolProgressData.AgentToolProgress(agentId = agentId, status = "spawning"))

        return try {
            val result = runAgent(
                agentId = agentId,
                apiClient = apiClient,
                prompt = input.prompt,
                agentType = input.subagentType,
                model = context.options.mainLoopModel,
                tools = context.options.tools,
                onProgress = onProgress
            )

            val output = AgentOutput(
                agentId = agentId,
                subagentType = input.subagentType,
                status = "completed",
                result = result.text,
                iterations = result.iterations,
                toolResults = result.toolUseCount
            )

            onProgress?.invoke(ToolProgressData.AgentToolProgress(agentId = agentId, status = "completed"))
            ToolResult(data = output, output = output)

        } catch (e: Exception) {
            logger.error(e) { "Agent $agentId failed" }
            val output = AgentOutput(
                agentId = agentId,
                subagentType = input.subagentType,
                status = "failed",
                error = e.message
            )
            ToolResult(data = output, output = output, isError = true)
        }
    }

    /**
     * Run the agent loop: send message, execute tools, repeat
     * 运行代理循环：发送消息、执行工具、重复
     */
    private suspend fun runAgent(
        agentId: String,
        apiClient: AnthropicClient,
        prompt: String,
        agentType: String,
        model: String,
        tools: List<Tool<*, *>>,
        onProgress: ((ToolProgressData) -> Unit)?
    ): AgentLoopResult {
        val systemPrompt = buildAgentSystemPrompt(agentType)
        val agentTools = filterToolsForAgentType(agentType, tools)
        val apiToolDefs = agentTools.map { tool ->
            ApiTool(
                name = tool.name,
                description = tool.inputJSONSchema.description ?: "",
                inputSchema = tool.inputJSONSchema.schema
            )
        }

        val messages = mutableListOf(ApiMessage(role = "user", content = prompt))
        var finalText = ""
        var totalToolUses = 0

        for (iteration in 1..MAX_AGENT_ITERATIONS) {
            onProgress?.invoke(ToolProgressData.AgentToolProgress(
                agentId = agentId, status = "iteration_$iteration"
            ))

            val request = MessageRequest(
                model = model,
                messages = messages.toList(),
                system = systemPrompt,
                maxTokens = 16000,
                tools = if (apiToolDefs.isNotEmpty()) apiToolDefs else null,
                stream = false
            )

            val response = apiClient.sendMessage(request)

            // Parse content blocks / 解析内容块
            val textParts = mutableListOf<String>()
            val toolUseBlocks = mutableListOf<Triple<String, String, JsonObject>>()

            for (block in response.content) {
                val blockType = block["type"]?.toString()?.removeSurrounding("\"") ?: ""
                when (blockType) {
                    "text" -> {
                        textParts.add(block["text"]?.toString()?.removeSurrounding("\"") ?: "")
                    }
                    "tool_use" -> {
                        val id = block["id"]?.toString()?.removeSurrounding("\"") ?: ""
                        val name = block["name"]?.toString()?.removeSurrounding("\"") ?: ""
                        val input = block["input"] as? JsonObject ?: JsonObject(emptyMap())
                        toolUseBlocks.add(Triple(id, name, input))
                    }
                }
            }

            val responseText = textParts.joinToString("\n")
            messages.add(ApiMessage(role = "assistant", content = responseText))

            if (toolUseBlocks.isEmpty()) {
                finalText = responseText
                return AgentLoopResult(finalText, iteration, totalToolUses)
            }

            // Execute tools inline / 内联执行工具
            val toolResults = mutableListOf<String>()
            for ((_, toolName, toolInput) in toolUseBlocks) {
                totalToolUses++
                onProgress?.invoke(ToolProgressData.AgentToolProgress(
                    agentId = agentId, status = "tool:$toolName"
                ))

                val tool = agentTools.find { it.name == toolName }
                if (tool == null) {
                    toolResults.add("[$toolName]: Error - tool not found")
                    continue
                }

                try {
                    @Suppress("UNCHECKED_CAST")
                    val typedTool = tool as Tool<Any, Any>
                    val parsedInput = typedTool.parseInput(toolInput)
                    val dummyContext = ToolUseContext(
                        options = ToolUseOptions(tools = tools, mainLoopModel = model),
                        job = kotlinx.coroutines.Job(),
                        getAppState = { AppState() },
                        setAppState = {},
                        messages = emptyList(),
                        agentId = AgentId(agentId),
                        agentType = "subagent"
                    )
                    val dummyParent = Message.Assistant(
                        uuid = "agent-$agentId",
                        timestamp = java.time.Instant.now().toString(),
                        content = listOf(ContentBlock.Text("agent"))
                    )
                    val canUse: CanUseToolFn = { _, _ -> PermissionResult.Allow() }
                    val result = typedTool.call(parsedInput, dummyContext, canUse, dummyParent, null)
                    toolResults.add("[$toolName]: ${result.data?.toString()?.take(2000) ?: "OK"}")
                } catch (e: Exception) {
                    toolResults.add("[$toolName]: Error - ${e.message}")
                }
            }

            messages.add(ApiMessage(role = "user", content = toolResults.joinToString("\n\n")))
            finalText = responseText
        }

        return AgentLoopResult(finalText, MAX_AGENT_ITERATIONS, totalToolUses)
    }

    private fun filterToolsForAgentType(agentType: String, allTools: List<Tool<*, *>>): List<Tool<*, *>> {
        val allowedNames = when (agentType) {
            "explore-agent" -> setOf("Read", "FileRead", "Glob", "GlobTool", "Grep", "GrepTool", "Bash", "BashTool")
            "code-reviewer" -> setOf("Read", "FileRead", "Glob", "GlobTool", "Grep", "GrepTool", "Bash", "BashTool", "WebSearch", "WebFetch")
            "browser-agent" -> setOf("Read", "FileRead", "Glob", "GlobTool", "Grep", "GrepTool", "WebFetch", "WebSearch")
            "plan-agent" -> setOf("Read", "FileRead", "Glob", "GlobTool", "Grep", "GrepTool", "Bash", "BashTool")
            else -> allTools.map { it.name }.toSet()
        }
        return allTools.filter { it.name in allowedNames }
    }

    private fun buildAgentSystemPrompt(agentType: String): String {
        val role = when (agentType) {
            "explore-agent" -> "You are a fast codebase exploration agent. Find files, search code, and answer questions quickly."
            "code-reviewer" -> "You are a code review agent. Review changes for correctness, security, performance, and test impact."
            "plan-agent" -> "You are a software architect agent. Design implementation plans with step-by-step approaches."
            "browser-agent" -> "You are a browser automation agent. Navigate websites, fill forms, and extract information."
            else -> "You are a general-purpose coding agent. Handle complex multi-step tasks autonomously."
        }
        return "$role\n\nWork autonomously. Use tools as needed. Return a concise result when done."
    }

    override suspend fun description(input: AgentInput, options: DescriptionOptions): String {
        return input.description ?: "Launch ${input.subagentType} agent"
    }

    override suspend fun checkPermissions(input: AgentInput, context: ToolUseContext) = PermissionResult.Allow()

    override fun getActivityDescription(input: AgentInput): String {
        return input.description ?: "Running ${input.subagentType} agent"
    }

    companion object {
        const val MAX_AGENT_ITERATIONS = 15
    }
}

private data class AgentLoopResult(val text: String, val iterations: Int, val toolUseCount: Int)

@Serializable data class AgentInput(
    val prompt: String, val description: String? = null,
    val subagentType: String = "general-purpose"
)
@Serializable data class AgentOutput(
    val agentId: String? = null, val subagentType: String? = null,
    val status: String = "pending", val result: String? = null,
    val error: String? = null, val iterations: Int = 0,
    val toolResults: Int = 0
)
