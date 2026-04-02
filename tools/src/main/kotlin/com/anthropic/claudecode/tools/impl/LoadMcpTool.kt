package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.services.mcp.McpManager
import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * LoadMcpTool - dynamically loads MCP tools from configured servers
 * LoadMcpTool - 从配置的服务器动态加载 MCP 工具
 *
 * Maps from TypeScript tools/LoadMcpTool.
 * Connects to MCP servers and registers their tools as native tools.
 * 映射自 TypeScript tools/LoadMcpTool。
 * 连接到 MCP 服务器并将其工具注册为原生工具。
 */
class LoadMcpTool : Tool<LoadMcpInput, LoadMcpOutput> {
    override val name = "LoadMcpTool"
    override val aliases = listOf("LoadMcp")

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Load MCP tool(s) from configured servers to make them available in the current session.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): LoadMcpInput {
        val toolsList = rawInput["tools"]?.jsonArray?.map { elem ->
            val obj = elem as? JsonObject ?: throw IllegalArgumentException("Invalid tool spec")
            LoadMcpToolSpec(
                server = obj["server"]?.jsonPrimitive?.content ?: "",
                tool = obj["tool"]?.jsonPrimitive?.content ?: ""
            )
        }
        return LoadMcpInput(
            server = rawInput["server"]?.jsonPrimitive?.content,
            tool = rawInput["tool"]?.jsonPrimitive?.content,
            tools = toolsList
        )
    }

    override suspend fun call(
        input: LoadMcpInput, context: ToolUseContext, canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant, onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<LoadMcpOutput> {
        logger.info { "Loading MCP tools" }

        // Collect tool specs from either single or batch format
        // 从单个或批量格式收集工具规格
        val specs = input.tools ?: if (input.server != null && input.tool != null) {
            listOf(LoadMcpToolSpec(server = input.server, tool = input.tool))
        } else {
            return ToolResult(
                data = LoadMcpOutput(status = "error", error = "Specify server+tool or tools array"),
                isError = true
            )
        }

        val loaded = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (spec in specs) {
            onProgress?.invoke(ToolProgressData.MCPProgress(
                serverName = spec.server, status = "loading ${spec.tool}"
            ))

            try {
                // Ensure server is started via McpManager
                // 确保服务器已通过 McpManager 启动
                val mcpManager = McpManager()
                if (!mcpManager.isRunning(spec.server)) {
                    // Try to start from known server configs
                    // 尝试从已知服务器配置启动
                    val serverCmd = System.getenv("MCP_SERVER_${spec.server.uppercase()}_CMD")
                    val serverArgs = System.getenv("MCP_SERVER_${spec.server.uppercase()}_ARGS")
                        ?.split(" ") ?: emptyList()

                    if (serverCmd != null) {
                        mcpManager.startServer(McpManager.ServerConfig(
                            name = spec.server,
                            command = serverCmd,
                            args = serverArgs
                        ))
                    }
                }

                // Verify tool exists on server
                // 验证工具在服务器上存在
                val availableTools = mcpManager.listTools(spec.server)
                if (spec.tool in availableTools) {
                    loaded.add("mcp__${spec.server}__${spec.tool}")
                } else {
                    errors.add("${spec.server}/${spec.tool}: not found (available: ${availableTools.take(5).joinToString()})")
                }

            } catch (e: Exception) {
                logger.warn(e) { "Failed to load ${spec.server}/${spec.tool}" }
                errors.add("${spec.server}/${spec.tool}: ${e.message}")
            }
        }

        val output = LoadMcpOutput(
            status = if (errors.isEmpty()) "success" else "partial",
            loadedTools = loaded,
            errors = errors.ifEmpty { null },
            message = "${loaded.size} tool(s) loaded" + if (errors.isNotEmpty()) ", ${errors.size} error(s)" else ""
        )

        return ToolResult(data = output, output = output, isError = loaded.isEmpty() && errors.isNotEmpty())
    }

    override suspend fun description(input: LoadMcpInput, options: DescriptionOptions) = "Load MCP tools"
    override suspend fun checkPermissions(input: LoadMcpInput, context: ToolUseContext) = PermissionResult.Allow()
}

@Serializable data class LoadMcpToolSpec(val server: String, val tool: String)
@Serializable data class LoadMcpInput(
    val server: String? = null, val tool: String? = null,
    val tools: List<LoadMcpToolSpec>? = null
)
@Serializable data class LoadMcpOutput(
    val status: String = "pending",
    val loadedTools: List<String> = emptyList(),
    val errors: List<String>? = null,
    val message: String? = null,
    val error: String? = null
)
