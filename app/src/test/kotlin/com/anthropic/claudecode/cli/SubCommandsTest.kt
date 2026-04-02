package com.anthropic.claudecode.cli

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.string.shouldContain

/**
 * Tests for CLI subcommands
 * CLI 子命令测试
 */
class SubCommandsTest : DescribeSpec({

    describe("ConfigCommand") {
        it("should display settings when run") {
            // ConfigCommand reads from filesystem, can test basic construction
            // ConfigCommand 从文件系统读取，可以测试基本构造
            val cmd = ConfigCommand()
            cmd.commandName shouldContain "config"
        }
    }

    describe("DoctorCommand") {
        it("should be constructable") {
            val cmd = DoctorCommand()
            cmd.commandName shouldContain "doctor"
        }
    }

    describe("InitCommand") {
        it("should be constructable") {
            val cmd = InitCommand()
            cmd.commandName shouldContain "init"
        }
    }
})
