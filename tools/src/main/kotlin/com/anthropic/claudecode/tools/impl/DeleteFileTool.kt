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
 * DeleteFileTool - safely deletes files from the filesystem
 * DeleteFileTool - 安全地从文件系统中删除文件
 *
 * Maps from TypeScript tools/DeleteFileTool.
 * Only deletes files (not directories), requires absolute paths.
 * 映射自 TypeScript tools/DeleteFileTool。
 * 只删除文件（不删除目录），需要绝对路径。
 */
class DeleteFileTool : Tool<DeleteFileInput, DeleteFileOutput> {
    override val name = "DeleteFileTool"
    override val aliases = listOf("DeleteFile", "delete_file")

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Delete a file from the local filesystem. Requires an absolute path. Cannot delete directories.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): DeleteFileInput {
        return DeleteFileInput(
            filePath = rawInput["file_path"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing: file_path")
        )
    }

    override suspend fun call(
        input: DeleteFileInput, context: ToolUseContext, canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant, onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<DeleteFileOutput> {
        val file = File(input.filePath)

        // Validate absolute path / 验证绝对路径
        if (!file.isAbsolute) {
            return ToolResult(
                data = DeleteFileOutput(error = "Path must be absolute: ${input.filePath}"),
                output = DeleteFileOutput(error = "Path must be absolute"),
                isError = true
            )
        }

        // Check file exists / 检查文件存在
        if (!file.exists()) {
            return ToolResult(
                data = DeleteFileOutput(error = "File not found: ${input.filePath}"),
                output = DeleteFileOutput(error = "File not found"),
                isError = true
            )
        }

        // Only delete files, not directories / 只删除文件，不删除目录
        if (file.isDirectory) {
            return ToolResult(
                data = DeleteFileOutput(error = "Cannot delete directories: ${input.filePath}"),
                output = DeleteFileOutput(error = "Cannot delete directories"),
                isError = true
            )
        }

        return try {
            val size = file.length()
            val deleted = file.delete()
            if (deleted) {
                logger.info { "Deleted file: ${input.filePath} (${size} bytes)" }
                val output = DeleteFileOutput(
                    filePath = input.filePath,
                    deleted = true,
                    sizeBytes = size
                )
                ToolResult(data = output, output = output)
            } else {
                ToolResult(
                    data = DeleteFileOutput(error = "Failed to delete: ${input.filePath}"),
                    output = DeleteFileOutput(error = "Delete failed"),
                    isError = true
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Delete failed: ${input.filePath}" }
            ToolResult(
                data = DeleteFileOutput(error = "Error: ${e.message}"),
                output = DeleteFileOutput(error = e.message),
                isError = true
            )
        }
    }

    override suspend fun description(input: DeleteFileInput, options: DescriptionOptions): String {
        return "Delete ${File(input.filePath).name}"
    }

    override suspend fun checkPermissions(input: DeleteFileInput, context: ToolUseContext): PermissionResult {
        return PermissionResult.Ask(message = "Delete file: ${input.filePath}?")
    }

    override fun isDestructive(input: DeleteFileInput) = true
}

@Serializable data class DeleteFileInput(val filePath: String)
@Serializable data class DeleteFileOutput(
    val filePath: String? = null, val deleted: Boolean = false,
    val sizeBytes: Long? = null, val error: String? = null
)
