package com.anthropic.claudecode.services.api

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private val logger = KotlinLogging.logger {}

/**
 * Anthropic API client - handles communication with Claude API
 * Anthropic API 客户端 - 处理与 Claude API 的通信
 *
 * Maps from TypeScript services/api/claude.ts.
 * Implements Ktor-based HTTP streaming with SSE event parsing,
 * retry with exponential backoff, and cost tracking.
 * 映射自 TypeScript services/api/claude.ts。
 * 实现基于 Ktor 的 HTTP 流式传输与 SSE 事件解析、
 * 指数退避重试和成本追踪。
 */
class AnthropicClient(
    private val config: ApiConfig
) {
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonParser)
        }
        engine {
            requestTimeout = config.timeoutMs
        }
    }

    /** Cumulative cost tracking / 累计成本追踪 */
    private var totalCostUsd: Double = 0.0
    private var totalInputTokens: Long = 0
    private var totalOutputTokens: Long = 0

    /**
     * Send a message to Claude and receive a streaming response
     * 向 Claude 发送消息并接收流式响应
     *
     * @param request The message request / 消息请求
     * @return Flow of stream events / 流事件的 Flow
     */
    fun streamMessage(request: MessageRequest): Flow<StreamEvent> = flow {
        if (config.apiKey.isBlank()) {
            logger.warn { "API key not configured, using placeholder response" }
            emit(StreamEvent.MessageStart(messageId = "placeholder"))
            emit(StreamEvent.ContentBlockStart(index = 0, type = "text"))
            emit(StreamEvent.ContentBlockDelta(index = 0, delta = "API key not configured. Set ANTHROPIC_API_KEY environment variable or configure in ~/.claude.json\nAPI 密钥未配置。请设置 ANTHROPIC_API_KEY 环境变量或在 ~/.claude.json 中配置"))
            emit(StreamEvent.ContentBlockStop(index = 0))
            emit(StreamEvent.MessageStop)
            return@flow
        }

        var retryCount = 0
        while (retryCount <= config.maxRetries) {
            try {
                val response = httpClient.preparePost("${config.baseUrl}/v1/messages") {
                    contentType(ContentType.Application.Json)
                    header("x-api-key", config.apiKey)
                    header("anthropic-version", config.apiVersion)
                    header("anthropic-beta", "prompt-caching-2024-07-31")
                    accept(ContentType.Text.EventStream)
                    setBody(buildRequestBody(request))
                }.execute()

                if (response.status != HttpStatusCode.OK) {
                    val errorBody = response.bodyAsText()
                    val errorType = categorizeError(response.status.value, errorBody)
                    if (errorType == ErrorCategory.RATE_LIMIT && retryCount < config.maxRetries) {
                        val delayMs = calculateBackoff(retryCount)
                        logger.warn { "Rate limited, retrying in ${delayMs}ms (attempt ${retryCount + 1})" }
                        delay(delayMs)
                        retryCount++
                        continue
                    }
                    emit(StreamEvent.Error(
                        message = "API error ${response.status.value}: $errorBody",
                        type = errorType.name
                    ))
                    return@flow
                }

                // Parse SSE stream / 解析 SSE 流
                val channel: ByteReadChannel = response.bodyAsChannel()
                val buffer = StringBuilder()

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break

                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data == "[DONE]") break

                        try {
                            val event = jsonParser.parseToJsonElement(data).jsonObject
                            val parsedEvent = parseStreamEvent(event)
                            if (parsedEvent != null) {
                                emit(parsedEvent)
                                // Track usage if message_delta / 如果是 message_delta 则追踪使用量
                                if (parsedEvent is StreamEvent.Usage) {
                                    trackUsage(parsedEvent, request.model)
                                }
                            }
                        } catch (e: Exception) {
                            logger.debug { "Failed to parse SSE event: $data" }
                        }
                    }
                }

                // Stream completed successfully / 流式传输成功完成
                emit(StreamEvent.MessageStop)
                return@flow

            } catch (e: Exception) {
                if (retryCount < config.maxRetries) {
                    val delayMs = calculateBackoff(retryCount)
                    logger.warn { "Stream error, retrying in ${delayMs}ms: ${e.message}" }
                    delay(delayMs)
                    retryCount++
                } else {
                    emit(StreamEvent.Error(
                        message = "Connection error after ${config.maxRetries} retries: ${e.message}",
                        type = "CONNECTION_ERROR"
                    ))
                    return@flow
                }
            }
        }
    }

    /**
     * Send a non-streaming message
     * 发送非流式消息
     */
    suspend fun sendMessage(request: MessageRequest): MessageResponse {
        if (config.apiKey.isBlank()) {
            return MessageResponse(id = "placeholder", model = request.model, content = emptyList(), stopReason = "end_turn")
        }

        val response = httpClient.post("${config.baseUrl}/v1/messages") {
            contentType(ContentType.Application.Json)
            header("x-api-key", config.apiKey)
            header("anthropic-version", config.apiVersion)
            setBody(buildRequestBody(request.copy(stream = false)))
        }

        val body = response.bodyAsText()
        return jsonParser.decodeFromString(body)
    }

    /** Get cumulative cost / 获取累计成本 */
    fun getCumulativeCost(): CostSummary = CostSummary(
        totalCostUsd = totalCostUsd,
        totalInputTokens = totalInputTokens,
        totalOutputTokens = totalOutputTokens
    )

    /** Reset cost tracking / 重置成本追踪 */
    fun resetCost() {
        totalCostUsd = 0.0
        totalInputTokens = 0
        totalOutputTokens = 0
    }

    /** Close the HTTP client / 关闭 HTTP 客户端 */
    fun close() {
        httpClient.close()
    }

    // ========== Internal helpers / 内部辅助方法 ==========

    private fun buildRequestBody(request: MessageRequest): String {
        return buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxTokens)
            if (request.stream) put("stream", true)
            request.system?.let { put("system", it) }

            putJsonArray("messages") {
                for (msg in request.messages) {
                    addJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                    }
                }
            }

            request.tools?.let { tools ->
                putJsonArray("tools") {
                    for (tool in tools) {
                        addJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", tool.inputSchema)
                        }
                    }
                }
            }
        }.toString()
    }

    private fun parseStreamEvent(event: JsonObject): StreamEvent? {
        val type = event["type"]?.jsonPrimitive?.content ?: return null
        return when (type) {
            "message_start" -> {
                val messageId = event["message"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: ""
                StreamEvent.MessageStart(messageId = messageId)
            }
            "content_block_start" -> {
                val index = event["index"]?.jsonPrimitive?.intOrNull ?: 0
                val block = event["content_block"]?.jsonObject
                val blockType = block?.get("type")?.jsonPrimitive?.content ?: "text"
                if (blockType == "tool_use") {
                    val id = block?.get("id")?.jsonPrimitive?.content ?: ""
                    val name = block?.get("name")?.jsonPrimitive?.content ?: ""
                    StreamEvent.ToolUse(id = id, name = name, input = JsonObject(emptyMap()))
                } else {
                    StreamEvent.ContentBlockStart(index = index, type = blockType)
                }
            }
            "content_block_delta" -> {
                val index = event["index"]?.jsonPrimitive?.intOrNull ?: 0
                val delta = event["delta"]?.jsonObject
                val deltaType = delta?.get("type")?.jsonPrimitive?.content
                when (deltaType) {
                    "text_delta" -> {
                        val text = delta?.get("text")?.jsonPrimitive?.content ?: ""
                        StreamEvent.ContentBlockDelta(index = index, delta = text)
                    }
                    "input_json_delta" -> {
                        val partialJson = delta?.get("partial_json")?.jsonPrimitive?.content ?: ""
                        StreamEvent.InputJsonDelta(index = index, partialJson = partialJson)
                    }
                    else -> null
                }
            }
            "content_block_stop" -> {
                val index = event["index"]?.jsonPrimitive?.intOrNull ?: 0
                StreamEvent.ContentBlockStop(index = index)
            }
            "message_delta" -> {
                val usage = event["usage"]?.jsonObject
                val outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: 0
                StreamEvent.Usage(outputTokens = outputTokens)
            }
            "message_stop" -> StreamEvent.MessageStop
            "error" -> {
                val error = event["error"]?.jsonObject
                val msg = error?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                StreamEvent.Error(message = msg, type = error?.get("type")?.jsonPrimitive?.content ?: "unknown")
            }
            else -> null
        }
    }

    private fun categorizeError(statusCode: Int, body: String): ErrorCategory = when {
        statusCode == 429 -> ErrorCategory.RATE_LIMIT
        statusCode == 401 -> ErrorCategory.AUTH
        statusCode == 413 -> ErrorCategory.PROMPT_TOO_LONG
        statusCode == 529 -> ErrorCategory.OVERLOADED
        statusCode in 500..599 -> ErrorCategory.SERVER
        else -> ErrorCategory.OTHER
    }

    private fun calculateBackoff(retryCount: Int): Long {
        val baseDelay = 1000L
        val maxDelay = 30000L
        val delay = (baseDelay * (1 shl retryCount)).coerceAtMost(maxDelay)
        val jitter = (Math.random() * delay * 0.1).toLong()
        return delay + jitter
    }

    private fun trackUsage(usage: StreamEvent.Usage, model: String) {
        totalOutputTokens += usage.outputTokens
        totalCostUsd += calculateCost(model, 0, usage.outputTokens)
    }

    private fun calculateCost(model: String, inputTokens: Long, outputTokens: Long): Double {
        // Pricing per million tokens (approximate) / 每百万令牌定价（近似）
        val (inputPrice, outputPrice) = when {
            "opus" in model -> 15.0 to 75.0
            "sonnet" in model -> 3.0 to 15.0
            "haiku" in model -> 0.25 to 1.25
            else -> 3.0 to 15.0
        }
        return (inputTokens * inputPrice + outputTokens * outputPrice) / 1_000_000.0
    }
}

