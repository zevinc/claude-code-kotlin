package com.anthropic.claudecode.services.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * McpManager - manages MCP server lifecycle and tool discovery
 * McpManager - 管理 MCP 服务器生命周期和工具发现
 *
 * Maps from TypeScript services/mcp/McpManager.ts.
 * Handles starting/stopping MCP servers and routing
 * tool calls to the appropriate server.
 * 映射自 TypeScript services/mcp/McpManager.ts。
 * 处理启动/停止 MCP 服务器以及将工具调用路由到适当的服务器。
 */
class McpManager {
    private val clients = ConcurrentHashMap<String, McpClient>()

    /**
     * Start an MCP server with the given configuration
     * 使用给定配置启动 MCP 服务器
     */
    suspend fun startServer(config: ServerConfig) {
        if (clients.containsKey(config.name)) {
            logger.info { "Server ${config.name} already running" }
            return
        }

        logger.info { "Starting MCP server: ${config.name} (${config.command})" }

        val client = McpClient(
            serverName = config.name,
            command = config.command,
            args = config.args,
            env = config.env
        )

        val connected = client.connect()
        if (!connected) {
            throw RuntimeException("Failed to connect to MCP server: ${config.name}")
        }

        clients[config.name] = client
        logger.info { "MCP server ${config.name} started, ${client.tools.size} tools available" }
    }

    /**
     * List tools available on a server / 列出服务器上可用的工具
     */
    fun listTools(serverName: String): List<String> {
        val client = clients[serverName]
            ?: throw IllegalStateException("Server $serverName not found")
        return client.tools.map { it.name }
    }

    /**
     * Execute a tool on a server / 在服务器上执行工具
     */
    suspend fun executeTool(serverName: String, toolName: String, input: JsonObject): McpToolResult {
        val client = clients[serverName]
            ?: throw IllegalStateException("Server $serverName not found")
        return client.callTool(toolName, input)
    }

    /**
     * Stop a specific server / 停止特定服务器
     */
    fun stopServer(serverName: String) {
        val client = clients.remove(serverName) ?: return
        try {
            client.disconnect()
            logger.info { "Stopped MCP server: $serverName" }
        } catch (e: Exception) {
            logger.warn(e) { "Error stopping server $serverName" }
        }
    }

    /** Stop all servers / 停止所有服务器 */
    fun stopAll() {
        clients.keys.toList().forEach { stopServer(it) }
    }

    /** Get all running server names / 获取所有运行中的服务器名称 */
    fun getRunningServers(): List<String> = clients.keys().toList()

    /** Check if a server is running / 检查服务器是否运行中 */
    fun isRunning(serverName: String): Boolean = clients[serverName]?.isConnected == true

    data class ServerConfig(
        val name: String,
        val command: String,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        val timeout: Long = 30_000
    )
}
