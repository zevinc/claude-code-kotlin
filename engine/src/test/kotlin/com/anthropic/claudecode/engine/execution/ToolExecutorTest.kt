package com.anthropic.claudecode.engine.execution

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Job
import kotlinx.serialization.json.*

/**
 * Tests for ToolExecutor
 * ToolExecutor 测试
 */
class ToolExecutorTest : DescribeSpec({

    // Test tool implementations
    // 测试工具实现
    class EchoTool : Tool<String, String> {
        override val name = "Echo"
        override val inputJSONSchema = ToolInputJSONSchema()
        override fun parseInput(rawInput: JsonObject): String =
            rawInput["text"]?.jsonPrimitive?.content ?: ""
        override suspend fun call(
            input: String,
            context: ToolUseContext,
            canUseTool: CanUseToolFn,
            parentMessage: Message.Assistant,
            onProgress: ((ToolProgressData) -> Unit)?
        ) = ToolResult(data = "Echo: $input")
        override suspend fun description(input: String, options: DescriptionOptions) = "Echo tool"
        override suspend fun checkPermissions(input: String, context: ToolUseContext): PermissionResult =
            PermissionResult.Allow()
    }

    class FailingTool : Tool<String, String> {
        override val name = "Failing"
        override val inputJSONSchema = ToolInputJSONSchema()
        override fun parseInput(rawInput: JsonObject): String = "input"
        override suspend fun call(
            input: String,
            context: ToolUseContext,
            canUseTool: CanUseToolFn,
            parentMessage: Message.Assistant,
            onProgress: ((ToolProgressData) -> Unit)?
        ): ToolResult<String> {
            throw RuntimeException("Tool execution failed intentionally")
        }
        override suspend fun description(input: String, options: DescriptionOptions) = "Failing tool"
        override suspend fun checkPermissions(input: String, context: ToolUseContext): PermissionResult =
            PermissionResult.Allow()
    }

    class DenyTool : Tool<String, String> {
        override val name = "Denied"
        override val inputJSONSchema = ToolInputJSONSchema()
        override fun parseInput(rawInput: JsonObject): String = "input"
        override suspend fun call(
            input: String,
            context: ToolUseContext,
            canUseTool: CanUseToolFn,
            parentMessage: Message.Assistant,
            onProgress: ((ToolProgressData) -> Unit)?
        ) = ToolResult(data = "should not reach")
        override suspend fun description(input: String, options: DescriptionOptions) = "Deny tool"
        override suspend fun checkPermissions(input: String, context: ToolUseContext): PermissionResult =
            PermissionResult.Deny(
                message = "Access denied for testing",
                decisionReason = PermissionDecisionReason.Other("test")
            )
    }

    fun createContext(): ToolUseContext = ToolUseContext(
        options = ToolUseOptions(),
        job = Job(),
        getAppState = { AppState() },
        setAppState = { },
        messages = emptyList()
    )

    fun createParentMessage(): Message.Assistant = Message.Assistant(
        uuid = "test-msg",
        timestamp = "2024-01-01T00:00:00Z",
        content = emptyList()
    )

    val allowAll: CanUseToolFn = { _, _ -> PermissionResult.Allow() }

    describe("ToolExecutor") {
        it("should execute a tool successfully") {
            val registry = ToolRegistry()
            registry.register(EchoTool())
            val executor = ToolExecutor(registry)

            val result = executor.execute(
                toolName = "Echo",
                rawInput = buildJsonObject { put("text", "hello") },
                context = createContext(),
                canUseTool = allowAll,
                parentMessage = createParentMessage()
            )

            result.isError shouldBe false
            result.toolName shouldBe "Echo"
            result.output shouldBe "Echo: hello"
        }

        it("should return error for unknown tool") {
            val registry = ToolRegistry()
            val executor = ToolExecutor(registry)

            val result = executor.execute(
                toolName = "NonExistent",
                rawInput = JsonObject(emptyMap()),
                context = createContext(),
                canUseTool = allowAll,
                parentMessage = createParentMessage()
            )

            result.isError shouldBe true
            result.error!! shouldContain "Unknown tool"
        }

        it("should handle tool execution failure") {
            val registry = ToolRegistry()
            registry.register(FailingTool())
            val executor = ToolExecutor(registry)

            val result = executor.execute(
                toolName = "Failing",
                rawInput = JsonObject(emptyMap()),
                context = createContext(),
                canUseTool = allowAll,
                parentMessage = createParentMessage()
            )

            result.isError shouldBe true
            result.error!! shouldContain "Execution failed"
        }

        it("should deny execution when permission check fails") {
            val registry = ToolRegistry()
            registry.register(DenyTool())
            val executor = ToolExecutor(registry)

            val result = executor.execute(
                toolName = "Denied",
                rawInput = JsonObject(emptyMap()),
                context = createContext(),
                canUseTool = allowAll,
                parentMessage = createParentMessage()
            )

            result.isError shouldBe true
            result.permissionDenied shouldBe true
            result.error!! shouldContain "Permission denied"
        }

        it("should track execution duration") {
            val registry = ToolRegistry()
            registry.register(EchoTool())
            val executor = ToolExecutor(registry)

            val result = executor.execute(
                toolName = "Echo",
                rawInput = buildJsonObject { put("text", "timing test") },
                context = createContext(),
                canUseTool = allowAll,
                parentMessage = createParentMessage()
            )

            (result.durationMs >= 0L) shouldBe true
        }
    }
})
