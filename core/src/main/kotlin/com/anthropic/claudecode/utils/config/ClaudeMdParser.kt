package com.anthropic.claudecode.utils.config

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Path
import kotlin.io.path.*

private val logger = KotlinLogging.logger {}

/**
 * CLAUDE.md parser and loader
 * CLAUDE.md 解析器和加载器
 *
 * Maps from TypeScript utils/claudemd.ts.
 * Loads configuration from CLAUDE.md files at different levels:
 * - Global: /etc/claude-code/CLAUDE.md
 * - User: ~/.claude/CLAUDE.md
 * - Project: CLAUDE.md, .claude/CLAUDE.md, .claude/rules/{name}.md
 * - Local: CLAUDE.local.md
 * 映射自 TypeScript utils/claudemd.ts。
 * 从不同级别的 CLAUDE.md 文件加载配置。
 */
object ClaudeMdParser {

    /**
     * All loaded CLAUDE.md content merged together
     * 合并所有已加载的 CLAUDE.md 内容
     */
    data class ClaudeMdConfig(
        /** Merged content from all sources / 所有来源合并的内容 */
        val content: String,
        /** Individual sections by source / 按来源分隔的各部分 */
        val sections: List<ClaudeMdSection>,
        /** Include directives resolved / 已解析的包含指令 */
        val resolvedIncludes: Map<String, String> = emptyMap()
    )

    data class ClaudeMdSection(
        /** Source file path / 来源文件路径 */
        val sourcePath: String,
        /** Section content / 部分内容 */
        val content: String,
        /** Source level / 来源级别 */
        val level: ConfigLevel,
        /** Frontmatter metadata / 前言元数据 */
        val frontmatter: Map<String, String> = emptyMap()
    )

    enum class ConfigLevel {
        GLOBAL, USER, PROJECT, LOCAL
    }

    /**
     * Load all CLAUDE.md files for the given project directory
     * 为给定项目目录加载所有 CLAUDE.md 文件
     *
     * @param projectDir Project root directory / 项目根目录
     * @return Merged configuration / 合并的配置
     */
    fun loadAll(projectDir: String): ClaudeMdConfig {
        val sections = mutableListOf<ClaudeMdSection>()

        // 1. Global config / 全局配置
        loadSection("/etc/claude-code/CLAUDE.md", ConfigLevel.GLOBAL)?.let { sections.add(it) }

        // 2. User config / 用户配置
        val configDir = System.getenv("CLAUDE_CONFIG_DIR")
            ?: "${System.getProperty("user.home")}/.claude"
        loadSection("$configDir/CLAUDE.md", ConfigLevel.USER)?.let { sections.add(it) }

        // 3. Project config / 项目配置
        loadSection("$projectDir/CLAUDE.md", ConfigLevel.PROJECT)?.let { sections.add(it) }
        loadSection("$projectDir/.claude/CLAUDE.md", ConfigLevel.PROJECT)?.let { sections.add(it) }

        // 3b. Project rules directory / 项目规则目录
        val rulesDir = File("$projectDir/.claude/rules")
        if (rulesDir.isDirectory) {
            rulesDir.listFiles()
                ?.filter { it.extension == "md" }
                ?.sortedBy { it.name }
                ?.forEach { ruleFile ->
                    loadSection(ruleFile.absolutePath, ConfigLevel.PROJECT)?.let { sections.add(it) }
                }
        }

        // 4. Local config (git-ignored) / 本地配置（git 忽略）
        loadSection("$projectDir/CLAUDE.local.md", ConfigLevel.LOCAL)?.let { sections.add(it) }

        // Merge all content / 合并所有内容
        val merged = sections.joinToString("\n\n---\n\n") { it.content }

        logger.info { "Loaded ${sections.size} CLAUDE.md sections for $projectDir" }

        return ClaudeMdConfig(content = merged, sections = sections)
    }

    /**
     * Load a single CLAUDE.md section from a file
     * 从文件加载单个 CLAUDE.md 部分
     */
    private fun loadSection(path: String, level: ConfigLevel): ClaudeMdSection? {
        val file = File(path)
        if (!file.exists() || !file.isFile) return null

        return try {
            val raw = file.readText()
            val (frontmatter, content) = parseFrontmatter(raw)

            // Resolve @include directives / 解析 @include 指令
            val resolved = resolveIncludes(content, file.parentFile)

            ClaudeMdSection(
                sourcePath = path,
                content = resolved,
                level = level,
                frontmatter = frontmatter
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load CLAUDE.md from $path" }
            null
        }
    }

    /**
     * Parse YAML frontmatter from markdown content
     * 从 markdown 内容解析 YAML 前言
     */
    internal fun parseFrontmatter(raw: String): Pair<Map<String, String>, String> {
        if (!raw.startsWith("---")) return emptyMap<String, String>() to raw

        val endIdx = raw.indexOf("---", 3)
        if (endIdx < 0) return emptyMap<String, String>() to raw

        val frontmatterBlock = raw.substring(3, endIdx).trim()
        val content = raw.substring(endIdx + 3).trimStart()

        val metadata = mutableMapOf<String, String>()
        for (line in frontmatterBlock.lines()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                metadata[key] = value
            }
        }

        return metadata to content
    }

    /**
     * Resolve @include directives in content
     * 解析内容中的 @include 指令
     *
     * @include directives reference other files relative to the current file.
     * Circular references are prevented by tracking included paths.
     * @include 指令引用相对于当前文件的其他文件。
     * 通过跟踪已包含的路径来防止循环引用。
     */
    private fun resolveIncludes(
        content: String,
        baseDir: File,
        visited: MutableSet<String> = mutableSetOf()
    ): String {
        val includePattern = Regex("""@include\s+(\S+)""")

        return includePattern.replace(content) { matchResult ->
            val includePath = matchResult.groupValues[1]
            val resolvedFile = File(baseDir, includePath)
            val canonicalPath = resolvedFile.canonicalPath

            if (canonicalPath in visited) {
                "<!-- Circular include: $includePath -->"
            } else if (resolvedFile.exists() && resolvedFile.isFile) {
                visited.add(canonicalPath)
                val includeContent = resolvedFile.readText()
                resolveIncludes(includeContent, resolvedFile.parentFile, visited)
            } else {
                "<!-- Include not found: $includePath -->"
            }
        }
    }
}
