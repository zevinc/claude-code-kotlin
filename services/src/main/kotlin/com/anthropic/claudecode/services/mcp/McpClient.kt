package com.anthropic.claudecode.services.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.BufferedWriter

private val logger = KotlinLogging.logger {}

/**
 * MCP Client - Model Context Protocol client implementation
 * MCP 客户端 - Model Context Protocol 客户端实现
 *
 * Maps from TypeScript services/mcpClient.
 * Communicates with MCP servers via JSON-RPC over stdio.
 * 映射自 TypeScript services/mcpClient。
 * 通过 stdio 上的 JSON-RPC 与 MCP 服务器通信。
 */
class McpClient(
    private val serverName: String,
    private val command: String,
    private val args: List<String> = emptyList(),
    private val env: Map<String, String> = emptyMap()
) {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var requestId = 0
    private val json = Json { ignoreUnknownKeys = true }

    private var _tools: List<McpToolInfo> = emptyList()
    private var _resources: List<McpResource> = emptyList()

    val tools: List<McpToolInfo> get() = _tools
    val resources: List<McpResource> get() = _resources
    val isConnected: Boolean get() = process?.isAlive == true

    /**
     * Connect to the MCP server / 连接到 MCP 服务器
     */
    suspend fun connect(): Boolean {
        return try {
            val cmdList = listOf(command) + args
            val pb = ProcessBuilder(cmdList)
                .redirectErrorStream(false)

            // Apply environment variables / 应用环境变量
            pb.environment().putAll(env)

            process = pb.start()
            writer = process!!.outputStream.bufferedWriter()
            reader = process!!.inputStream.bufferedReader()

            // Initialize protocol / 初始化协议
            val initResult = sendRequest("initialize", buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", "claude-code-kt")
                    put("version", "1.0.0")
                })
            })

            if (initResult != null) {
                // Send initialized notification / 发送已初始化通知
                sendNotification("notifications/initialized", JsonObject(emptyMap()))

                // Fetch available tools / 获取可用工具
                refreshTools()
                refreshResources()

                logger.info { "MCP server '$serverName' connected, ${_tools.size} tools available" }
                true
            } else {
                logger.error { "MCP server '$serverName' initialization failed" }
                false
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to connect to MCP server '$serverName'" }
            false
        }
    }

    /**
     * Call a tool on the MCP server / 调用 MCP 服务器上的工具
     */
    suspend fun callTool(toolName: String, arguments: JsonObject): McpToolResult {
        val result = sendRequest("tools/call", buildJsonObject {
            put("name", toolName)
            put("arguments", arguments)
        })

        return if (result != null) {
            val content = result["content"]?.jsonArray?.map { elem ->
                val obj = elem.jsonObject
                val type = obj["type"]?.jsonPrimitive?.content ?: "text"
                val text = obj["text"]?.jsonPrimitive?.content ?: ""
                McpContent(type = type, text = text)
            } ?: emptyList()
            val isError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false
            McpToolResult(content = content, isError = isError)
        } else {
            McpToolResult(content = listOf(McpContent(text = "MCP call failed")), isError = true)
        }
    }

    /**
     * Read a resource from the MCP server / 从 MCP 服务器读取资源
     */
    suspend fun readResource(uri: String): String? {
        val result = sendRequest("resources/read", buildJsonObject {
            put("uri", uri)
        })
        return result?.get("contents")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.content
    }

    /**
     * Refresh the list of available tools / 刷新可用工具列表
     */
    private suspend fun refreshTools() {
        val result = sendRequest("tools/list", JsonObject(emptyMap()))
        _tools = result?.get("tools")?.jsonArray?.map { elem ->
            val obj = elem.jsonObject
            McpToolInfo(
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                description = obj["description"]?.jsonPrimitive?.content ?: "",
                inputSchema = obj["inputSchema"]?.jsonObject ?: JsonObject(emptyMap())
            )
        } ?: emptyList()
    }

    /**
     * Refresh the list of available resources / 刷新可用资源列表
     */
    private suspend fun refreshResources() {
        val result = sendRequest("resources/list", JsonObject(emptyMap()))
        _resources = result?.get("resources")?.jsonArray?.map { elem ->
            val obj = elem.jsonObject
            McpResource(
                uri = obj["uri"]?.jsonPrimitive?.content ?: "",
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                description = obj["description"]?.jsonPrimitive?.content,
                mimeType = obj["mimeType"]?.jsonPrimitive?.content
            )
        } ?: emptyList()
    }

    /**
     * Send a JSON-RPC request and get the response
     * 发送 JSON-RPC 请求并获取响应
     */
    private fun sendRequest(method: String, params: JsonElement): JsonObject? {
        val id = ++requestId
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        return try {
            writer?.write(json.encodeToString(JsonObject.serializer(), request))
            writer?.newLine()
            writer?.flush()

            val line = reader?.readLine() ?: return null
            val response = json.parseToJsonElement(line).jsonObject

            if (response["error"] != null) {
                val error = response["error"]?.jsonObject
                logger.warn { "MCP error: ${error?.get("message")?.jsonPrimitive?.content}" }
                null
            } else {
                response["result"]?.jsonObject
            }
        } catch (e: Exception) {
            logger.error(e) { "MCP request failed: $method" }
            null
        }
    }

    private fun sendNotification(method: String, params: JsonElement) {
        val notification = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        try {
            writer?.write(json.encodeToString(JsonObject.serializer(), notification))
            writer?.newLine()
            writer?.flush()
        } catch (e: Exception) {
            logger.warn(e) { "MCP notification failed: $method" }
        }
    }

    /**
     * Disconnect from the MCP server / 断开与 MCP 服务器的连接
     */
    fun disconnect() {
        try {
            writer?.close()
            reader?.close()
            process?.destroyForcibly()
            process = null
            logger.info { "MCP server '$serverName' disconnected" }
        } catch (e: Exception) {
            logger.warn(e) { "Error disconnecting MCP server '$serverName'" }
        }
    }
}

@Serializable data class McpToolInfo(
    val name: String, val description: String, val inputSchema: JsonObject = JsonObject(emptyMap())
)
@Serializable data class McpResource(
    val uri: String, val name: String, val description: String? = null, val mimeType: String? = null
)
@Serializable data class McpContent(val type: String = "text", val text: String = "")
data class McpToolResult(val content: List<McpContent>, val isError: Boolean = false)
