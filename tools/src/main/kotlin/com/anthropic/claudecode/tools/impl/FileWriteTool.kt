package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * FileWrite tool - writes content to files
 * FileWrite 工具 - 将内容写入文件
 *
 * Maps from TypeScript tools/FileWriteTool/FileWriteTool.ts.
 * Creates new files or overwrites existing ones.
 * 映射自 TypeScript tools/FileWriteTool/FileWriteTool.ts。
 * 创建新文件或覆盖已有文件。
 */
class FileWriteTool : Tool<FileWriteInput, FileWriteOutput> {
    override val name = "FileWriteTool"

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Write content to a file. Creates new files or overwrites existing ones. The file_path must be absolute.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): FileWriteInput {
        return FileWriteInput(
            filePath = rawInput["file_path"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing: file_path"),
            content = rawInput["content"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing: content")
        )
    }

    override suspend fun call(
        input: FileWriteInput,
        context: ToolUseContext,
        canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant,
        onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<FileWriteOutput> {
        val file = File(input.filePath)
        val isNew = !file.exists()

        return try {
            // Ensure parent directories exist / 确保父目录存在
            file.parentFile?.mkdirs()

            // Write content / 写入内容
            file.writeText(input.content)

            val output = FileWriteOutput(
                filePath = input.filePath,
                bytesWritten = input.content.toByteArray().size,
                isNew = isNew
            )

            logger.info {
                "${if (isNew) "Created" else "Updated"} file: ${input.filePath} " +
                "(${output.bytesWritten} bytes)"
            }

            ToolResult(data = output, output = output)
        } catch (e: Exception) {
            logger.error(e) { "FileWrite failed for ${input.filePath}" }
            ToolResult(
                data = FileWriteOutput(error = e.message),
                output = FileWriteOutput(error = e.message),
                isError = true
            )
        }
    }

    override suspend fun description(input: FileWriteInput, options: DescriptionOptions): String {
        return "Write ${input.filePath}"
    }

    override suspend fun checkPermissions(input: FileWriteInput, context: ToolUseContext): PermissionResult {
        // Check if writing to sensitive paths / 检查是否写入敏感路径
        val path = input.filePath
        if (path.contains(".env") || path.contains("credentials") || path.contains("secret")) {
            return PermissionResult.Ask(
                message = "Writing to potentially sensitive file: $path"
            )
        }
        return PermissionResult.Allow()
    }

    override suspend fun validateInput(input: FileWriteInput, context: ToolUseContext): ValidationResult {
        if (!input.filePath.startsWith("/")) {
            return ValidationResult.Failure("file_path must be absolute", errorCode = 400)
        }
        return ValidationResult.Success
    }

    override fun isDestructive(input: FileWriteInput) = true

    override fun getActivityDescription(input: FileWriteInput): String {
        return "Writing ${input.filePath}"
    }
}

@Serializable data class FileWriteInput(val filePath: String, val content: String)
@Serializable data class FileWriteOutput(
    val filePath: String? = null, val bytesWritten: Int = 0,
    val isNew: Boolean = false, val error: String? = null
)
