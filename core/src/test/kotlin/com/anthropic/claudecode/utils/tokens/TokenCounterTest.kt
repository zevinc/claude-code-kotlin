package com.anthropic.claudecode.utils.tokens

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/**
 * Tests for TokenCounter and CostCalculator
 * TokenCounter 和 CostCalculator 测试
 */
class TokenCounterTest : DescribeSpec({

    describe("TokenCounter.estimateTokens") {
        val counter = TokenCounter()

        it("should return 1 for empty string") {
            // Minimum is 1 token
            counter.estimateTokens("") shouldBe 0
        }

        it("should estimate English text tokens") {
            // "Hello world" = 11 chars, ~2.75 tokens, rounds to at least 1
            val tokens = counter.estimateTokens("Hello world")
            tokens shouldBeGreaterThan 0
        }

        it("should estimate CJK text with higher token density") {
            val enTokens = counter.estimateTokens("a".repeat(100))
            val cjkTokens = counter.estimateTokens("\u4f60".repeat(100))
            // CJK should produce more tokens per character
            // CJK 每个字符应产生更多令牌
            cjkTokens shouldBeGreaterThan enTokens
        }

        it("should handle mixed content") {
            val tokens = counter.estimateTokens("Hello world \u4f60\u597d\u4e16\u754c")
            tokens shouldBeGreaterThan 0
        }
    }

    describe("TokenCounter.recordUsage") {
        it("should accumulate token usage") {
            val counter = TokenCounter()
            counter.recordUsage(inputTokens = 100, outputTokens = 50)
            counter.recordUsage(inputTokens = 200, outputTokens = 100)
            counter.inputTokens shouldBe 300
            counter.outputTokens shouldBe 150
            counter.apiCalls shouldBe 2
        }

        it("should track cache tokens") {
            val counter = TokenCounter()
            counter.recordUsage(cacheReadTokens = 500, cacheCreateTokens = 1000)
            counter.cacheReadTokens shouldBe 500
            counter.cacheCreateTokens shouldBe 1000
        }

        it("should reset correctly") {
            val counter = TokenCounter()
            counter.recordUsage(inputTokens = 100, outputTokens = 50)
            counter.reset()
            counter.inputTokens shouldBe 0
            counter.outputTokens shouldBe 0
            counter.apiCalls shouldBe 0
        }
    }

    describe("TokenCounter.getSummary") {
        it("should return correct summary") {
            val counter = TokenCounter()
            counter.recordUsage(inputTokens = 1000, outputTokens = 500)
            val summary = counter.getSummary()
            summary.inputTokens shouldBe 1000
            summary.outputTokens shouldBe 500
            summary.totalTokens shouldBe 1500
            summary.apiCalls shouldBe 1
        }
    }

    describe("CostCalculator") {
        it("should calculate sonnet costs correctly") {
            val usage = TokenUsageSummary(
                inputTokens = 1_000_000,
                outputTokens = 1_000_000
            )
            val cost = CostCalculator.calculate("claude-sonnet-4-20250514", usage)
            // Sonnet: $3/M input, $15/M output
            cost.inputCost shouldBe 3.0
            cost.outputCost shouldBe 15.0
            cost.totalCost shouldBe 18.0
        }

        it("should calculate opus costs correctly") {
            val usage = TokenUsageSummary(
                inputTokens = 1_000_000,
                outputTokens = 1_000_000
            )
            val cost = CostCalculator.calculate("claude-opus-4-20250514", usage)
            // Opus: $15/M input, $75/M output
            cost.inputCost shouldBe 15.0
            cost.outputCost shouldBe 75.0
            cost.totalCost shouldBe 90.0
        }

        it("should calculate haiku costs correctly") {
            val usage = TokenUsageSummary(
                inputTokens = 1_000_000,
                outputTokens = 1_000_000
            )
            val cost = CostCalculator.calculate("claude-haiku-3-20240307", usage)
            cost.inputCost shouldBe 0.25
            cost.outputCost shouldBe 1.25
        }

        it("should include cache costs") {
            val usage = TokenUsageSummary(
                inputTokens = 0,
                outputTokens = 0,
                cacheReadTokens = 1_000_000,
                cacheCreateTokens = 1_000_000
            )
            val cost = CostCalculator.calculate("claude-sonnet-4-20250514", usage)
            cost.cacheReadCost shouldBe 0.3
            cost.cacheCreateCost shouldBe 3.75
        }

        it("should format costs correctly") {
            CostCalculator.formatCost(1.50) shouldBe "$1.50"
            CostCalculator.formatCost(0.003) shouldStartWith "$0.003"
        }
    }
})
