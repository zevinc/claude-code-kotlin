package com.anthropic.claudecode.ui.input

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Tests for InputProcessor / InputProcessor 测试
 */
class InputProcessorTest : DescribeSpec({

    describe("InputProcessor") {
        it("should process regular enter as submit") {
            val processor = InputProcessor()
            val action = processor.processEnter("hello world")
            action.shouldBeInstanceOf<InputAction.Submit>()
            (action as InputAction.Submit).text shouldBe "hello world"
        }

        it("should handle multi-line continuation with backslash") {
            val processor = InputProcessor()

            val action1 = processor.processEnter("first line\\")
            action1.shouldBeInstanceOf<InputAction.ContinueLine>()
            processor.isMultiLineMode shouldBe true

            val action2 = processor.processEnter("second line")
            action2.shouldBeInstanceOf<InputAction.Submit>()
            (action2 as InputAction.Submit).text shouldBe "first line\nsecond line"
        }

        it("should navigate history") {
            val processor = InputProcessor()
            processor.processEnter("first")
            processor.processEnter("second")
            processor.processEnter("third")

            processor.getHistorySize() shouldBe 3
            processor.historyUp() shouldBe "third"
            processor.historyUp() shouldBe "second"
            processor.historyDown() shouldBe "third"
        }

        it("should return empty on history down at bottom") {
            val processor = InputProcessor()
            processor.processEnter("hello")
            processor.historyDown() shouldBe ""
        }

        it("should not add blank to history") {
            val processor = InputProcessor()
            processor.processEnter("")
            processor.processEnter("   ")
            processor.getHistorySize() shouldBe 0
        }

        it("should not duplicate consecutive history entries") {
            val processor = InputProcessor()
            processor.processEnter("hello")
            processor.processEnter("hello")
            processor.getHistorySize() shouldBe 1
        }
    }

    describe("Tab completion") {
        it("should return null for non-slash input") {
            val processor = InputProcessor()
            processor.tabComplete("hello") shouldBe null
        }
    }
})
