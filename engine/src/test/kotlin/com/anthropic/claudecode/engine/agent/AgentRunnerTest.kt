package com.anthropic.claudecode.engine.agent

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Tests for AgentRunner / AgentRunner 测试
 */
class AgentRunnerTest : DescribeSpec({

    describe("AgentResult") {
        it("should have correct defaults") {
            val result = AgentResult(
                agentId = "test-1",
                agentType = "explore-agent",
                status = "completed",
                result = "Found 5 files"
            )
            result.agentId shouldBe "test-1"
            result.agentType shouldBe "explore-agent"
            result.status shouldBe "completed"
            result.result shouldContain "5 files"
            result.error shouldBe null
            result.iterations shouldBe 0
            result.toolResults shouldBe 0
        }

        it("should represent failed state") {
            val result = AgentResult(
                agentId = "test-2",
                agentType = "code-reviewer",
                status = "failed",
                error = "API timeout"
            )
            result.status shouldBe "failed"
            result.error shouldBe "API timeout"
            result.result shouldBe null
        }

        it("should track iterations and tool results") {
            val result = AgentResult(
                agentId = "test-3",
                agentType = "general-purpose",
                status = "completed",
                result = "Done",
                iterations = 5,
                toolResults = 12
            )
            result.iterations shouldBe 5
            result.toolResults shouldBe 12
        }
    }

    describe("AgentProgress") {
        it("should serialize correctly") {
            val progress = AgentProgress("agent-1", "running", "Processing...")
            progress.agentId shouldBe "agent-1"
            progress.status shouldBe "running"
            progress.message shouldBe "Processing..."
        }
    }

    describe("AgentState") {
        it("should track status changes") {
            val state = AgentState("agent-1", "explore-agent")
            state.status shouldBe AgentStatus.PENDING

            state.status = AgentStatus.RUNNING
            state.status shouldBe AgentStatus.RUNNING

            state.status = AgentStatus.COMPLETED
            state.status shouldBe AgentStatus.COMPLETED
        }
    }

    describe("AgentStatus") {
        it("should have all expected values") {
            AgentStatus.values().map { it.name } shouldBe
                listOf("PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED")
        }
    }
})