/** Error categories for retry logic / 用于重试逻辑的错误类别 */
enum class ErrorCategory {
    RATE_LIMIT, AUTH, PROMPT_TOO_LONG, OVERLOADED, SERVER, OTHER
}

/** Cost summary / 成本摘要 */
data class CostSummary(
    val totalCostUsd: Double = 0.0,
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0
)

// ========== Data classes / 数据类 ==========

data class ApiConfig(
    val apiKey: String = System.getenv("ANTHROPIC_API_KEY") ?: "",
    val baseUrl: String = "https://api.anthropic.com",
    val apiVersion: String = "2023-06-01",
    val defaultModel: String = "claude-sonnet-4-20250514",
    val maxRetries: Int = 3,
    val timeoutMs: Long = 120_000
)

@Serializable
data class MessageRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val system: String? = null,
    @SerialName("max_tokens") val maxTokens: Int = 8192,
    val tools: List<ApiTool>? = null,
    val stream: Boolean = true
)

@Serializable
data class ApiMessage(val role: String, val content: String)

@Serializable
data class ApiTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject
)

@Serializable
data class MessageResponse(
    val id: String,
    val model: String,
    val content: List<JsonObject>,
    @SerialName("stop_reason") val stopReason: String? = null
)

sealed class StreamEvent {
    data class MessageStart(val messageId: String) : StreamEvent()
    data class ContentBlockStart(val index: Int, val type: String) : StreamEvent()
    data class ContentBlockDelta(val index: Int, val delta: String) : StreamEvent()
    data class InputJsonDelta(val index: Int, val partialJson: String) : StreamEvent()
    data class ContentBlockStop(val index: Int) : StreamEvent()
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : StreamEvent()
    data class Usage(val outputTokens: Long) : StreamEvent()
    data object MessageStop : StreamEvent()
    data class Error(val message: String, val type: String) : StreamEvent()
}
