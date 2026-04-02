package com.anthropic.claudecode.engine.context

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.longs.shouldBeGreaterThan

/**
 * Tests for ContextWindowManager
 * ContextWindowManager 测试
 */
class ContextWindowManagerTest : DescribeSpec({

    describe("ContextWindowManager") {
        it("should return 200K max tokens for sonnet") {
            val mgr = ContextWindowManager(model = "claude-sonnet-4-20250514")
            mgr.getMaxContextTokens() shouldBe 200_000L
        }

        it("should return 200K max tokens for opus") {
            val mgr = ContextWindowManager(model = "claude-opus-4-20250514")
            mgr.getMaxContextTokens() shouldBe 200_000L
        }

        it("should return correct max output tokens per model") {
            val sonnet = ContextWindowManager(model = "claude-sonnet-4-20250514")
            val opus = ContextWindowManager(model = "claude-opus-4-20250514")
            val haiku = ContextWindowManager(model = "claude-haiku-3-20240307")

            sonnet.getMaxOutputTokens() shouldBe 16_000
            opus.getMaxOutputTokens() shouldBe 32_000
            haiku.getMaxOutputTokens() shouldBe 8_192
        }

        it("should default to sonnet limits for unknown models") {
            val mgr = ContextWindowManager(model = "claude-unknown-1.0")
            mgr.getMaxContextTokens() shouldBe 200_000L
            mgr.getMaxOutputTokens() shouldBe 16_000
        }
    }
})
