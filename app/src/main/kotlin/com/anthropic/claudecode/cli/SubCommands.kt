package com.anthropic.claudecode.cli

import com.anthropic.claudecode.services.doctor.DoctorService
import com.anthropic.claudecode.services.init.InitService
import com.anthropic.claudecode.utils.config.SettingsLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * DoctorCommand - diagnose environment issues
 * DoctorCommand - 诊断环境问题
 *
 * Maps from TypeScript commands/doctor.
 * Runs health checks and reports findings.
 * 映射自 TypeScript commands/doctor。
 * 运行健康检查并报告结果。
 */
class DoctorCommand : CliktCommand(
    name = "doctor",
    help = "Check your environment for potential issues / 检查环境是否存在潜在问题"
) {
    override fun run() {
        echo("Claude Code Doctor")
        echo("==================")
        echo()

        val doctor = DoctorService()
        val report = doctor.runAll()

        for (check in report.checks) {
            val icon = when (check.status) {
                com.anthropic.claudecode.services.doctor.CheckStatus.PASS -> "[OK]"
                com.anthropic.claudecode.services.doctor.CheckStatus.WARN -> "[WARN]"
                com.anthropic.claudecode.services.doctor.CheckStatus.FAIL -> "[FAIL]"
            }
            echo("$icon ${check.name}: ${check.detail}")
        }

        echo()
        if (report.healthy) {
            echo("All checks passed! Your environment is ready.")
        } else {
            echo("${report.failed} failures, ${report.warnings} warnings found.")
        }
    }
}

/**
 * InitCommand - initialize a new project
 * InitCommand - 初始化新项目
 *
 * Maps from TypeScript commands/init.
 * Creates .claude/ directory and default configuration.
 * 映射自 TypeScript commands/init。
 * 创建 .claude/ 目录和默认配置。
 */
class InitCommand : CliktCommand(
    name = "init",
    help = "Initialize Claude Code in the current directory / 在当前目录初始化 Claude Code"
) {
    private val force by option("--force", "-f", help = "Overwrite existing config").flag()

    override fun run() {
        echo("Initializing Claude Code...")

        val projectDir = System.getProperty("user.dir")
        val initService = InitService()

        try {
            val result = initService.initProject(projectDir)
            for (action in result.actions) {
                echo("  $action")
            }
            echo()
            echo("Claude Code initialized in: $projectDir/.claude/")
            echo("Edit CLAUDE.md to customize project instructions.")
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
        }
    }
}

/**
 * ConfigCommand - view/edit configuration
 * ConfigCommand - 查看/编辑配置
 *
 * Maps from TypeScript commands/config.
 * Displays current settings from all sources.
 * 映射自 TypeScript commands/config。
 * 显示来自所有来源的当前设置。
 */
class ConfigCommand : CliktCommand(
    name = "config",
    help = "View current configuration / 查看当前配置"
) {
    private val key by argument(help = "Configuration key to view").optional()

    override fun run() {
        val projectDir = System.getProperty("user.dir")
        val settings = SettingsLoader.load(projectDir)

        if (key != null) {
            val value = when (key) {
                "mode" -> settings.mode ?: "default"
                "model" -> settings.model ?: "claude-sonnet-4-20250514"
                "maxBudget" -> settings.maxBudget?.toString() ?: "unlimited"
                "theme" -> settings.theme ?: "default"
                "allowRules" -> settings.allowRules.joinToString(", ").ifEmpty { "(none)" }
                "denyRules" -> settings.denyRules.joinToString(", ").ifEmpty { "(none)" }
                else -> "Unknown key: $key"
            }
            echo("$key = $value")
        } else {
            echo("Claude Code Configuration")
            echo("=========================")
            echo("  mode:      ${settings.mode ?: "default"}")
            echo("  model:     ${settings.model ?: "claude-sonnet-4-20250514"}")
            echo("  maxBudget: ${settings.maxBudget ?: "unlimited"}")
            echo("  theme:     ${settings.theme ?: "default"}")
            echo("  allowRules (${settings.allowRules.size}):")
            settings.allowRules.forEach { echo("    - $it") }
            echo("  denyRules (${settings.denyRules.size}):")
            settings.denyRules.forEach { echo("    - $it") }
            echo("  hooks (${settings.hooks.size}):")
            settings.hooks.forEach { (event, hooks) ->
                echo("    $event: ${hooks.size} hook(s)")
            }
            echo()
            echo("Sources: ~/.claude/settings.json, .claude/settings.json, .claude/settings.local.json")
        }
    }
}
