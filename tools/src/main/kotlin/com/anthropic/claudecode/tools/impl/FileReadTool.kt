package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO

private val logger = KotlinLogging.logger {}

/**
 * FileReadTool - reads files from the filesystem with encoding detection
 * FileReadTool - 从文件系统读取文件，支持编码检测
 *
 * Maps from TypeScript FileReadTool/FileReadTool.ts.
 * Supports: text files with line numbers, images (base64),
 * offset/limit for partial reads, dedup detection.
 * 映射自 TypeScript FileReadTool/FileReadTool.ts。
 * 支持：带行号的文本文件、图片（base64）、偏移/限制的部分读取、去重检测。
 */
class FileReadTool : Tool<FileReadTool.Input, FileReadTool.Output> {

    override val name: String = "Read"

    override val aliases: List<String> = listOf("read", "cat", "FileRead")

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Read a file from the filesystem. " +
            "The file_path must be an absolute path. " +
            "Supports text files with line numbers, and images (PNG, JPG, GIF, WebP).",
        schema = kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("object"))
            put("properties", kotlinx.serialization.json.buildJsonObject {
                put("file_path", kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("string"))
                    put("description", kotlinx.serialization.json.JsonPrimitive("Absolute path to the file to read"))
                })
                put("offset", kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("integer"))
                    put("description", kotlinx.serialization.json.JsonPrimitive("Line number to start reading from (1-indexed)"))
                })
                put("limit", kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("integer"))
                    put("description", kotlinx.serialization.json.JsonPrimitive("Number of lines to read"))
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("file_path"))
            })
        }
    )

    override val maxResultSizeChars: Int = 30000

    override fun parseInput(rawInput: JsonObject): Input {
        return Input(
            filePath = rawInput["file_path"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required field: file_path"),
            offset = rawInput["offset"]?.jsonPrimitive?.intOrNull,
            limit = rawInput["limit"]?.jsonPrimitive?.intOrNull
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
        logger.info { "Reading file: $filePath" }

        // Validate path / 验证路径
        if (!filePath.startsWith("/")) {
            return ToolResult(
                output = Output.Error("File path must be absolute: $filePath"),
                isError = true
            )
        }

        val file = File(filePath)
        if (!file.exists()) {
            return ToolResult(
                output = Output.Error(buildNotFoundMessage(filePath)),
                isError = true
            )
        }

        if (file.isDirectory) {
            return ToolResult(
                output = Output.Error("Path is a directory, not a file: $filePath. Use ls via Bash tool to list directory contents."),
                isError = true
            )
        }

        // Check blocked device files / 检查被阻止的设备文件
        if (BLOCKED_PATHS.any { filePath.startsWith(it) }) {
            return ToolResult(
                output = Output.Error("Cannot read blocked device file: $filePath"),
                isError = true
            )
        }

        // Check file size / 检查文件大小
        val fileSize = file.length()
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            return ToolResult(
                output = Output.Error(
                    "File too large: ${fileSize / 1024}KB exceeds limit of ${MAX_FILE_SIZE_BYTES / 1024}KB"
                ),
                isError = true
            )
        }

        try {
            // Branch by file type / 按文件类型分支
            val extension = file.extension.lowercase()

            return when {
                extension in IMAGE_EXTENSIONS -> readImage(file)
                extension in BINARY_EXTENSIONS -> ToolResult(
                    output = Output.Error("Cannot read binary file: $filePath"),
                    isError = true
                )
                else -> readTextFile(file, input.offset, input.limit)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to read file: $filePath" }
            return ToolResult(
                output = Output.Error("Error reading file: ${e.message}"),
                isError = true
            )
        }
    }

    /**
     * Read a text file with line numbers
     * 读取带行号的文本文件
     */
    private fun readTextFile(file: File, offset: Int?, limit: Int?): ToolResult<Output> {
        val lines = file.readLines(detectCharset(file))
        val totalLines = lines.size

        // Apply offset and limit / 应用偏移和限制
        val startLine = ((offset ?: 1) - 1).coerceIn(0, totalLines)
        val endLine = if (limit != null) {
            (startLine + limit).coerceAtMost(totalLines)
        } else {
            totalLines
        }

        val selectedLines = lines.subList(startLine, endLine)

        // Add line numbers (cat -n format) / 添加行号（cat -n 格式）
        val content = selectedLines.mapIndexed { idx, line ->
            val lineNum = startLine + idx + 1
            val truncatedLine = if (line.length > MAX_LINE_LENGTH) {
                line.take(MAX_LINE_LENGTH) + "...(truncated)"
            } else {
                line
            }
            "%6d\t%s".format(lineNum, truncatedLine)
        }.joinToString("\n")

        return ToolResult(
            output = Output.TextFile(
                filePath = file.absolutePath,
                content = content,
                numLines = selectedLines.size,
                startLine = startLine + 1,
                totalLines = totalLines
            ),
            isError = false
        )
    }

    /**
     * Read an image file as base64
     * 读取图像文件为 base64
     */
    private fun readImage(file: File): ToolResult<Output> {
        val bytes = file.readBytes()
        val base64 = Base64.getEncoder().encodeToString(bytes)
        val mediaType = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }

        return ToolResult(
            output = Output.ImageFile(
                filePath = file.absolutePath,
                base64 = base64,
                mediaType = mediaType,
                originalSize = bytes.size.toLong()
            ),
            isError = false
        )
    }

    /**
     * Detect file charset / 检测文件编码
     */
    private fun detectCharset(file: File): Charset {
        val bytes = file.inputStream().use { it.readNBytes(4) }
        return when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                Charsets.UTF_16BE
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                Charsets.UTF_8
            else -> Charsets.UTF_8
        }
    }

    /**
     * Build a helpful not-found error message / 构建有用的文件未找到错误消息
     */
    private fun buildNotFoundMessage(filePath: String): String {
        val sb = StringBuilder("File not found: $filePath\n")
        val parent = File(filePath).parentFile
        if (parent != null && parent.exists()) {
            val similar = parent.listFiles()
                ?.filter { it.name.startsWith(File(filePath).name.take(3)) }
                ?.take(5)
                ?.map { it.name }
            if (!similar.isNullOrEmpty()) {
                sb.appendLine("Similar files in ${parent.absolutePath}:")
                similar.forEach { sb.appendLine("  - $it") }
            }
        }
        sb.appendLine("Current working directory: ${System.getProperty("user.dir")}")
        return sb.toString()
    }

    override suspend fun description(input: Input, options: DescriptionOptions): String {
        val fileName = File(input.filePath).name
        return if (input.offset != null || input.limit != null) {
            "Read $fileName (lines ${input.offset ?: 1}-${(input.offset ?: 1) + (input.limit ?: 0)})"
        } else {
            "Read $fileName"
        }
    }

    override suspend fun checkPermissions(input: Input, context: ToolUseContext): PermissionResult {
        return PermissionResult.Allow()
    }

    override fun isConcurrencySafe(input: Input): Boolean = true
    override fun isReadOnly(input: Input): Boolean = true
    override fun isSearchOrReadCommand(input: Input): Boolean = true

    override fun getActivityDescription(input: Input): String {
        return "Reading ${File(input.filePath).name}"
    }

    /**
     * Input for FileReadTool
     * FileReadTool 输入
     */
    data class Input(
        /** Absolute path to the file / 文件的绝对路径 */
        val filePath: String,
        /** Line number to start reading from (1-indexed) / 开始读取的行号（1 索引） */
        val offset: Int? = null,
        /** Number of lines to read / 要读取的行数 */
        val limit: Int? = null
    )

    /**
     * Output types for FileReadTool
     * FileReadTool 输出类型
     */
    sealed class Output {
        /** Text file content / 文本文件内容 */
        data class TextFile(
            val filePath: String,
            val content: String,
            val numLines: Int,
            val startLine: Int,
            val totalLines: Int
        ) : Output() {
            override fun toString(): String = content
        }

        /** Image file content / 图像文件内容 */
        data class ImageFile(
            val filePath: String,
            val base64: String,
            val mediaType: String,
            val originalSize: Long
        ) : Output() {
            override fun toString(): String = "[Image: $filePath ($originalSize bytes)]"
        }

        /** Error output / 错误输出 */
        data class Error(val message: String) : Output() {
            override fun toString(): String = message
        }
    }

    companion object {
        /** Maximum file size: 256 KB / 最大文件大小：256 KB */
        const val MAX_FILE_SIZE_BYTES = 256L * 1024

        /** Maximum line length before truncation / 截断前的最大行长度 */
        const val MAX_LINE_LENGTH = 2000

        /** Image file extensions / 图像文件扩展名 */
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "svg")

        /** Binary file extensions (not readable as text) / 二进制文件扩展名（不可作为文本读取） */
        val BINARY_EXTENSIONS = setOf(
            "exe", "dll", "so", "dylib", "bin", "obj", "o", "a", "lib",
            "zip", "tar", "gz", "bz2", "xz", "7z", "rar",
            "class", "jar", "war", "ear",
            "pyc", "pyd", "whl",
            "mp3", "mp4", "avi", "mov", "mkv", "flac", "wav",
            "ttf", "otf", "woff", "woff2", "eot",
            "ico", "bmp", "tiff", "psd",
            "sqlite", "db", "mdb"
        )

        /** Blocked device file paths / 被阻止的设备文件路径 */
        val BLOCKED_PATHS = listOf(
            "/dev/zero", "/dev/random", "/dev/urandom",
            "/dev/stdin", "/dev/stdout", "/dev/stderr",
            "/dev/tty", "/dev/console"
        )
    }
}
