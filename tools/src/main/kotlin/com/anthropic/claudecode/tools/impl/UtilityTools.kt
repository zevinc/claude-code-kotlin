package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

// ==================== SleepTool ====================

/**
 * SleepTool - delays execution for a specified duration
 * SleepTool - 延迟执行指定时长
 */
class SleepTool : Tool<SleepInput, SleepOutput> {
    override val name = "SleepTool"
    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Delay execution for a specified number of milliseconds.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): SleepInput {
        return SleepInput(
            durationMs = rawInput["duration_ms"]?.jsonPrimitive?.longOrNull
                ?: rawInput["duration"]?.jsonPrimitive?.longOrNull
                ?: 1000L
        )
    }

    override suspend fun call(
        input: SleepInput, context: ToolUseContext, canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant, onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<SleepOutput> {
        val capped = input.durationMs.coerceAtMost(MAX_SLEEP_MS)
        delay(capped)
        return ToolResult(data = SleepOutput(sleptMs = capped), output = SleepOutput(sleptMs = capped))
    }

    override suspend fun description(input: SleepInput, options: DescriptionOptions) = "Sleep ${input.durationMs}ms"
    override suspend fun checkPermissions(input: SleepInput, context: ToolUseContext) = PermissionResult.Allow()
    override fun isReadOnly(input: SleepInput) = true

    companion object { const val MAX_SLEEP_MS = 300_000L }
}

@Serializable data class SleepInput(val durationMs: Long = 1000)
@Serializable data class SleepOutput(val sleptMs: Long = 0)

// ==================== ToolSearchTool ====================

/**
 * ToolSearchTool - searches available tools by name or description
 * ToolSearchTool - 按名称或描述搜索可用工具
 */
class ToolSearchTool : Tool<ToolSearchInput, ToolSearchOutput> {
    override val name = "ToolSearchTool"
    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Search available tools by name or description keyword.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): ToolSearchInput {
        return ToolSearchInput(
            query = rawInput["query"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing: query")
        )
    }

    override suspend fun call(
        input: ToolSearchInput, context: ToolUseContext, canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant, onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<ToolSearchOutput> {
        val query = input.query.lowercase()
        val allTools = context.options.tools

        val matches = allTools.filter { tool ->
            tool.name.lowercase().contains(query) ||
            (tool.inputJSONSchema.description?.lowercase()?.contains(query) == true) ||
            tool.aliases.any { it.lowercase().contains(query) } ||
            (tool.searchHint?.lowercase()?.contains(query) == true)
        }

        val results = matches.map { ToolSearchResult(name = it.name, description = it.inputJSONSchema.description ?: "") }
        return ToolResult(
            data = ToolSearchOutput(results = results, totalFound = results.size),
            output = ToolSearchOutput(results = results, totalFound = results.size)
        )
    }

    override suspend fun description(input: ToolSearchInput, options: DescriptionOptions) = "Search tools: ${input.query}"
    override suspend fun checkPermissions(input: ToolSearchInput, context: ToolUseContext) = PermissionResult.Allow()
    override fun isReadOnly(input: ToolSearchInput) = true
}

@Serializable data class ToolSearchInput(val query: String)
@Serializable data class ToolSearchResult(val name: String, val description: String)
@Serializable data class ToolSearchOutput(val results: List<ToolSearchResult> = emptyList(), val totalFound: Int = 0)

// ==================== ListFilesTool ====================

/**
 * ListFilesTool - lists files in a directory
 * ListFilesTool - 列出目录中的文件
 */
class ListFilesTool : Tool<ListFilesInput, ListFilesOutput> {
    override val name = "ListFilesTool"
    override val inputJSONSchema = ToolInputJSONSchema(
        description = "List files and directories in a given path. Returns names, sizes, and types.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): ListFilesInput {
        return ListFilesInput(
            path = rawInput["path"]?.jsonPrimitive?.content ?: System.getProperty("user.dir"),
            recursive = rawInput["recursive"]?.jsonPrimitive?.content?.toBoolean() ?: false
        )
    }

    override suspend fun call(
        input: ListFilesInput, context: ToolUseContext, canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant, onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<ListFilesOutput> {
        val dir = File(input.path)
        if (!dir.isDirectory) {
            return ToolResult(
                data = ListFilesOutput(error = "Not a directory: ${input.path}"),
                output = ListFilesOutput(error = "Not a directory"), isError = true
            )
        }

        val entries = if (input.recursive) {
            dir.walkTopDown()
                .filter { it != dir }
                .take(MAX_ENTRIES)
                .map { fileToEntry(it, dir) }
                .toList()
        } else {
            (dir.listFiles() ?: emptyArray())
                .take(MAX_ENTRIES)
                .map { fileToEntry(it, dir) }
        }

        val output = ListFilesOutput(entries = entries, totalCount = entries.size)
        return ToolResult(data = output, output = output)
    }

    private fun fileToEntry(file: File, baseDir: File): FileEntry {
        val relative = file.relativeTo(baseDir).path
        return FileEntry(
            name = relative,
            isDirectory = file.isDirectory,
            size = if (file.isFile) file.length() else null
        )
    }

    override suspend fun description(input: ListFilesInput, options: DescriptionOptions) = "List ${input.path}"
    override suspend fun checkPermissions(input: ListFilesInput, context: ToolUseContext) = PermissionResult.Allow()
    override fun isReadOnly(input: ListFilesInput) = true
    override fun isSearchOrReadCommand(input: ListFilesInput) = true

    companion object { const val MAX_ENTRIES = 500 }
}

@Serializable data class ListFilesInput(val path: String, val recursive: Boolean = false)
@Serializable data class FileEntry(val name: String, val isDirectory: Boolean = false, val size: Long? = null)
@Serializable data class ListFilesOutput(
    val entries: List<FileEntry> = emptyList(), val totalCount: Int = 0, val error: String? = null
)
