package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * SkillTool - invokes registered skills (slash commands)
 * SkillTool - 调用已注册的技能（斜杠命令）
 *
 * Maps from TypeScript tools/SkillTool.
 * Skills are pre-defined workflows that can be invoked by name.
 * Looks up skills from the SkillRegistry and returns their instructions.
 * 映射自 TypeScript tools/SkillTool。
 * 技能是可以按名称调用的预定义工作流。
 * 从 SkillRegistry 查找技能并返回其指令。
 */
class SkillTool : Tool<SkillInput, SkillOutput> {
    override val name = "SkillTool"
    override val aliases = listOf("Skill")

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Execute a skill within the conversation. Skills provide expert-level implementations for specialized tasks.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): SkillInput {
        return SkillInput(
            skill = rawInput["skill"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing: skill"),
            args = rawInput["args"]?.jsonPrimitive?.content
        )
    }

    override suspend fun call(
        input: SkillInput,
        context: ToolUseContext,
        canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant,
        onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<SkillOutput> {
        logger.info { "Invoking skill: ${input.skill}" }

        onProgress?.invoke(ToolProgressData.SkillToolProgress(
            skillName = input.skill, status = "resolving"
        ))

        // Look up skill from the default skill registry
        // 从默认技能注册表查找技能
        val registry = com.anthropic.claudecode.services.skill.SkillRegistry.createDefault()
        val skillDef = registry.get(input.skill)

        if (skillDef == null) {
            logger.warn { "Skill not found: ${input.skill}" }
            return ToolResult(
                data = SkillOutput(
                    skill = input.skill,
                    status = "error",
                    error = "Skill '${input.skill}' not found. Available: ${registry.getAll().joinToString { it.name }}"
                ),
                isError = true
            )
        }

        onProgress?.invoke(ToolProgressData.SkillToolProgress(
            skillName = input.skill, status = "executing"
        ))

        // Return skill instructions for the model to follow
        // 返回技能指令供模型遵循
        val result = buildString {
            appendLine("Skill: ${skillDef.name}")
            appendLine("Description: ${skillDef.description}")
            if (input.args != null) {
                appendLine("Arguments: ${input.args}")
            }
            appendLine()
            appendLine("Instructions:")
            appendLine(skillDef.instructions)
        }

        val output = SkillOutput(
            skill = input.skill,
            status = "completed",
            result = result
        )
        return ToolResult(data = output, output = output)
    }

    override suspend fun description(input: SkillInput, options: DescriptionOptions): String {
        return "Run skill: ${input.skill}"
    }

    override suspend fun checkPermissions(input: SkillInput, context: ToolUseContext) = PermissionResult.Allow()
}

@Serializable data class SkillInput(val skill: String, val args: String? = null)
@Serializable data class SkillOutput(
    val skill: String? = null, val status: String = "pending",
    val result: String? = null, val error: String? = null
)
