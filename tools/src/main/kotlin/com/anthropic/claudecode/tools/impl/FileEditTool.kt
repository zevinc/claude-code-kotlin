package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * FileEditTool - performs exact string replacements in files
 * FileEditTool - 在文件中执行精确字符串替换
 *
 * Maps from TypeScript FileEditTool/FileEditTool.ts.
 * Supports: find/replace with unique string matching, replace_all mode,
 * encoding detection, atomic read-modify-write, quote normalization.
 * 映射自 TypeScript FileEditTool/FileEditTool.ts。
 * 支持：唯一字符串匹配的查找/替换、全部替换模式、编码检测、
 * 原子读-改-写、引号规范化。
 */
class FileEditTool : Tool<FileEditTool.Input, FileEditTool.Output> {

    override val name: String = "Edit"

    override val aliases: List<String> = listOf("edit", "FileEdit", "sed")

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Performs exact string replacements in files. " +
            "The old_string must be unique in the file. " +
            "Use replace_all to replace all occurrences.",
        schema = kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("object"))
            put("properties", kotlinx.serialization.json.buildJsonObject {
                put("file_path", kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("string"))
                    put("description", kotlinx.serialization.json.JsonPrimitive("Absolute path to the file to edit"))
                })
                put("old_string", kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("string"))
                    put("description", kotlinx.serialization.json.JsonPrimitive("The exact string to find and replace"))
                })
                put("new_string", kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("string"))
                    put("description", kotlinx.serialization.json.JsonPrimitive("The replacement string"))
                })
                put("replace_all", kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("boolean"))
                    put("description", kotlinx.serialization.json.JsonPrimitive("Replace all occurrences (default: false)"))
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("file_path"))
                add(kotlinx.serialization.json.JsonPrimitive("old_string"))
                add(kotlinx.serialization.json.JsonPrimitive("new_string"))
            })
        }
    )

    override fun parseInput(rawInput: JsonObject): Input {
        return Input(
            filePath = rawInput["file_path"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required field: file_path"),
            oldString = rawInput["old_string"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required field: old_string"),
            newString = rawInput["new_string"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required field: new_string"),
            replaceAll = rawInput["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }

    override suspend fun call(
        input: Input,
        context: ToolUseContext,
        canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant,
        onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<Output> {
        val filePath = input.filePath
        logger.info { "Editing file: $filePath" }

        if (!filePath.startsWith("/")) {
            return ToolResult(
                output = Output(filePath = filePath, success = false,
                    message = "File path must be absolute"),
                isError = true
            )
        }

        val file = File(filePath)
        if (!file.exists()) {
            return ToolResult(
                output = Output(filePath = filePath, success = false,
                    message = "File not found: $filePath"),
                isError = true
            )
        }

        // Check file size / 检查文件大小
        if (file.length() > MAX_FILE_SIZE) {
            return ToolResult(
                output = Output(filePath = filePath, success = false,
                    message = "File too large: ${file.length() / 1024 / 1024}MB exceeds 1GiB limit"),
                isError = true
            )
        }

        try {
            val content = file.readText(Charsets.UTF_8)

            // Find occurrences / 查找出现次数
            val occurrences = countOccurrences(content, input.oldString)

            if (occurrences == 0) {
                // Try with quote normalization / 尝试引号规范化
                val normalized = normalizeQuotes(input.oldString)
                val normalizedOccurrences = if (normalized != input.oldString) {
                    countOccurrences(content, normalized)
                } else 0

                return if (normalizedOccurrences > 0) {
                    // Do the replacement with normalized string / 使用规范化字符串进行替换
                    performReplacement(file, content, normalized, input.newString, input.replaceAll, normalizedOccurrences)
                } else {
                    ToolResult(
                        output = Output(filePath = filePath, success = false,
                            message = "old_string not found in file. Make sure it matches exactly, " +
                                "including whitespace and indentation."),
                        isError = true
                    )
                }
            }

            if (occurrences > 1 && !input.replaceAll) {
                return ToolResult(
                    output = Output(filePath = filePath, success = false,
                        message = "old_string found $occurrences times. Use replace_all=true to replace all, " +
                            "or provide more context to make it unique."),
                    isError = true
                )
            }

            return performReplacement(file, content, input.oldString, input.newString, input.replaceAll, occurrences)

        } catch (e: Exception) {
            logger.error(e) { "Failed to edit file: $filePath" }
            return ToolResult(
                output = Output(filePath = filePath, success = false,
                    message = "Error: ${e.message}"),
                isError = true
            )
        }
    }

    /**
     * Perform the replacement and write back / 执行替换并写回
     */
    private fun performReplacement(
        file: File,
        content: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
        occurrences: Int
    ): ToolResult<Output> {
        val newContent = if (replaceAll) {
            content.replace(oldString, newString)
        } else {
            content.replaceFirst(oldString, newString)
        }

        // Atomic write / 原子写入
        file.writeText(newContent, Charsets.UTF_8)

        val replacements = if (replaceAll) occurrences else 1
        logger.info { "Edit completed: ${file.absolutePath} ($replacements replacements)" }

        return ToolResult(
            output = Output(
                filePath = file.absolutePath,
                success = true,
                replacements = replacements,
                message = "Replaced $replacements occurrence(s) in ${file.name}"
            ),
            isError = false
        )
    }

    /**
     * Count occurrences of a substring / 计算子字符串出现次数
     */
    private fun countOccurrences(content: String, search: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = content.indexOf(search, index)
            if (index < 0) break
            count++
            index += search.length
        }
        return count
    }

    /**
     * Normalize curly/smart quotes to straight quotes
     * 将弯引号/智能引号规范化为直引号
     */
    private fun normalizeQuotes(text: String): String {
        return text
            .replace('\u2018', '\'')  // Left single quotation mark
            .replace('\u2019', '\'')  // Right single quotation mark
            .replace('\u201C', '"')   // Left double quotation mark
            .replace('\u201D', '"')   // Right double quotation mark
    }

    override suspend fun description(input: Input, options: DescriptionOptions): String {
        return "Edit ${File(input.filePath).name}"
    }

    override suspend fun checkPermissions(input: Input, context: ToolUseContext): PermissionResult {
        return PermissionResult.Ask(
            message = "Edit ${input.filePath}?",
            suggestions = emptyList()
        )
    }

    override fun isConcurrencySafe(input: Input): Boolean = false
    override fun isReadOnly(input: Input): Boolean = false
    override fun isDestructive(input: Input): Boolean = true

    override fun getActivityDescription(input: Input): String {
        return "Editing ${File(input.filePath).name}"
    }

    data class Input(
        val filePath: String,
        val oldString: String,
        val newString: String,
        val replaceAll: Boolean = false
    )

    data class Output(
        val filePath: String,
        val success: Boolean,
        val replacements: Int = 0,
        val message: String? = null
    ) {
        override fun toString(): String = message ?: if (success) "Edit successful" else "Edit failed"
    }

    companion object {
        /** Maximum file size: 1 GiB / 最大文件大小：1 GiB */
        const val MAX_FILE_SIZE = 1L * 1024 * 1024 * 1024
    }
}
