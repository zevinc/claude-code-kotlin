package com.anthropic.claudecode.tools

import com.anthropic.claudecode.types.*
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.collections.shouldContain
import kotlinx.serialization.json.JsonObject

/**
 * Tests for ToolRegistry
 * ToolRegistry 测试
 */
class ToolRegistryTest : DescribeSpec({

    // Simple test tool implementation
    // 简单的测试工具实现
    class DummyTool(
        override val name: String,
        override val aliases: List<String> = emptyList(),
        private val enabled: Boolean = true
    ) : Tool<String, String> {
        override val inputJSONSchema = ToolInputJSONSchema()
        override fun parseInput(rawInput: JsonObject) = "input"
        override suspend fun call(
            input: String,
            context: ToolUseContext,
            canUseTool: CanUseToolFn,
            parentMessage: Message.Assistant,
            onProgress: ((ToolProgressData) -> Unit)?
        ) = ToolResult(data = "ok")
        override suspend fun description(input: String, options: DescriptionOptions) = name
        override suspend fun checkPermissions(input: String, context: ToolUseContext): PermissionResult =
            PermissionResult.Allow()
        override fun isEnabled() = enabled
    }

    describe("ToolRegistry") {
        it("should register and retrieve tools by name") {
            val registry = ToolRegistry()
            val tool = DummyTool("TestTool")
            registry.register(tool)
            registry.get("TestTool").shouldNotBeNull()
            registry.get("TestTool")!!.name shouldBe "TestTool"
        }

        it("should return null for unknown tools") {
            val registry = ToolRegistry()
            registry.get("NonExistent").shouldBeNull()
        }

        it("should register and resolve aliases") {
            val registry = ToolRegistry()
            registry.register(DummyTool("FileRead", aliases = listOf("Read", "Cat")))
            registry.get("FileRead").shouldNotBeNull()
            registry.get("Read").shouldNotBeNull()
            registry.get("Cat").shouldNotBeNull()
            registry.get("Read")!!.name shouldBe "FileRead"
        }

        it("should track size correctly") {
            val registry = ToolRegistry()
            registry.size shouldBe 0
            registry.register(DummyTool("A"))
            registry.register(DummyTool("B"))
            registry.size shouldBe 2
        }

        it("should register multiple tools at once") {
            val registry = ToolRegistry()
            registry.registerAll(
                DummyTool("A"),
                DummyTool("B"),
                DummyTool("C")
            )
            registry.size shouldBe 3
        }

        it("should check if tool exists") {
            val registry = ToolRegistry()
            registry.register(DummyTool("Bash"))
            registry.has("Bash") shouldBe true
            registry.has("Unknown") shouldBe false
        }

        it("should filter enabled tools") {
            val registry = ToolRegistry()
            registry.registerAll(
                DummyTool("Enabled1", enabled = true),
                DummyTool("Disabled1", enabled = false),
                DummyTool("Enabled2", enabled = true)
            )
            registry.getEnabledTools().size shouldBe 2
        }

        it("should unregister tools and their aliases") {
            val registry = ToolRegistry()
            registry.register(DummyTool("MyTool", aliases = listOf("MT")))
            registry.has("MyTool") shouldBe true
            registry.has("MT") shouldBe true
            registry.unregister("MyTool")
            registry.has("MyTool") shouldBe false
            registry.has("MT") shouldBe false
        }

        it("should clear all tools") {
            val registry = ToolRegistry()
            registry.registerAll(DummyTool("A"), DummyTool("B"))
            registry.clear()
            registry.size shouldBe 0
        }

        it("should get all names including aliases") {
            val registry = ToolRegistry()
            registry.register(DummyTool("Tool1", aliases = listOf("T1")))
            registry.register(DummyTool("Tool2"))
            val names = registry.getAllNames()
            names shouldContain "Tool1"
            names shouldContain "T1"
            names shouldContain "Tool2"
        }
    }
})
