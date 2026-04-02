package com.anthropic.claudecode.integration

import com.anthropic.claudecode.commands.CommandRegistry
import com.anthropic.claudecode.commands.CommandContext
import com.anthropic.claudecode.commands.SessionStats
import com.anthropic.claudecode.services.TelemetryService
import com.anthropic.claudecode.services.doctor.DoctorService
import com.anthropic.claudecode.services.init.InitService
import com.anthropic.claudecode.services.session.SessionManager
import com.anthropic.claudecode.services.skill.SkillRegistry
import com.anthropic.claudecode.tools.ToolRegistry
import com.anthropic.claudecode.tools.impl.*
import com.anthropic.claudecode.utils.config.ClaudeMdParser
import com.anthropic.claudecode.utils.config.SettingsLoader
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * Integration tests - verify end-to-end component wiring
 * 集成测试 - 验证端到端组件连接
 */
class IntegrationTest : DescribeSpec({

    describe("Tool Registry Integration") {
        it("should register all built-in tools") {
            val registry = ToolRegistry()
            registry.registerAll(
                FileReadTool(), FileEditTool(), FileWriteTool(), DeleteFileTool(),
                GlobTool(), GrepTool(), ListFilesTool(),
                BashTool(), REPLTool(),
                WebFetchTool(), WebSearchTool(),
                NotebookEditTool(),
                AgentTool(), TaskCreateTool(), TodoWriteTool(),
                AskUserQuestionTool(),
                EnterPlanModeTool(), ExitPlanModeTool(),
                SkillTool(), LoadMcpTool(), ConnectSupabaseTool(),
                SleepTool(), ToolSearchTool(), CheckRuntimeTool(), ImageGenTool()
            )

            registry.size shouldBeGreaterThan 20
            registry.has("Read") shouldBe true
            registry.has("Bash") shouldBe true
            registry.has("AgentTool") shouldBe true
            registry.has("TodoWriteTool") shouldBe true
        }

        it("should find tools by alias") {
            val registry = ToolRegistry()
            registry.register(AgentTool())
            registry.register(SkillTool())

            registry.has("Agent") shouldBe true
            registry.has("Task") shouldBe true
            registry.has("Skill") shouldBe true
        }
    }

    describe("Command Registry Integration") {
        it("should register all built-in commands") {
            val registry = CommandRegistry()
            val commands = registry.getAllCommands()

            commands.size shouldBeGreaterThan 10
            registry.findCommand("help") shouldNotBe null
            registry.findCommand("cost") shouldNotBe null
            registry.findCommand("compact") shouldNotBe null
            registry.findCommand("exit") shouldNotBe null
        }

        it("should execute help command") {
            val registry = CommandRegistry()
            val context = CommandContext(
                commandRegistry = registry,
                sessionStats = SessionStats()
            )

            kotlinx.coroutines.runBlocking {
                val result = registry.executeCommand("/help", context)
                result shouldNotBe null
                result!!.output shouldContain "help"
            }
        }
    }

    describe("Settings + Config Integration") {
        it("should load settings from project dir") {
            val settings = SettingsLoader.load(System.getProperty("user.dir"))
            settings shouldNotBe null
        }

        it("should parse CLAUDE.md from project dir") {
            val config = ClaudeMdParser.loadAll(System.getProperty("user.dir"))
            config shouldNotBe null
        }
    }

    describe("Session Manager Integration") {
        it("should create and list sessions") {
            val tmpDir = File(System.getProperty("java.io.tmpdir"), "claude-test-sessions-${System.currentTimeMillis()}")
            tmpDir.mkdirs()
            val manager = SessionManager(tmpDir.absolutePath)

            val session = manager.createSession()
            session shouldNotBe null
            session.id.isNotBlank() shouldBe true

            // Cleanup
            tmpDir.deleteRecursively()
        }
    }

    describe("Doctor Service Integration") {
        it("should run all checks without crashing") {
            val doctor = DoctorService()
            val report = doctor.runAll()
            report.checks.size shouldBeGreaterThan 0
            report.passed shouldBeGreaterThan 0
        }
    }

    describe("Telemetry Service Integration") {
        it("should track mixed events correctly") {
            val telemetry = TelemetryService()

            telemetry.recordApiCall("claude-sonnet", 1000, 500, durationMs = 200)
            telemetry.recordToolUse("FileRead", 50, true)
            telemetry.recordToolUse("BashTool", 3000, true)
            telemetry.recordCommand("/help")
            telemetry.recordError("api", "timeout")

            val stats = telemetry.getStats()
            stats.totalEvents shouldBe 5
            stats.apiCallCount shouldBe 1
            stats.toolUseCount shouldBe 2
            stats.commandCount shouldBe 1
            stats.errorCount shouldBe 1
            stats.totalInputTokens shouldBe 1000
            stats.totalOutputTokens shouldBe 500
        }
    }

    describe("Skill Registry Integration") {
        it("should create default registry") {
            val registry = SkillRegistry.createDefault()
            registry shouldNotBe null
        }
    }
})
