package com.anthropic.claudecode.services.skill

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.collections.shouldHaveSize
import java.io.File

/**
 * Tests for SkillRegistry
 * SkillRegistry 测试
 */
class SkillRegistryTest : DescribeSpec({

    describe("SkillRegistry") {
        it("should register and retrieve skills") {
            val registry = SkillRegistry()
            registry.register(SkillDefinition(
                name = "commit",
                description = "Create a git commit",
                instructions = "Run git add and git commit"
            ))
            registry.get("commit").shouldNotBeNull()
            registry.get("commit")!!.description shouldBe "Create a git commit"
        }

        it("should resolve aliases") {
            val registry = SkillRegistry()
            registry.register(SkillDefinition(
                name = "review-pr",
                description = "Review a pull request",
                aliases = listOf("pr-review", "review")
            ))
            registry.get("review-pr").shouldNotBeNull()
            registry.get("pr-review").shouldNotBeNull()
            registry.get("review").shouldNotBeNull()
        }

        it("should return null for unknown skills") {
            val registry = SkillRegistry()
            registry.get("nonexistent").shouldBeNull()
        }

        it("should load skills from directory") {
            val tempDir = kotlin.io.path.createTempDirectory("skill-test-").toFile()
            try {
                // Create a skill directory with SKILL.md
                val skillDir = File(tempDir, "test-skill")
                skillDir.mkdirs()
                File(skillDir, "SKILL.md").writeText("""
                    # test-skill
                    ## Description
                    A test skill for unit testing.
                    ## Instructions
                    Step 1: Do something
                    Step 2: Do something else
                """.trimIndent())

                val registry = SkillRegistry()
                registry.loadFromDirectory(tempDir.absolutePath)
                registry.getAll() shouldHaveSize 1
                registry.get("test-skill").shouldNotBeNull()
                registry.get("test-skill")!!.description shouldBe "A test skill for unit testing."
            } finally {
                tempDir.deleteRecursively()
            }
        }

        it("should return all unique skills") {
            val registry = SkillRegistry()
            registry.register(SkillDefinition(name = "a", aliases = listOf("a1")))
            registry.register(SkillDefinition(name = "b"))
            registry.getAll() shouldHaveSize 2
        }
    }
})
