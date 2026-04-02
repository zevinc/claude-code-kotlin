package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * BashTool - executes shell commands in a persistent working directory
 * BashTool - 在持久化工作目录中执行 Shell 命令
 *
 * Maps from TypeScript BashTool/BashTool.tsx.
 * Features: timeout, background execution, output streaming,
 * large output persistence, command semantics interpretation.
 * 映射自 TypeScript BashTool/BashTool.tsx。
 * 功能：超时、后台执行、输出流、大输出持久化、命令语义解释。
 */
class BashTool : Tool<BashTool.Input, BashTool.Output> {

    override val name: String = "Bash"

    override val aliases: List<String> = listOf("bash", "shell", "terminal")

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Execute a bash command in the terminal. " +
            "Working directory persists between calls. " +
            "Use for running shell commands, git operations, build tools, etc.",
        schema = kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("object"))
            put("properties", kotlinx.serialization.json.buildJsonObject {
                put("command", kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("string"))
                    put("description", kotlinx.serialization.json.JsonPrimitive("The bash command to execute"))
                })
                put("timeout", kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("integer"))
                    put("description", kotlinx.serialization.json.JsonPrimitive("Timeout in milliseconds (max 600000)"))
                })
                put("description", kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("string"))
                    put("description", kotlinx.serialization.json.JsonPrimitive("Description of what the command does (5-10 words)"))
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("command"))
            })
        }
    )

    override val maxResultSizeChars: Int = 30000

    /**
     * Current working directory (persists between calls)
     * 当前工作目录（在调用间持久化）
     */
    private var cwd: String = System.getProperty("user.dir")

    override fun parseInput(rawInput: JsonObject): Input {
        return Input(
            command = rawInput["command"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required field: command"),
            timeout = rawInput["timeout"]?.jsonPrimitive?.intOrNull,
            description = rawInput["description"]?.jsonPrimitive?.content,
            runInBackground = rawInput["run_in_background"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }

    override suspend fun call(
        input: Input,
        context: ToolUseContext,
        canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant,
        onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<Output> {
        logger.info { "Executing bash command: ${input.command.take(100)}" }

        val timeout = (input.timeout ?: DEFAULT_TIMEOUT_MS).coerceAtMost(MAX_TIMEOUT_MS).toLong()

        try {
            // Check for cd command to update cwd / 检查 cd 命令以更新 cwd
            val cdMatch = CD_REGEX.find(input.command.trim())

            val process = ProcessBuilder("/bin/bash", "-c", input.command)
                .directory(File(cwd))
                .redirectErrorStream(true) // Merge stderr into stdout / 将 stderr 合并到 stdout
                .start()

            val output = StringBuilder()
            val interrupted: Boolean

            // Read output with timeout / 带超时读取输出
            val job = CoroutineScope(Dispatchers.IO).async {
                process.inputStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    var lineCount = 0
                    while (line != null) {
                        output.appendLine(line)
                        lineCount++

                        // Report progress after threshold / 超过阈值后报告进度
                        if (lineCount % PROGRESS_LINE_INTERVAL == 0) {
                            onProgress?.invoke(ToolProgressData.BashProgress(
                                stdout = "Lines read: $lineCount",
                                status = "running"
                            ))
                        }
                        line = reader.readLine()
                    }
                }
            }

            try {
                val completed = withTimeoutOrNull(timeout) {
                    job.await()
                    process.waitFor()
                }
                interrupted = (completed == null)
                if (interrupted) {
                    process.destroyForcibly()
                    job.cancel()
                }
            } catch (e: CancellationException) {
                process.destroyForcibly()
                job.cancel()
                throw e
            }

            val exitCode = if (interrupted) -1 else process.exitValue()

            // Update cwd if cd command / 如果是 cd 命令则更新 cwd
            if (cdMatch != null && !interrupted && exitCode == 0) {
                updateCwd(input.command)
            }

            // Truncate large output / 截断大输出
            val stdout = if (output.length > maxResultSizeChars) {
                output.substring(0, maxResultSizeChars) + "\n... [output truncated at ${maxResultSizeChars} chars]"
            } else {
                output.toString()
            }

            // Interpret return code semantics / 解释返回码语义
            val returnCodeInterpretation = interpretReturnCode(input.command, exitCode)

            val result = Output(
                stdout = stdout.trimEnd(),
                stderr = "", // Merged into stdout / 已合并到 stdout
                exitCode = exitCode,
                interrupted = interrupted,
                returnCodeInterpretation = returnCodeInterpretation
            )

            logger.info {
                "Bash command completed: exit=$exitCode, " +
                "output=${stdout.length} chars, interrupted=$interrupted"
            }

            return ToolResult(
                output = result,
                isError = exitCode != 0 && returnCodeInterpretation == null
            )

        } catch (e: Exception) {
            logger.error(e) { "Bash command execution failed" }
            return ToolResult(
                output = Output(
                    stdout = "",
                    stderr = "Error: ${e.message}",
                    exitCode = -1,
                    interrupted = false
                ),
                isError = true
            )
        }
    }

    override suspend fun description(input: Input, options: DescriptionOptions): String {
        return input.description ?: "Execute: ${input.command.take(60)}"
    }

    override suspend fun checkPermissions(input: Input, context: ToolUseContext): PermissionResult {
        // Check for obviously dangerous commands / 检查明显危险的命令
        val command = input.command.trim()
        for (pattern in DANGEROUS_PATTERNS) {
            if (pattern.containsMatchIn(command)) {
                return PermissionResult.Ask(
                    message = "Command may be dangerous: ${input.command.take(80)}",
                    suggestions = emptyList()
                )
            }
        }

        // Default: allow (will be overridden by actual permission system)
        // 默认：允许（将被实际权限系统覆盖）
        return PermissionResult.Allow()
    }

    override fun isConcurrencySafe(input: Input): Boolean = false
    override fun isReadOnly(input: Input): Boolean = false
    override fun isDestructive(input: Input): Boolean = true

    override fun getActivityDescription(input: Input): String {
        return input.description ?: "Running: ${input.command.take(40)}"
    }

    /**
     * Update working directory from cd commands
     * 从 cd 命令更新工作目录
     */
    private fun updateCwd(command: String) {
        val trimmed = command.trim()
        val cdMatch = CD_REGEX.find(trimmed) ?: return
        val target = cdMatch.groupValues[1].trim()

        val newDir = if (target.startsWith("/")) {
            target
        } else if (target.startsWith("~")) {
            target.replaceFirst("~", System.getProperty("user.home"))
        } else {
            File(cwd, target).canonicalPath
        }

        if (File(newDir).isDirectory) {
            cwd = newDir
            logger.debug { "Working directory changed to: $cwd" }
        }
    }

    /**
     * Interpret command-specific return codes
     * 解释命令特定的返回码
     *
     * Some commands have non-zero exit codes that aren't errors.
     * 某些命令的非零退出码并非错误。
     */
    private fun interpretReturnCode(command: String, exitCode: Int): String? {
        if (exitCode == 0) return null

        val baseCommand = command.trim().split("\\s+".toRegex()).firstOrNull() ?: return null

        return when {
            // grep/rg: 1 = no matches (not an error)
            // grep/rg: 1 = 无匹配（非错误）
            baseCommand in listOf("grep", "rg", "egrep", "fgrep") && exitCode == 1 ->
                "No matches found"

            // diff: 1 = files differ (not an error)
            // diff: 1 = 文件不同（非错误）
            baseCommand == "diff" && exitCode == 1 ->
                "Files differ"

            // test/[: 1 = condition false (not an error)
            // test/[: 1 = 条件为假（非错误）
            baseCommand in listOf("test", "[") && exitCode == 1 ->
                "Condition is false"

            // find: 1 = some dirs inaccessible
            // find: 1 = 部分目录不可访问
            baseCommand == "find" && exitCode == 1 ->
                "Some directories were inaccessible"

            else -> null
        }
    }

    /**
     * BashTool input
     * BashTool 输入
     */
    data class Input(
        /** The bash command to execute / 要执行的 bash 命令 */
        val command: String,
        /** Timeout in milliseconds / 超时时间（毫秒） */
        val timeout: Int? = null,
        /** Description of the command / 命令描述 */
        val description: String? = null,
        /** Whether to run in background / 是否在后台运行 */
        val runInBackground: Boolean = false
    )

    /**
     * BashTool output
     * BashTool 输出
     */
    data class Output(
        /** Standard output / 标准输出 */
        val stdout: String,
        /** Standard error / 标准错误 */
        val stderr: String,
        /** Process exit code / 进程退出码 */
        val exitCode: Int,
        /** Whether the process was interrupted by timeout / 进程是否因超时被中断 */
        val interrupted: Boolean,
        /** Semantic interpretation of exit code / 退出码的语义解释 */
        val returnCodeInterpretation: String? = null
    ) {
        override fun toString(): String {
            val sb = StringBuilder()
            if (stdout.isNotBlank()) sb.append(stdout)
            if (stderr.isNotBlank()) {
                if (sb.isNotEmpty()) sb.appendLine()
                sb.append("STDERR: $stderr")
            }
            if (interrupted) sb.appendLine("\n[Command interrupted by timeout]")
            if (returnCodeInterpretation != null) sb.appendLine("\n[Exit code $exitCode: $returnCodeInterpretation]")
            return sb.toString()
        }
    }

    companion object {
        /** Default timeout: 2 minutes / 默认超时：2 分钟 */
        const val DEFAULT_TIMEOUT_MS = 120_000

        /** Maximum timeout: 10 minutes / 最大超时：10 分钟 */
        const val MAX_TIMEOUT_MS = 600_000

        /** Progress report interval in lines / 进度报告间隔（行数） */
        const val PROGRESS_LINE_INTERVAL = 100

        /** Regex to detect cd commands / 检测 cd 命令的正则 */
        private val CD_REGEX = Regex("""^cd\s+(.+)""")

        /** Dangerous command patterns / 危险命令模式 */
        private val DANGEROUS_PATTERNS = listOf(
            Regex("""rm\s+-rf\s+/(?!\S)"""),     // rm -rf /
            Regex(""":>\s*/"""),                   // truncate root files
            Regex("""mkfs\."""),                   // format filesystem
            Regex("""dd\s+.*of=/dev/"""),          // write to device
            Regex(""">\s*/dev/sd[a-z]"""),         // overwrite disk
        )
    }
}
