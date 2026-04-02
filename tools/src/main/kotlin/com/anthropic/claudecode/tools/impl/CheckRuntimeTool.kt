package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * CheckRuntimeTool - detects installed runtime environments
 * CheckRuntimeTool - 检测已安装的运行时环境
 *
 * Maps from TypeScript tools/CheckRuntimeTool.
 * Checks if node, python, ruby, java, go, rust are available.
 * 映射自 TypeScript tools/CheckRuntimeTool。
 * 检查 node、python、ruby、java、go、rust 是否可用。
 */
class CheckRuntimeTool : Tool<CheckRuntimeInput, CheckRuntimeOutput> {
    override val name = "CheckRuntimeTool"
    override val aliases = listOf("CheckRuntime", "check_runtime")

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Detect whether a runtime environment (node, python, ruby, java, go, rust) is installed.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): CheckRuntimeInput {
        return CheckRuntimeInput(
            runtime = rawInput["runtime"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing: runtime")
        )
    }

    override suspend fun call(
        input: CheckRuntimeInput, context: ToolUseContext, canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant, onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<CheckRuntimeOutput> {
        val runtime = input.runtime.lowercase()
        logger.info { "Checking runtime: $runtime" }

        val commands = RUNTIME_COMMANDS[runtime]
        if (commands == null) {
            return ToolResult(
                data = CheckRuntimeOutput(runtime = runtime, found = false, error = "Unknown runtime: $runtime"),
                output = CheckRuntimeOutput(runtime = runtime, found = false, error = "Unknown runtime"),
                isError = true
            )
        }

        // Try each possible command / 尝试每个可能的命令
        for (cmd in commands) {
            try {
                val process = ProcessBuilder(cmd, "--version")
                    .redirectErrorStream(true)
                    .start()
                val versionOutput = process.inputStream.bufferedReader().readText().trim()
                val completed = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)

                if (completed && process.exitValue() == 0) {
                    val version = extractVersion(versionOutput)
                    val output = CheckRuntimeOutput(
                        runtime = runtime,
                        found = true,
                        executable = cmd,
                        version = version,
                        rawOutput = versionOutput.take(200)
                    )
                    return ToolResult(data = output, output = output)
                }
            } catch (_: Exception) {
                // Command not found, try next / 命令未找到，尝试下一个
            }
        }

        val output = CheckRuntimeOutput(
            runtime = runtime,
            found = false,
            suggestions = getSuggestions(runtime)
        )
        return ToolResult(data = output, output = output)
    }

    private fun extractVersion(output: String): String {
        val versionRegex = Regex("""(\d+\.\d+(?:\.\d+)?)""")
        return versionRegex.find(output)?.groupValues?.get(1) ?: output.take(50)
    }

    private fun getSuggestions(runtime: String): List<String> = when (runtime) {
        "node" -> listOf("brew install node", "nvm install --lts", "https://nodejs.org")
        "python" -> listOf("brew install python3", "pyenv install 3.12", "https://python.org")
        "ruby" -> listOf("brew install ruby", "rbenv install 3.3", "https://ruby-lang.org")
        "java" -> listOf("brew install openjdk", "sdk install java", "https://adoptium.net")
        "go" -> listOf("brew install go", "https://go.dev/dl/")
        "rust" -> listOf("curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh", "https://rustup.rs")
        else -> emptyList()
    }

    override suspend fun description(input: CheckRuntimeInput, options: DescriptionOptions) = "Check ${input.runtime}"
    override suspend fun checkPermissions(input: CheckRuntimeInput, context: ToolUseContext) = PermissionResult.Allow()
    override fun isReadOnly(input: CheckRuntimeInput) = true

    companion object {
        val RUNTIME_COMMANDS = mapOf(
            "node" to listOf("node"),
            "python" to listOf("python3", "python"),
            "ruby" to listOf("ruby"),
            "java" to listOf("java"),
            "go" to listOf("go"),
            "rust" to listOf("rustc")
        )
    }
}

@Serializable data class CheckRuntimeInput(val runtime: String)
@Serializable data class CheckRuntimeOutput(
    val runtime: String, val found: Boolean = false,
    val executable: String? = null, val version: String? = null,
    val rawOutput: String? = null, val suggestions: List<String> = emptyList(),
    val error: String? = null
)
