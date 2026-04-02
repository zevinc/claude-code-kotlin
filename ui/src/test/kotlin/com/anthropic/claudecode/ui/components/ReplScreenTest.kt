package com.anthropic.claudecode.ui.components

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty

/**
 * Tests for ReplScreen and ReplScreenState
 * ReplScreen 和 ReplScreenState 测试
 */
class ReplScreenTest : DescribeSpec({

    describe("ReplScreenState") {
        it("should have sensible defaults") {
            val state = ReplScreenState()
            state.messages shouldBe emptyList()
            state.inputBuffer shouldBe ""
            state.cursorPosition shouldBe 0
            state.isQueryInProgress shouldBe false
            state.isStreaming shouldBe false
            state.model.shouldNotBeEmpty()
        }

        it("should copy with updated fields") {
            val state = ReplScreenState()
            val updated = state.copy(
                inputBuffer = "hello",
                cursorPosition = 5,
                isQueryInProgress = true
            )
            updated.inputBuffer shouldBe "hello"
            updated.cursorPosition shouldBe 5
            updated.isQueryInProgress shouldBe true
        }
    }

    describe("ReplScreen") {
        it("should be constructable") {
            val screen = ReplScreen()
            // Basic construction test - rendering requires Lanterna context
            // 基本构造测试 - 渲染需要 Lanterna 上下文
        }
    }
})
