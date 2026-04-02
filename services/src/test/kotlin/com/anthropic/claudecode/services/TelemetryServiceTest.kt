package com.anthropic.claudecode.services

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo

/**
 * Tests for TelemetryService / TelemetryService 测试
 */
class TelemetryServiceTest : DescribeSpec({

    describe("TelemetryService") {
        it("should record tool use events") {
            val service = TelemetryService()
            service.recordToolUse("FileRead", 150, true)
            service.recordToolUse("BashTool", 3000, true)
            service.recordToolUse("FileRead", 80, false)

            val stats = service.getStats()
            stats.toolUseCount shouldBe 3
            stats.avgToolDurationMs shouldBeGreaterThan 0
        }

        it("should record API call events with token tracking") {
            val service = TelemetryService()
            service.recordApiCall("claude-sonnet", 1000, 500, cacheReadTokens = 200)
            service.recordApiCall("claude-sonnet", 2000, 800, cacheReadTokens = 300)

            val stats = service.getStats()
            stats.apiCallCount shouldBe 2
            stats.totalInputTokens shouldBe 3000
            stats.totalOutputTokens shouldBe 1300
            stats.totalCacheReadTokens shouldBe 500
        }

        it("should record command events") {
            val service = TelemetryService()
            service.recordCommand("/help")
            service.recordCommand("/cost")
            service.recordCommand("/help")

            val stats = service.getStats()
            stats.commandCount shouldBe 3
        }

        it("should record error events") {
            val service = TelemetryService()
            service.recordError("api", "Rate limited")
            service.recordError("tool", "File not found")

            val stats = service.getStats()
            stats.errorCount shouldBe 2
        }

        it("should compute top tools") {
            val service = TelemetryService()
            service.recordToolUse("FileRead", 100, true)
            service.recordToolUse("FileRead", 120, true)
            service.recordToolUse("FileRead", 90, true)
            service.recordToolUse("BashTool", 500, true)
            service.recordToolUse("Grep", 50, true)

            val stats = service.getStats()
            stats.topTools["FileRead"] shouldBe 3
            stats.topTools["BashTool"] shouldBe 1
        }

        it("should track session duration") {
            val service = TelemetryService()
            val stats = service.getStats()
            stats.sessionDurationSeconds shouldBeGreaterThanOrEqualTo 0
        }

        it("should not record when disabled") {
            val service = TelemetryService(enabled = false)
            service.recordToolUse("FileRead", 100, true)
            service.recordApiCall("claude", 500, 200)

            val stats = service.getStats()
            stats.totalEvents shouldBe 0
        }

        it("should record compaction events") {
            val service = TelemetryService()
            service.recordCompaction("Full", 5000, 800)

            val stats = service.getStats()
            stats.totalEvents shouldBe 1
        }
    }
})
