package com.anthropic.claudecode.services.hooks

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tests for HookEngine
 * HookEngine 测试
 */
class HookEngineTest : DescribeSpec({

    describe("HookEngine registration") {
        it("should register hooks for events") {
            val engine = HookEngine()
            engine.register(HookEvent.SESSION_START, HookDefinition(name = "test-session-hook",
                command = "echo session started",
                timeout = 5000L,
                type = HookType.COMMAND
            ))
            // Engine should not throw when registering
            // 注册时不应抛异常
        }

        it("should register from settings format") {
            val engine = HookEngine()
            val settingsMap = mapOf(
                "PreToolUse" to listOf(
                    HookSettingsEntry(command = "echo pre", timeout = 3000L)
                ),
                "PostToolUse" to listOf(
                    HookSettingsEntry(command = "echo post")
                )
            )
            engine.registerFromSettings(settingsMap)
            // Should successfully register without errors
        }
    }

    describe("HookEngine execution") {
        it("should return empty result when no hooks registered") {
            val engine = HookEngine()
            val result = engine.execute(HookEvent.SESSION_START, buildJsonObject {
                put("test", "value")
            })
            result.blocked shouldBe false
            result.results shouldHaveSize 0
        }

        it("should execute registered hooks") {
            val engine = HookEngine()
            engine.register(HookEvent.SESSION_START, HookDefinition(name = "test-session-hook",
                command = "echo hello",
                timeout = 5000L,
                type = HookType.COMMAND
            ))
            val result = engine.execute(HookEvent.SESSION_START, JsonObject(emptyMap()))
            // Result should contain the hook execution result
            // 结果应包含钩子执行结果
            result.event shouldBe HookEvent.SESSION_START
        }
    }

    describe("HookResult") {
        it("should create empty result") {
            val empty = HookResult.empty()
            empty.blocked shouldBe false
            empty.results shouldHaveSize 0
        }
    }
})
