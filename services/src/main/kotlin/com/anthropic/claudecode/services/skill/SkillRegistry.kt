package com.anthropic.claudecode.services.skill

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * SkillRegistry - manages skill definitions and execution
 * SkillRegistry - 管理技能定义和执行
 *
 * Maps from TypeScript services/skills.
 * Skills are pre-defined workflows loaded from SKILL.md files.
 * 映射自 TypeScript services/skills。
 * 技能是从 SKILL.md 文件加载的预定义工作流。
 */
class SkillRegistry {
    private val skills = mutableMapOf<String, SkillDefinition>()

    /**
     * Register a skill / 注册技能
     */
    fun register(skill: SkillDefinition) {
        skills[skill.name] = skill
        skill.aliases.forEach { alias -> skills[alias] = skill }
        logger.debug { "Registered skill: ${skill.name}" }
    }

    /**
     * Look up a skill by name or alias / 按名称或别名查找技能
     */
    fun get(name: String): SkillDefinition? = skills[name]

    /**
     * Check if skill exists / 检查技能是否存在
     */
    fun has(name: String): Boolean = name in skills

    /**
     * Get all unique skills / 获取所有唯一技能
     */
    fun getAll(): List<SkillDefinition> = skills.values.distinctBy { it.name }

    /**
     * Load skills from a directory of SKILL.md files
     * 从 SKILL.md 文件目录加载技能
     */
    fun loadFromDirectory(dir: String) {
        val directory = File(dir)
        if (!directory.isDirectory) return

        directory.listFiles()?.filter { it.isDirectory }?.forEach { skillDir ->
            val skillMd = File(skillDir, "SKILL.md")
            if (skillMd.exists()) {
                try {
                    val definition = parseSkillMd(skillDir.name, skillMd.readText(), skillDir.absolutePath)
                    register(definition)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to load skill from ${skillDir.name}" }
                }
            }
        }
        logger.info { "Loaded ${getAll().size} skills from $dir" }
    }

    /**
     * Parse a SKILL.md file into a SkillDefinition
     * 将 SKILL.md 文件解析为 SkillDefinition
     */
    private fun parseSkillMd(name: String, content: String, basePath: String): SkillDefinition {
        val lines = content.lines()
        var description = ""
        var instructions = ""
        val aliases = mutableListOf<String>()
        var location = "built-in"

        var section = ""
        val instructionLines = mutableListOf<String>()

        for (line in lines) {
            when {
                line.startsWith("# ") -> { /* title, skip */ }
                line.startsWith("## Description") || line.startsWith("## description") -> section = "description"
                line.startsWith("## Instructions") || line.startsWith("## instructions") -> section = "instructions"
                line.startsWith("## Aliases") || line.startsWith("## aliases") -> section = "aliases"
                line.startsWith("## ") -> section = "other"
                section == "description" && line.isNotBlank() -> description = line.trim()
                section == "instructions" -> instructionLines.add(line)
                section == "aliases" && line.trim().startsWith("-") ->
                    aliases.add(line.trim().removePrefix("-").trim())
            }
        }
        instructions = instructionLines.joinToString("\n").trim()

        return SkillDefinition(
            name = name,
            description = description,
            instructions = instructions,
            aliases = aliases,
            basePath = basePath,
            location = location
        )
    }

    companion object {
        /**
         * Create registry with built-in skill paths
         * 创建带有内置技能路径的注册表
         */
        fun createDefault(): SkillRegistry {
            val registry = SkillRegistry()

            // Load from user skill directory
            // 从用户技能目录加载
            val userSkillDir = System.getenv("CLAUDE_SKILLS_DIR")
                ?: "${System.getProperty("user.home")}/.qoder/skills"
            registry.loadFromDirectory(userSkillDir)

            // Load from project skill directory
            // 从项目技能目录加载
            val projectSkillDir = "${System.getProperty("user.dir")}/.claude/skills"
            registry.loadFromDirectory(projectSkillDir)

            return registry
        }
    }
}

@Serializable
data class SkillDefinition(
    val name: String,
    val description: String = "",
    val instructions: String = "",
    val aliases: List<String> = emptyList(),
    val basePath: String = "",
    val location: String = "built-in"
)
