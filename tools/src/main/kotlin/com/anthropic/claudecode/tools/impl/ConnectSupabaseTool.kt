package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.services.mcp.McpManager
import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

private val logger = KotlinLogging.logger {}

/**
 * ConnectSupabaseTool - connects to Supabase via MCP protocol
 * ConnectSupabaseTool - 通过 MCP 协议连接到 Supabase
 *
 * Maps from TypeScript tools/ConnectSupabaseTool.
 * Starts the supabase MCP server process and establishes
 * JSON-RPC communication, then lists available tools.
 * 映射自 TypeScript tools/ConnectSupabaseTool。
 * 启动 supabase MCP 服务器进程并建立 JSON-RPC 通信，
 * 然后列出可用的工具。
 */
class ConnectSupabaseTool : Tool<ConnectSupabaseInput, ConnectSupabaseOutput> {
    override val name = "ConnectSupabaseTool"
    override val aliases = listOf("ConnectSupabase")

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Connect to a Supabase MCP server to enable database tools (execute SQL, manage tables, etc.).",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): ConnectSupabaseInput {
        return ConnectSupabaseInput()
    }

    override suspend fun call(
        input: ConnectSupabaseInput, context: ToolUseContext, canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant, onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<ConnectSupabaseOutput> {
        logger.info { "Connecting to Supabase MCP server..." }

        onProgress?.invoke(ToolProgressData.MCPProgress(
            serverName = "supabase", status = "connecting"
        ))

        // Resolve Supabase MCP server command
        // 解析 Supabase MCP 服务器命令
        val supabaseCmd = System.getenv("SUPABASE_MCP_CMD") ?: "npx"
        val supabaseArgs = System.getenv("SUPABASE_MCP_ARGS")
            ?.split(" ")
            ?: listOf("-y", "@supabase/mcp-server")

        return try {
            val mcpManager = McpManager()
            val serverConfig = McpManager.ServerConfig(
                name = "supabase",
                command = supabaseCmd,
                args = supabaseArgs,
                env = buildSupabaseEnv()
            )

            mcpManager.startServer(serverConfig)

            onProgress?.invoke(ToolProgressData.MCPProgress(
                serverName = "supabase", status = "listing_tools"
            ))

            // List available tools from the MCP server
            // 从 MCP 服务器列出可用工具
            val tools = mcpManager.listTools("supabase")
            val toolNames = tools.map { "mcp__supabase__${it}" }

            onProgress?.invoke(ToolProgressData.MCPProgress(
                serverName = "supabase", status = "connected"
            ))

            logger.info { "Supabase connected: ${toolNames.size} tools available" }

            val output = ConnectSupabaseOutput(
                status = "connected",
                message = "Supabase MCP server connected. ${toolNames.size} tools available: ${toolNames.joinToString(", ")}",
                availableTools = toolNames
            )
            ToolResult(data = output, output = output)

        } catch (e: Exception) {
            logger.error(e) { "Supabase MCP connection failed" }

            onProgress?.invoke(ToolProgressData.MCPProgress(
                serverName = "supabase", status = "error"
            ))

            val output = ConnectSupabaseOutput(
                status = "error",
                message = "Failed to connect: ${e.message}. Ensure Supabase MCP server is available."
            )
            ToolResult(data = output, output = output, isError = true)
        }
    }

    /**
     * Build environment variables for Supabase MCP server
     * 构建 Supabase MCP 服务器的环境变量
     */
    private fun buildSupabaseEnv(): Map<String, String> {
        val env = mutableMapOf<String, String>()

        System.getenv("SUPABASE_URL")?.let { env["SUPABASE_URL"] = it }
        System.getenv("SUPABASE_ANON_KEY")?.let { env["SUPABASE_ANON_KEY"] = it }
        System.getenv("SUPABASE_SERVICE_ROLE_KEY")?.let { env["SUPABASE_SERVICE_ROLE_KEY"] = it }
        System.getenv("SUPABASE_ACCESS_TOKEN")?.let { env["SUPABASE_ACCESS_TOKEN"] = it }

        return env
    }

    override suspend fun description(input: ConnectSupabaseInput, options: DescriptionOptions) = "Connect Supabase"
    override suspend fun checkPermissions(input: ConnectSupabaseInput, context: ToolUseContext) = PermissionResult.Allow()
}

@Serializable class ConnectSupabaseInput
@Serializable data class ConnectSupabaseOutput(
    val status: String = "disconnected",
    val message: String? = null,
    val availableTools: List<String> = emptyList()
)
