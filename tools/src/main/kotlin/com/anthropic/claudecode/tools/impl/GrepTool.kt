package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * GrepTool - search file contents using regex patterns
 * GrepTool - 使用正则表达式模式搜索文件内容
 *
 * Maps from TypeScript tools/GrepTool/GrepTool.ts.
 * Built on ripgrep (rg) for fast searching.
 * 映射自 TypeScript tools/GrepTool/GrepTool.ts。
 * 基于 ripgrep (rg) 实现快速搜索。
 */
class GrepTool : Tool<GrepInput, GrepOutput> {
    override val name = "GrepTool"

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Search file contents using regex patterns. Supports context lines, file type filtering, and various output modes.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): GrepInput {
        return GrepInput(
            pattern = rawInput["pattern"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing: pattern"),
            path = rawInput["path"]?.jsonPrimitive?.content,
            glob = rawInput["glob"]?.jsonPrimitive?.content,
            type = rawInput["type"]?.jsonPrimitive?.content,
            contextLines = rawInput["-C"]?.jsonPrimitive?.intOrNull,
            beforeContext = rawInput["-B"]?.jsonPrimitive?.intOrNull,
            afterContext = rawInput["-A"]?.jsonPrimitive?.intOrNull,
            showLineNumbers = rawInput["-n"]?.jsonPrimitive?.booleanOrNull ?: false,
            caseInsensitive = rawInput["-i"]?.jsonPrimitive?.booleanOrNull ?: false,
            outputMode = rawInput["output_mode"]?.jsonPrimitive?.content ?: "files_with_matches",
            headLimit = rawInput["head_limit"]?.jsonPrimitive?.intOrNull
        )
    }

    override suspend fun call(
        input: GrepInput,
        context: ToolUseContext,
        canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant,
        onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<GrepOutput> {
        logger.info { "Grep: pattern='${input.pattern}' path=${input.path ?: "."}" }

        val searchPath = input.path ?: System.getProperty("user.dir")

        // Try ripgrep first, fall back to grep / 优先尝试 ripgrep，回退到 grep
        val useRipgrep = isRipgrepAvailable()

        val cmd = if (useRipgrep) {
            buildRipgrepCommand(input, searchPath)
        } else {
            buildGrepCommand(input, searchPath)
        }

        return try {
            val process = ProcessBuilder(cmd)
                .directory(File(searchPath))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            // grep/rg exit code 1 = no matches (not an error)
            // grep/rg 退出码 1 = 无匹配（非错误）
            if (exitCode > 1) {
                return ToolResult(
                    data = GrepOutput(error = "Search failed (exit $exitCode): ${output.take(200)}"),
                    output = GrepOutput(error = "Search failed"),
                    isError = true
                )
            }

            val lines = output.lines().filter { it.isNotBlank() }
            val limited = if (input.headLimit != null) lines.take(input.headLimit) else lines

            val result = GrepOutput(
                lines = limited,
                totalMatches = lines.size,
                truncated = input.headLimit != null && lines.size > input.headLimit,
                usedRipgrep = useRipgrep
            )
            ToolResult(data = result, output = result)
        } catch (e: Exception) {
            logger.error(e) { "Grep failed" }
            ToolResult(
                data = GrepOutput(error = e.message),
                output = GrepOutput(error = e.message),
                isError = true
            )
        }
    }

    private fun buildRipgrepCommand(input: GrepInput, path: String): List<String> {
        val cmd = mutableListOf("rg")
        when (input.outputMode) {
            "files_with_matches" -> cmd.add("-l")
            "count" -> cmd.add("-c")
            "content" -> { /* default rg output */ }
        }
        if (input.caseInsensitive) cmd.add("-i")
        if (input.showLineNumbers && input.outputMode == "content") cmd.add("-n")
        input.contextLines?.let { cmd.addAll(listOf("-C", it.toString())) }
        input.beforeContext?.let { cmd.addAll(listOf("-B", it.toString())) }
        input.afterContext?.let { cmd.addAll(listOf("-A", it.toString())) }
        input.glob?.let { cmd.addAll(listOf("--glob", it)) }
        input.type?.let { cmd.addAll(listOf("--type", it)) }
        cmd.add(input.pattern)
        cmd.add(path)
        return cmd
    }

    private fun buildGrepCommand(input: GrepInput, path: String): List<String> {
        val cmd = mutableListOf("grep", "-r")
        when (input.outputMode) {
            "files_with_matches" -> cmd.add("-l")
            "count" -> cmd.add("-c")
        }
        if (input.caseInsensitive) cmd.add("-i")
        if (input.showLineNumbers && input.outputMode == "content") cmd.add("-n")
        input.contextLines?.let { cmd.addAll(listOf("-C", it.toString())) }
        input.beforeContext?.let { cmd.addAll(listOf("-B", it.toString())) }
        input.afterContext?.let { cmd.addAll(listOf("-A", it.toString())) }
        input.glob?.let { cmd.addAll(listOf("--include", it)) }
        cmd.add(input.pattern)
        cmd.add(path)
        return cmd
    }

    private fun isRipgrepAvailable(): Boolean {
        return try {
            ProcessBuilder("rg", "--version").start().waitFor() == 0
        } catch (_: Exception) { false }
    }

    override suspend fun description(input: GrepInput, options: DescriptionOptions): String {
        return "Search: ${input.pattern}"
    }

    override suspend fun checkPermissions(input: GrepInput, context: ToolUseContext) = PermissionResult.Allow()
    override fun isReadOnly(input: GrepInput) = true
    override fun isConcurrencySafe(input: GrepInput) = true
    override fun isSearchOrReadCommand(input: GrepInput) = true

    override fun getActivityDescription(input: GrepInput): String {
        return "Searching: ${input.pattern}"
    }
}

@Serializable data class GrepInput(
    val pattern: String, val path: String? = null, val glob: String? = null,
    val type: String? = null, val contextLines: Int? = null,
    val beforeContext: Int? = null, val afterContext: Int? = null,
    val showLineNumbers: Boolean = false, val caseInsensitive: Boolean = false,
    val outputMode: String = "files_with_matches", val headLimit: Int? = null
)
@Serializable data class GrepOutput(
    val lines: List<String> = emptyList(), val totalMatches: Int = 0,
    val truncated: Boolean = false, val usedRipgrep: Boolean = false,
    val error: String? = null
)
