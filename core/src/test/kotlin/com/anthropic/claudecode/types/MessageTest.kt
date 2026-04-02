package com.anthropic.claudecode.types

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tests for Message sealed class hierarchy serialization/deserialization
 * Message 密封类层次结构序列化/反序列化测试
 */
class MessageTest : DescribeSpec({

    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    describe("Message.User") {
        it("should create user message with text content") {
            val msg = Message.User(
                uuid = "msg-001",
                timestamp = "2024-01-01T00:00:00Z",
                content = listOf(ContentBlock.Text("Hello Claude"))
            )
            msg.uuid shouldBe "msg-001"
            msg.content.size shouldBe 1
            val textBlock = msg.content[0]
            textBlock.shouldBeInstanceOf<ContentBlock.Text>()
            (textBlock as ContentBlock.Text).text shouldBe "Hello Claude"
        }

        it("should support compact summary flag") {
            val msg = Message.User(
                uuid = "msg-002",
                timestamp = "2024-01-01T00:00:00Z",
                content = emptyList(),
                isCompactSummary = true
            )
            msg.isCompactSummary shouldBe true
        }
    }

    describe("Message.Assistant") {
        it("should create assistant message with model and stop reason") {
            val msg = Message.Assistant(
                uuid = "asst-001",
                timestamp = "2024-01-01T00:00:00Z",
                content = listOf(ContentBlock.Text("I can help with that.")),
                model = "claude-sonnet-4-20250514",
                stopReason = StopReason.END_TURN,
                costUsd = 0.003
            )
            msg.model shouldBe "claude-sonnet-4-20250514"
            msg.stopReason shouldBe StopReason.END_TURN
            msg.costUsd shouldBe 0.003
        }

        it("should create assistant message with tool use") {
            val msg = Message.Assistant(
                uuid = "asst-002",
                timestamp = "2024-01-01T00:00:00Z",
                content = listOf(
                    ContentBlock.Text("Let me read that file."),
                    ContentBlock.ToolUse(
                        id = "tu-001",
                        name = "Read",
                        input = kotlinx.serialization.json.buildJsonObject {
                            put("file_path", kotlinx.serialization.json.JsonPrimitive("/tmp/test.txt"))
                        }
                    )
                ),
                stopReason = StopReason.TOOL_USE
            )
            msg.content.size shouldBe 2
            msg.content[1].shouldBeInstanceOf<ContentBlock.ToolUse>()
            val toolUse = msg.content[1] as ContentBlock.ToolUse
            toolUse.name shouldBe "Read"
        }
    }

    describe("Message.System") {
        it("should create system message") {
            val msg = Message.System(
                uuid = "sys-001",
                timestamp = "2024-01-01T00:00:00Z",
                content = "Session started",
                isMeta = true
            )
            msg.content shouldBe "Session started"
            msg.isMeta shouldBe true
        }
    }

    describe("StopReason enum") {
        it("should have all expected values") {
            StopReason.entries.map { it.name } shouldBe listOf(
                "END_TURN", "MAX_TOKENS", "TOOL_USE", "STOP_SEQUENCE"
            )
        }
    }

    describe("ContentBlock sealed class") {
        it("should differentiate content block types") {
            val blocks: List<ContentBlock> = listOf(
                ContentBlock.Text("hello"),
                ContentBlock.ToolUse("tu1", "Bash", kotlinx.serialization.json.JsonObject(emptyMap())),
                ContentBlock.Thinking("I need to think about this...")
            )
            blocks[0].shouldBeInstanceOf<ContentBlock.Text>()
            blocks[1].shouldBeInstanceOf<ContentBlock.ToolUse>()
            blocks[2].shouldBeInstanceOf<ContentBlock.Thinking>()
        }
    }
})
