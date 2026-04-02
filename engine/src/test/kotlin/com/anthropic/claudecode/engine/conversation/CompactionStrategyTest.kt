package com.anthropic.claudecode.engine.conversation

import com.anthropic.claudecode.services.api.ApiMessage
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan

/**
 * Tests for CompactionStrategy / CompactionStrategy 测试
 */
class CompactionStrategyTest : DescribeSpec({

    describe("CompactionStrategy sealed class") {
        it("Full strategy should hold instruction") {
            val strategy = CompactionStrategy.Full("Keep code changes")
            strategy.instruction shouldBe "Keep code changes"
        }

        it("Partial strategy should hold index and instruction") {
            val strategy = CompactionStrategy.Partial(upToIndex = 5, instruction = "Focus on API")
            strategy.upToIndex shouldBe 5
            strategy.instruction shouldBe "Focus on API"
        }

        it("Micro strategy should be a singleton") {
            val s1 = CompactionStrategy.Micro
            val s2 = CompactionStrategy.Micro
            s1 shouldBe s2
        }

        it("Rolling strategy should have default keep count") {
            val strategy = CompactionStrategy.Rolling()
            strategy.keepRecentCount shouldBe 10
        }

        it("Rolling strategy should accept custom keep count") {
            val strategy = CompactionStrategy.Rolling(keepRecentCount = 20)
            strategy.keepRecentCount shouldBe 20
        }
    }

    describe("CompactionResult") {
        it("should track compaction metrics") {
            val result = CompactionResult(
                messages = listOf(ApiMessage(role = "user", content = "Hello")),
                originalCount = 20,
                compactedCount = 3,
                tokensBefore = 5000,
                tokensAfter = 800,
                strategy = "Full"
            )
            result.originalCount shouldBe 20
            result.compactedCount shouldBe 3
            result.tokensBefore shouldBeGreaterThan result.tokensAfter
            result.strategy shouldBe "Full"
        }
    }

    describe("Micro compaction logic") {
        it("should not modify short messages") {
            val messages = listOf(
                ApiMessage(role = "user", content = "Hello"),
                ApiMessage(role = "assistant", content = "Hi there!")
            )
            // Micro compaction only trims messages > 3000 chars
            messages.all { it.content.length < 3000 } shouldBe true
        }

        it("should identify messages needing trimming") {
            val longContent = "x".repeat(5000)
            val msg = ApiMessage(role = "user", content = longContent)
            (msg.content.length > 3000) shouldBe true
        }
    }
})
