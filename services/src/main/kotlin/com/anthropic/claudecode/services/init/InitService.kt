package com.anthropic.claudecode.services.init

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * InitService - initializes project configuration
 * InitService - 初始化项目配置
 *
 * Maps from TypeScript commands/init.ts.
 * Creates .claude/ directory with default settings,
 * CLAUDE.md template, and .gitignore entries.
 * 映射自 TypeScript commands/init.ts。
 * 创建 .claude/ 目录，包含默认设置、
 * CLAUDE.md 模板和 .gitignore 条目。
 */
class InitService {

    /**
     * Initialize project configuration in the current directory
     * 在当前目录初始化项目配置
     */
    fun initProject(projectDir: String = System.getProperty("user.dir")): InitResult {
        val results = mutableListOf<String>()
        val claudeDir = File(projectDir, ".claude")

        // Create .claude directory / 创建 .claude 目录
        if (!claudeDir.exists()) {
            claudeDir.mkdirs()
            results.add("Created .claude/ directory")
        } else {
            results.add(".claude/ directory already exists")
        }

        // Create default settings.json / 创建默认 settings.json
        val settingsFile = File(claudeDir, "settings.json")
        if (!settingsFile.exists()) {
            settingsFile.writeText(DEFAULT_SETTINGS)
            results.add("Created .claude/settings.json")
        }

        // Create settings.local.json (gitignored) / 创建 settings.local.json（git 忽略）
        val localSettingsFile = File(claudeDir, "settings.local.json")
        if (!localSettingsFile.exists()) {
            localSettingsFile.writeText(DEFAULT_LOCAL_SETTINGS)
            results.add("Created .claude/settings.local.json")
        }

        // Create CLAUDE.md template / 创建 CLAUDE.md 模板
        val claudeMdFile = File(projectDir, "CLAUDE.md")
        if (!claudeMdFile.exists()) {
            claudeMdFile.writeText(DEFAULT_CLAUDE_MD)
            results.add("Created CLAUDE.md")
        }

        // Update .gitignore / 更新 .gitignore
        val gitignoreFile = File(projectDir, ".gitignore")
        val gitignoreUpdated = updateGitignore(gitignoreFile)
        if (gitignoreUpdated) {
            results.add("Updated .gitignore with .claude/settings.local.json")
        }

        logger.info { "Project initialized: ${results.size} actions" }
        return InitResult(actions = results, projectDir = projectDir)
    }

    private fun updateGitignore(gitignoreFile: File): Boolean {
        val entriesToAdd = listOf(
            ".claude/settings.local.json",
            ".claude/todos.json"
        )

        val existing = if (gitignoreFile.exists()) gitignoreFile.readLines().toSet() else emptySet()
        val newEntries = entriesToAdd.filter { it !in existing }

        if (newEntries.isEmpty()) return false

        val content = buildString {
            if (gitignoreFile.exists()) {
                append(gitignoreFile.readText())
                if (!endsWith("\n")) append("\n")
            }
            append("\n# Claude Code\n")
            for (entry in newEntries) {
                appendLine(entry)
            }
        }
        gitignoreFile.writeText(content)
        return true
    }

    companion object {
        val DEFAULT_SETTINGS = """{
  "permissions": {
    "allow": [],
    "deny": []
  },
  "hooks": {}
}
"""

        val DEFAULT_LOCAL_SETTINGS = """{
  "permissions": {
    "allow": []
  }
}
"""

        val DEFAULT_CLAUDE_MD = """# Project Instructions

<!-- Add project-specific instructions for Claude here -->
<!-- 在此添加项目特定的 Claude 指令 -->

## Build & Test
- Build: `./gradlew build`
- Test: `./gradlew test`
- Lint: `./gradlew check`

## Code Style
- Follow existing patterns in the codebase
- Add tests for new features
"""
    }
}

data class InitResult(
    val actions: List<String> = emptyList(),
    val projectDir: String = ""
) {
    fun toDisplayString(): String {
        val sb = StringBuilder()
        sb.appendLine("Project initialized / 项目已初始化")
        for (action in actions) {
            sb.appendLine("  + $action")
        }
        return sb.toString()
    }
}
