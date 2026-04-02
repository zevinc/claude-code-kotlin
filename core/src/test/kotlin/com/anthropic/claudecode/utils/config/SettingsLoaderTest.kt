package com.anthropic.claudecode.utils.config

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import java.io.File

/**
 * Tests for SettingsLoader
 * SettingsLoader 测试
 */
class SettingsLoaderTest : DescribeSpec({

    describe("SettingsLoader.load") {
        it("should return default settings when no config files exist") {
            val settings = SettingsLoader.load("/nonexistent/path")
            settings.mode shouldBe null
            settings.apiKey shouldBe null
            settings.model shouldBe null
            settings.maxBudget shouldBe null
            settings.allowRules shouldBe emptyList()
            settings.denyRules shouldBe emptyList()
            settings.hooks shouldBe emptyMap()
        }

        it("should load settings from a project directory") {
            // Create temp project with settings
            // 创建带设置的临时项目
            val tempDir = createTempDir("claude-test-")
            try {
                val claudeDir = File(tempDir, ".claude")
                claudeDir.mkdirs()
                File(claudeDir, "settings.json").writeText("""
                    {
                        "mode": "default",
                        "model": "claude-sonnet-4-20250514",
                        "allowRules": ["Bash(git *)"],
                        "theme": "dark"
                    }
                """.trimIndent())

                val settings = SettingsLoader.load(tempDir.absolutePath)
                settings.mode shouldBe "default"
                settings.model shouldBe "claude-sonnet-4-20250514"
                settings.allowRules shouldBe listOf("Bash(git *)")
                settings.theme shouldBe "dark"
            } finally {
                tempDir.deleteRecursively()
            }
        }

        it("should merge settings from multiple sources") {
            val tempDir = createTempDir("claude-merge-")
            try {
                val claudeDir = File(tempDir, ".claude")
                claudeDir.mkdirs()

                // Project settings
                File(claudeDir, "settings.json").writeText("""
                    {
                        "mode": "default",
                        "allowRules": ["Bash(git *)"]
                    }
                """.trimIndent())

                // Local settings override
                File(claudeDir, "settings.local.json").writeText("""
                    {
                        "mode": "acceptEdits",
                        "model": "claude-opus-4-20250514"
                    }
                """.trimIndent())

                val settings = SettingsLoader.load(tempDir.absolutePath)
                // Local should override project
                settings.mode shouldBe "acceptEdits"
                settings.model shouldBe "claude-opus-4-20250514"
                // Project rules should be accumulated
                settings.allowRules shouldBe listOf("Bash(git *)")
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }
})
