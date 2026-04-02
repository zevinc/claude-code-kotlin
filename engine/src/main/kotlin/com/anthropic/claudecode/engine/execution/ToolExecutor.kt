package com.anthropic.claudecode.engine.execution

import com.anthropic.claudecode.services.hooks.HookEngine
import com.anthropic.claudecode.services.hooks.HookEvent
import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.*

private val logger = KotlinLogging.logger {}

/**
 * Tool Executor - orchestrates tool execution with permission checks and hooks
 * 工具执行器 - 协调工具执行，包括权限检查和钩子
 *
 * Maps from TypeScript services/tools/toolExecution.ts.
 * Pipeline: validate -> permission check -> pre-hook -> execute -> post-hook.
 * 映射自 TypeScript services/tools/toolExecution.ts。
 * 管道：验证 -> 权限检查 -> 前置钩子 -> 执行 -> 后置钩子。
 */
class ToolExecutor(
    private val toolRegistry: ToolRegistry,
    private val hookEngine: HookEngine? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Execute a tool use request from the model
     * 执行来自模型的工具使用请求
     *
     * @param toolName Name of the tool to execute / 要执行的工具名称
     * @param rawInput Raw JSON input from the model / 来自模型的原始 JSON 输入
     * @param context Tool execution context / 工具执行上下文
     * @param canUseTool Permission check function / 权限检查函数
     * @param parentMessage The assistant message that initiated this / 发起此操作的助手消息
     * @param onProgress Progress callback / 进度回调
     * @return Execution result / 执行结果
     */
    suspend fun execute(
        toolName: String,
        rawInput: JsonObject,
        context: ToolUseContext,
        canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant,
        onProgress: ((ToolProgressData) -> Unit)? = null
    ): ToolExecutionResult {
        // 1. Find tool / 查找工具
        val tool = toolRegistry.get(toolName)
            ?: return ToolExecutionResult(
                toolName = toolName,
                error = "Unknown tool: $toolName",
                isError = true
            )

        @Suppress("UNCHECKED_CAST")
        val typedTool = tool as Tool<Any, Any>

        // 2. Parse input / 解析输入
        val input = try {
            typedTool.parseInput(rawInput)
        } catch (e: Exception) {
            return ToolExecutionResult(
                toolName = toolName,
                error = "Invalid input: ${e.message}",
                isError = true
            )
        }

        // 3. Validate input / 验证输入
        val validation = typedTool.validateInput(input, context)
        if (validation is ValidationResult.Failure) {
            return ToolExecutionResult(
                toolName = toolName,
                error = "Validation failed: ${validation.message}",
                isError = true
            )
        }

        // 4. Check permissions / 检查权限
        val permission = typedTool.checkPermissions(input, context)
        if (permission is PermissionResult.Deny) {
            return ToolExecutionResult(
                toolName = toolName,
                error = "Permission denied: ${permission.message}",
                isError = true,
                permissionDenied = true
            )
        }

        // 5. Execute pre-tool hooks / 执行前置工具钩子
        if (hookEngine != null) {
            val preHookContext = buildJsonObject {
                put("tool_name", toolName)
                put("input", rawInput)
            }
            val preResult = hookEngine.execute(HookEvent.PRE_TOOL_USE, preHookContext)
            if (preResult.blocked) {
                return ToolExecutionResult(
                    toolName = toolName,
                    error = "Blocked by pre-tool hook: ${preResult.results.lastOrNull()?.message ?: ""}",
                    isError = true,
                    blockedByHook = true
                )
            }
        }

        // 6. Execute tool / 执行工具
        val startTime = System.currentTimeMillis()
        val result = try {
            typedTool.call(input, context, canUseTool, parentMessage, onProgress)
        } catch (e: Exception) {
            logger.error(e) { "Tool execution failed: $toolName" }

            // Execute failure hooks / 执行失败钩子
            hookEngine?.execute(HookEvent.POST_TOOL_USE_FAILURE, buildJsonObject {
                put("tool_name", toolName)
                put("error", e.message ?: "Unknown error")
            })

            return ToolExecutionResult(
                toolName = toolName,
                error = "Execution failed: ${e.message}",
                isError = true,
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        val durationMs = System.currentTimeMillis() - startTime

        // 7. Execute post-tool hooks / 执行后置工具钩子
        if (hookEngine != null) {
            val postHookContext = buildJsonObject {
                put("tool_name", toolName)
                put("input", rawInput)
                put("is_error", result.isError)
                put("duration_ms", durationMs)
            }
            hookEngine.execute(HookEvent.POST_TOOL_USE, postHookContext)
        }

        logger.info { "Tool $toolName completed in ${durationMs}ms, error=${result.isError}" }

        return ToolExecutionResult(
            toolName = toolName,
            output = result.output?.toString(),
            isError = result.isError,
            durationMs = durationMs
        )
    }
}

/**
 * Result of a tool execution
 * 工具执行结果
 */
data class ToolExecutionResult(
    val toolName: String,
    val output: String? = null,
    val error: String? = null,
    val isError: Boolean = false,
    val durationMs: Long = 0,
    val permissionDenied: Boolean = false,
    val blockedByHook: Boolean = false
)
