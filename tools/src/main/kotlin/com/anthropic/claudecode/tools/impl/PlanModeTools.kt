package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * EnterPlanMode tool - transitions into planning mode
 * EnterPlanMode 工具 - 进入规划模式
 *
 * In plan mode, the assistant explores the codebase and designs
 * an implementation approach before writing code.
 * 在规划模式下，助手探索代码库并设计实现方案后再编写代码。
 */
class EnterPlanModeTool : Tool<EnterPlanInput, PlanModeOutput> {
    override val name = "EnterPlanModeTool"

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Enter plan mode to design an implementation approach before coding. Use for non-trivial tasks.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): EnterPlanInput {
        return EnterPlanInput(
            planFileName = rawInput["plan_file_name"]?.jsonPrimitive?.content ?: "plan"
        )
    }

    override suspend fun call(
        input: EnterPlanInput, context: ToolUseContext, canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant, onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<PlanModeOutput> {
        logger.info { "Entering plan mode: ${input.planFileName}" }
        val output = PlanModeOutput(mode = "plan", planFileName = input.planFileName, active = true)
        return ToolResult(data = output, output = output)
    }

    override suspend fun description(input: EnterPlanInput, options: DescriptionOptions) = "Enter plan mode"
    override suspend fun checkPermissions(input: EnterPlanInput, context: ToolUseContext) = PermissionResult.Allow()
    override fun isReadOnly(input: EnterPlanInput) = true
}

/**
 * ExitPlanMode tool - exits planning mode and begins implementation
 * ExitPlanMode 工具 - 退出规划模式并开始实现
 */
class ExitPlanModeTool : Tool<ExitPlanInput, PlanModeOutput> {
    override val name = "ExitPlanModeTool"

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Exit plan mode after presenting plan to user. Transitions to implementation.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): ExitPlanInput {
        return ExitPlanInput(
            overview = rawInput["overview"]?.jsonPrimitive?.content ?: "",
            specFilePath = rawInput["specFilePath"]?.jsonPrimitive?.content
        )
    }

    override suspend fun call(
        input: ExitPlanInput, context: ToolUseContext, canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant, onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<PlanModeOutput> {
        logger.info { "Exiting plan mode: ${input.overview.take(60)}" }
        val output = PlanModeOutput(mode = "implement", overview = input.overview, active = false)
        return ToolResult(data = output, output = output)
    }

    override suspend fun description(input: ExitPlanInput, options: DescriptionOptions) = "Exit plan mode"
    override suspend fun checkPermissions(input: ExitPlanInput, context: ToolUseContext) = PermissionResult.Allow()
}

@Serializable data class EnterPlanInput(val planFileName: String = "plan")
@Serializable data class ExitPlanInput(val overview: String = "", val specFilePath: String? = null)
@Serializable data class PlanModeOutput(
    val mode: String = "plan", val planFileName: String? = null,
    val overview: String? = null, val active: Boolean = true
)
