package com.anthropic.claudecode.engine.stream

import com.anthropic.claudecode.services.api.StreamEvent
import com.anthropic.claudecode.types.Message
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

private val logger = KotlinLogging.logger {}

/**
 * StreamHandler - processes SSE stream events into structured messages
 * StreamHandler - 将 SSE 流事件处理为结构化消息
 *
 * Maps from TypeScript services/claude/stream.ts.
 * Accumulates text deltas, tool use JSON fragments, and usage data
 * into a complete assistant message with content blocks.
 * 映射自 TypeScript services/claude/stream.ts。
 * 将文本增量、工具使用 JSON 片段和使用数据
 * 累积为带有内容块的完整助手消息。
 */
class StreamHandler {

    /**
     * Process a stream of SSE events into structured output events
     * 将 SSE 事件流处理为结构化输出事件
     *
     * @param upstream Raw SSE events from API client / 来自 API 客户端的原始 SSE 事件
     * @return Flow of processed stream output events / 处理后的流输出事件 Flow
     */
    fun process(upstream: Flow<StreamEvent>): Flow<StreamOutput> = flow {
        val state = StreamState()

        upstream.collect { event ->
            when (event) {
                is StreamEvent.MessageStart -> {
                    state.messageId = event.messageId
                    emit(StreamOutput.Started(messageId = event.messageId))
                }

                is StreamEvent.ContentBlockStart -> {
                    state.currentBlockIndex = event.index
                    state.currentBlockType = event.type
                    if (event.type == "text") {
                        state.textBuffer.clear()
                    }
                }

                is StreamEvent.ContentBlockDelta -> {
                    state.textBuffer.append(event.delta)
                    emit(StreamOutput.TextDelta(
                        text = event.delta,
                        blockIndex = event.index,
                        accumulatedText = state.textBuffer.toString()
                    ))
                }

                is StreamEvent.InputJsonDelta -> {
                    state.toolInputBuffer.append(event.partialJson)
                }

                is StreamEvent.ToolUse -> {
                    // Flush any pending text / 刷新任何待处理的文本
                    if (state.textBuffer.isNotEmpty()) {
                        state.completedBlocks.add(CompletedBlock.Text(state.textBuffer.toString()))
                        state.textBuffer.clear()
                    }

                    state.currentToolUseId = event.id
                    state.currentToolName = event.name
                    state.toolInputBuffer.clear()
                    emit(StreamOutput.ToolUseStart(
                        toolUseId = event.id,
                        toolName = event.name
                    ))
                }

                is StreamEvent.ContentBlockStop -> {
                    if (state.currentToolName != null) {
                        // Complete tool use block / 完成工具使用块
                        val inputJson = try {
                            val raw = state.toolInputBuffer.toString()
                            if (raw.isNotBlank()) {
                                Json.parseToJsonElement(raw).jsonObject
                            } else {
                                JsonObject(emptyMap())
                            }
                        } catch (e: Exception) {
                            logger.warn { "Failed to parse tool input JSON: ${e.message}" }
                            JsonObject(emptyMap())
                        }

                        val toolBlock = CompletedBlock.ToolUse(
                            id = state.currentToolUseId ?: "",
                            name = state.currentToolName ?: "",
                            input = inputJson
                        )
                        state.completedBlocks.add(toolBlock)

                        emit(StreamOutput.ToolUseComplete(
                            toolUseId = toolBlock.id,
                            toolName = toolBlock.name,
                            input = inputJson
                        ))

                        state.currentToolUseId = null
                        state.currentToolName = null
                        state.toolInputBuffer.clear()
                    } else if (state.textBuffer.isNotEmpty()) {
                        // Complete text block / 完成文本块
                        state.completedBlocks.add(CompletedBlock.Text(state.textBuffer.toString()))
                        state.textBuffer.clear()
                    }
                }

                is StreamEvent.Usage -> {
                    state.outputTokens = event.outputTokens
                    emit(StreamOutput.UsageUpdate(outputTokens = event.outputTokens))
                }

                is StreamEvent.MessageStop -> {
                    // Flush any remaining text / 刷新任何剩余文本
                    if (state.textBuffer.isNotEmpty()) {
                        state.completedBlocks.add(CompletedBlock.Text(state.textBuffer.toString()))
                    }

                    emit(StreamOutput.Completed(
                        messageId = state.messageId ?: "",
                        blocks = state.completedBlocks.toList(),
                        outputTokens = state.outputTokens
                    ))
                }

                is StreamEvent.Error -> {
                    emit(StreamOutput.StreamError(
                        message = event.message,
                        type = event.type
                    ))
                }
            }
        }
    }
}

/**
 * Internal stream accumulation state
 * 内部流累积状态
 */
private class StreamState {
    var messageId: String? = null
    var currentBlockIndex: Int = 0
    var currentBlockType: String = "text"
    var currentToolUseId: String? = null
    var currentToolName: String? = null
    val textBuffer = StringBuilder()
    val toolInputBuffer = StringBuilder()
    val completedBlocks = mutableListOf<CompletedBlock>()
    var outputTokens: Long = 0
}

/**
 * Completed content block types
 * 完成的内容块类型
 */
sealed class CompletedBlock {
    data class Text(val text: String) : CompletedBlock()
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : CompletedBlock()
}

/**
 * Stream output events - processed from raw SSE events
 * 流输出事件 - 从原始 SSE 事件处理而来
 */
sealed class StreamOutput {
    data class Started(val messageId: String) : StreamOutput()
    data class TextDelta(val text: String, val blockIndex: Int, val accumulatedText: String) : StreamOutput()
    data class ToolUseStart(val toolUseId: String, val toolName: String) : StreamOutput()
    data class ToolUseComplete(val toolUseId: String, val toolName: String, val input: JsonObject) : StreamOutput()
    data class UsageUpdate(val outputTokens: Long) : StreamOutput()
    data class Completed(val messageId: String, val blocks: List<CompletedBlock>, val outputTokens: Long) : StreamOutput()
    data class StreamError(val message: String, val type: String) : StreamOutput()
}
