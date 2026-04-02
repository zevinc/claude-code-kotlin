package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import com.anthropic.claudecode.services.mcp.McpManager
import com.anthropic.claudecode.services.mcp.McpToolInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * MCPTool - wraps an MCP server tool as a native Claude tool
 * MCPTool - 将 MCP 服务器工具包装为原生 Claude 工具
 *
 * Maps from TypeScript tools/MCPTool.
 * Each MCP tool from a server becomes an instance of this class.
 * 映射自 TypeScript tools/MCPTool。
 * 来自服务器的每个 MCP 工具都成为此类的一个实例。
 */
class MCPTool(
    private val serverName: String,
    private val toolInfo: McpToolInfo,
    private val mcpManager: McpManager
) : Tool<MCPInput, MCPOutput> {

    override val name: String = "mcp__${serverName}__${toolInfo.name}"

    override val inputJSONSchema = ToolInputJSONSchema(
        description = toolInfo.description,
        schema = toolInfo.inputSchema
    )

    override fun parseInput(rawInput: JsonObject): MCPInput {
        return MCPInput(serverName = serverName, toolName = toolInfo.name, arguments = rawInput)
    }

    override suspend fun call(
        input: MCPInput, context: ToolUseContext, canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant, onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<MCPOutput> {
        logger.info { "MCP call: ${input.serverName}/${input.toolName}" }

        onProgress?.invoke(ToolProgressData.MCPProgress(
            serverName = input.serverName, status = "calling"
        ))

        val result = mcpManager.executeTool(input.serverName, input.toolName, input.arguments)
        val text = result.content.joinToString("\n") { it.text }

        val output = MCPOutput(
            serverName = input.serverName,
            toolName = input.toolName,
            content = text,
            isError = result.isError
        )

        return ToolResult(data = output, output = output, isError = result.isError)
    }

    override suspend fun description(input: MCPInput, options: DescriptionOptions): String {
        return "${input.serverName}/${input.toolName}"
    }

    override suspend fun checkPermissions(input: MCPInput, context: ToolUseContext): PermissionResult {
        return PermissionResult.Allow()
    }
}

@Serializable data class MCPInput(val serverName: String, val toolName: String, val arguments: JsonObject)
@Serializable data class MCPOutput(
    val serverName: String? = null, val toolName: String? = null,
    val content: String? = null, val isError: Boolean = false
)
