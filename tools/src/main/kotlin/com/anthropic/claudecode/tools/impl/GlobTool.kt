package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

private val logger = KotlinLogging.logger {}

/**
 * GlobTool - fast file pattern matching
 * GlobTool - 快速文件模式匹配
 *
 * Maps from TypeScript tools/GlobTool/GlobTool.ts.
 * Supports glob patterns like "{dir}/{file}.kt", returns matching file paths sorted by modification time.
 * 映射自 TypeScript tools/GlobTool/GlobTool.ts。
 * 支持 glob 模式如 "{dir}/{file}.kt"，返回按修改时间排序的匹配文件路径。
 */
class GlobTool : Tool<GlobInput, GlobOutput> {
    override val name = "GlobTool"

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Fast file pattern matching tool. Supports glob patterns like '{dir}/{file}.js' or 'src/{dir}/{file}.ts'. Returns matching file paths sorted by modification time.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): GlobInput {
        return GlobInput(
            pattern = rawInput["pattern"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing: pattern"),
            path = rawInput["path"]?.jsonPrimitive?.content,
            limit = rawInput["limit"]?.jsonPrimitive?.intOrNull
        )
    }

    override suspend fun call(
        input: GlobInput,
        context: ToolUseContext,
        canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant,
        onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<GlobOutput> {
        val baseDir = input.path ?: System.getProperty("user.dir")
        val basePath = Paths.get(baseDir)

        if (!Files.isDirectory(basePath)) {
            return ToolResult(
                data = GlobOutput(error = "Directory not found: $baseDir"),
                output = GlobOutput(error = "Directory not found"),
                isError = true
            )
        }

        return try {
            val matcher = FileSystems.getDefault().getPathMatcher("glob:${input.pattern}")
            val matches = mutableListOf<GlobMatch>()
            val maxResults = input.limit ?: DEFAULT_LIMIT

            Files.walkFileTree(basePath, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = basePath.relativize(file)
                    if (matcher.matches(relative) || matcher.matches(file)) {
                        matches.add(GlobMatch(
                            path = file.toString(),
                            relativePath = relative.toString(),
                            lastModified = attrs.lastModifiedTime().toMillis()
                        ))
                    }
                    if (matches.size >= maxResults * 2) return FileVisitResult.TERMINATE
                    return FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.fileName?.toString() ?: ""
                    // Skip hidden dirs and common large dirs / 跳过隐藏目录和常见大目录
                    if (name.startsWith(".") || name in SKIP_DIRS) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException?): FileVisitResult {
                    return FileVisitResult.CONTINUE // Skip inaccessible files / 跳过不可访问的文件
                }
            })

            // Sort by modification time (newest first) / 按修改时间排序（最新优先）
            matches.sortByDescending { it.lastModified }
            val limited = matches.take(maxResults)

            val output = GlobOutput(
                matches = limited.map { it.relativePath },
                totalMatches = matches.size,
                truncated = matches.size > maxResults
            )
            ToolResult(data = output, output = output)
        } catch (e: Exception) {
            logger.error(e) { "Glob failed for pattern: ${input.pattern}" }
            ToolResult(
                data = GlobOutput(error = e.message),
                output = GlobOutput(error = e.message),
                isError = true
            )
        }
    }

    override suspend fun description(input: GlobInput, options: DescriptionOptions): String {
        return "Glob: ${input.pattern}"
    }

    override suspend fun checkPermissions(input: GlobInput, context: ToolUseContext) = PermissionResult.Allow()
    override fun isReadOnly(input: GlobInput) = true
    override fun isConcurrencySafe(input: GlobInput) = true
    override fun isSearchOrReadCommand(input: GlobInput) = true

    override fun getActivityDescription(input: GlobInput): String {
        return "Searching: ${input.pattern}"
    }

    companion object {
        const val DEFAULT_LIMIT = 200
        val SKIP_DIRS = setOf("node_modules", "build", "dist", ".git", ".gradle", "__pycache__", "target", ".idea")
    }
}

private data class GlobMatch(val path: String, val relativePath: String, val lastModified: Long)

@Serializable data class GlobInput(val pattern: String, val path: String? = null, val limit: Int? = null)
@Serializable data class GlobOutput(
    val matches: List<String> = emptyList(), val totalMatches: Int = 0,
    val truncated: Boolean = false, val error: String? = null
)
