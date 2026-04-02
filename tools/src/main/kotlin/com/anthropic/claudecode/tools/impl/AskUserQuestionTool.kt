package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private val logger = KotlinLogging.logger {}

/**
 * AskUserQuestion tool - asks the user a question with optional choices
 * AskUserQuestion 工具 - 向用户提问，可选提供选项
 *
 * Maps from TypeScript tools/AskUserQuestionTool.
 * 映射自 TypeScript tools/AskUserQuestionTool。
 */
class AskUserQuestionTool : Tool<AskUserInput, AskUserOutput> {
    override val name = "AskUserQuestionTool"

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Ask the user a question to gather preferences, clarify instructions, or get decisions. Supports multiple choice options.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): AskUserInput {
        val questions = rawInput["questions"]?.jsonArray?.map { qElement ->
            val q = qElement.jsonObject
            QuestionDef(
                question = q["question"]?.jsonPrimitive?.content ?: "",
                header = q["header"]?.jsonPrimitive?.content ?: "",
                multiSelect = q["multiSelect"]?.jsonPrimitive?.booleanOrNull ?: false,
                options = q["options"]?.jsonArray?.map { opt ->
                    val o = opt.jsonObject
                    QuestionOption(
                        label = o["label"]?.jsonPrimitive?.content ?: "",
                        description = o["description"]?.jsonPrimitive?.content ?: ""
                    )
                } ?: emptyList()
            )
        } ?: emptyList()

        return AskUserInput(questions = questions)
    }

    override suspend fun call(
        input: AskUserInput,
        context: ToolUseContext,
        canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant,
        onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<AskUserOutput> {
        // This tool's output is rendered as a UI prompt to the user
        // The actual response comes back through the conversation flow
        // 此工具的输出作为 UI 提示呈现给用户
        // 实际响应通过对话流返回

        val questionSummary = input.questions.joinToString("; ") { it.question }
        logger.info { "Asking user: $questionSummary" }

        val output = AskUserOutput(
            questions = input.questions,
            status = "awaiting_response"
        )
        return ToolResult(data = output, output = output)
    }

    override suspend fun description(input: AskUserInput, options: DescriptionOptions): String {
        return "Ask user: ${input.questions.firstOrNull()?.question?.take(40) ?: "question"}"
    }

    override suspend fun checkPermissions(input: AskUserInput, context: ToolUseContext) = PermissionResult.Allow()
    override fun isReadOnly(input: AskUserInput) = true
}

@Serializable data class QuestionDef(
    val question: String, val header: String = "",
    val multiSelect: Boolean = false, val options: List<QuestionOption> = emptyList()
)
@Serializable data class QuestionOption(val label: String, val description: String = "")
@Serializable data class AskUserInput(val questions: List<QuestionDef>)
@Serializable data class AskUserOutput(
    val questions: List<QuestionDef> = emptyList(), val status: String = "pending"
)
