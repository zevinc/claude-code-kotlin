package com.anthropic.claudecode.engine.prompt

import com.anthropic.claudecode.utils.config.ClaudeMdParser
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThan

/**
 * Tests for SystemPromptBuilder / SystemPromptBuilder 测试
 */
class SystemPromptBuilderTest : DescribeSpec({

    describe("SystemPromptBuilder") {
        it("should build basic system prompt") {
            val builder = SystemPromptBuilder()
            val prompt = builder.build()
            prompt shouldContain "Claude"
            prompt shouldContain "coding agent"
            prompt.length shouldBeGreaterThan 100
        }

        it("should include CLAUDE.md config") {
            val config = ClaudeMdParser.ClaudeMdConfig(
                content = "Use Kotlin style\nAlways add tests",
                sections = listOf(
                    ClaudeMdParser.ClaudeMdSection(
                        sourcePath = "CLAUDE.md",
                        content = "Use Kotlin style",
                        level = ClaudeMdParser.ConfigLevel.PROJECT
                    ),
                    ClaudeMdParser.ClaudeMdSection(
                        sourcePath = ".claude/rules/tests.md",
                        content = "Always add tests",
                        level = ClaudeMdParser.ConfigLevel.PROJECT
                    )
                )
            )
            val builder = SystemPromptBuilder(claudeMdConfig = config)
            val prompt = builder.build()
            prompt shouldContain "Kotlin style"
            prompt shouldContain "Always add tests"
        }

        it("should include custom instructions") {
            val builder = SystemPromptBuilder(customInstructions = "Be concise")
            val prompt = builder.build()
            prompt shouldContain "Be concise"
        }

        it("should include environment info") {
            val builder = SystemPromptBuilder()
            val prompt = builder.build()
            prompt shouldContain "Platform:"
            prompt shouldContain "Working directory:"
        }

        it("should include model info") {
            val builder = SystemPromptBuilder(model = "claude-opus-4-20250514")
            val prompt = builder.build()
            prompt shouldContain "claude-opus-4-20250514"
        }
    }
})
