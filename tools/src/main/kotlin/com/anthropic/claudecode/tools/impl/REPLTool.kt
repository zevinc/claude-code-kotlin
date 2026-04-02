package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * REPLTool - execute code in a REPL environment
 * REPLTool - 在 REPL 环境中执行代码
 *
 * Maps from TypeScript tools/REPLTool.
 * Supports Python and Node.js execution.
 * 映射自 TypeScript tools/REPLTool。
 * 支持 Python 和 Node.js 执行。
 */
class REPLTool : Tool<REPLInput, REPLOutput> {
    override val name = "REPLTool"

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Execute code in a REPL (Python or Node.js). Returns stdout, stderr, and exit code.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): REPLInput {
        return REPLInput(
            code = rawInput["code"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing: code"),
            language = rawInput["language"]?.jsonPrimitive?.content ?: "python"
        )
    }

    override suspend fun call(
        input: REPLInput, context: ToolUseContext, canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant, onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<REPLOutput> {
        logger.info { "REPL execute: ${input.language}, ${input.code.take(60)}" }

        val interpreter = when (input.language.lowercase()) {
            "python", "py" -> listOf("python3", "-c")
            "node", "javascript", "js" -> listOf("node", "-e")
            else -> return ToolResult(
                data = REPLOutput(error = "Unsupported language: ${input.language}"),
                output = REPLOutput(error = "Unsupported language"), isError = true
            )
        }

        return try {
            val process = ProcessBuilder(interpreter + input.code)
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val completed = process.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)

            if (!completed) {
                process.destroyForcibly()
                return ToolResult(
                    data = REPLOutput(error = "Execution timed out"),
                    output = REPLOutput(error = "Timeout"), isError = true
                )
            }

            val output = REPLOutput(
                stdout = stdout.take(MAX_OUTPUT),
                stderr = stderr.take(MAX_OUTPUT),
                exitCode = process.exitValue()
            )
            ToolResult(data = output, output = output, isError = process.exitValue() != 0)
        } catch (e: Exception) {
            logger.error(e) { "REPL failed" }
            ToolResult(data = REPLOutput(error = e.message), output = REPLOutput(error = e.message), isError = true)
        }
    }

    override suspend fun description(input: REPLInput, options: DescriptionOptions): String {
        return "Run ${input.language}: ${input.code.take(40)}"
    }

    override suspend fun checkPermissions(input: REPLInput, context: ToolUseContext): PermissionResult {
        return PermissionResult.Ask(message = "Execute ${input.language} code?")
    }

    companion object {
        const val TIMEOUT_MS = 60_000L
        const val MAX_OUTPUT = 20_000
    }
}

@Serializable data class REPLInput(val code: String, val language: String = "python")
@Serializable data class REPLOutput(
    val stdout: String? = null, val stderr: String? = null,
    val exitCode: Int? = null, val error: String? = null
)
