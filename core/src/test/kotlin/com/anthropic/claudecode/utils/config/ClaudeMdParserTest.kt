package com.anthropic.claudecode.utils.config

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import java.io.File

/**
 * Tests for ClaudeMdParser
 * ClaudeMdParser 测试
 */
class ClaudeMdParserTest : DescribeSpec({

    describe("parseFrontmatter") {
        it("should parse YAML frontmatter") {
            val raw = """---
title: My Config
version: 1.0
---
# Content here
Some instructions."""
            val (metadata, content) = ClaudeMdParser.parseFrontmatter(raw)
            metadata["title"] shouldBe "My Config"
            metadata["version"] shouldBe "1.0"
            content shouldBe "# Content here\nSome instructions."
        }

        it("should return empty metadata when no frontmatter") {
            val raw = "# Just content\nNo frontmatter here."
            val (metadata, content) = ClaudeMdParser.parseFrontmatter(raw)
            metadata shouldBe emptyMap()
            content shouldBe raw
        }

        it("should handle incomplete frontmatter") {
            val raw = "---\nkey: value\nNo closing marker"
            val (metadata, content) = ClaudeMdParser.parseFrontmatter(raw)
            metadata shouldBe emptyMap()
            content shouldBe raw
        }
    }

    describe("loadAll") {
        it("should load project CLAUDE.md") {
            val tempDir = createTempDir("claudemd-test-")
            try {
                File(tempDir, "CLAUDE.md").writeText("# Project Rules\nAlways use Kotlin.")
                val config = ClaudeMdParser.loadAll(tempDir.absolutePath)
                config.sections shouldHaveSize 1
                config.content.contains("Always use Kotlin") shouldBe true
                config.sections[0].level shouldBe ClaudeMdParser.ConfigLevel.PROJECT
            } finally {
                tempDir.deleteRecursively()
            }
        }

        it("should load from .claude directory") {
            val tempDir = createTempDir("claudemd-dotdir-")
            try {
                val claudeDir = File(tempDir, ".claude")
                claudeDir.mkdirs()
                File(claudeDir, "CLAUDE.md").writeText("# Dot directory config")
                val config = ClaudeMdParser.loadAll(tempDir.absolutePath)
                config.sections shouldHaveSize 1
            } finally {
                tempDir.deleteRecursively()
            }
        }

        it("should return empty config when no files exist") {
            val config = ClaudeMdParser.loadAll("/nonexistent/path/xyz")
            config.sections shouldHaveSize 0
            config.content shouldBe ""
        }

        it("should load rules from .claude/rules directory") {
            val tempDir = createTempDir("claudemd-rules-")
            try {
                val rulesDir = File(tempDir, ".claude/rules")
                rulesDir.mkdirs()
                File(rulesDir, "01-style.md").writeText("Use 4-space indentation.")
                File(rulesDir, "02-naming.md").writeText("Use camelCase for variables.")
                val config = ClaudeMdParser.loadAll(tempDir.absolutePath)
                config.sections shouldHaveSize 2
                config.content.contains("4-space indentation") shouldBe true
                config.content.contains("camelCase") shouldBe true
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }
})
