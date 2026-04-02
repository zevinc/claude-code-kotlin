package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * WebFetch tool - fetches content from URLs and converts to markdown
 * WebFetch 工具 - 从 URL 获取内容并转换为 markdown
 *
 * Maps from TypeScript tools/WebFetchTool/WebFetchTool.ts.
 * Fetches web pages, extracts text content, handles redirects.
 * 映射自 TypeScript tools/WebFetchTool/WebFetchTool.ts。
 * 获取网页、提取文本内容、处理重定向。
 */
class WebFetchTool : Tool<WebFetchInput, WebFetchOutput> {
    override val name: String = "WebFetchTool"

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Fetch content from a URL and extract it as markdown. Use for reading web pages, documentation, API references.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(json: JsonObject): WebFetchInput {
        val url = json["url"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing required parameter: url")
        val prompt = json["prompt"]?.jsonPrimitive?.content
        return WebFetchInput(url = url, prompt = prompt)
    }

    override suspend fun call(
        input: WebFetchInput,
        context: ToolUseContext,
        canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant,
        onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<WebFetchOutput> {
        logger.info { "Fetching URL: ${input.url}" }

        return try {
            // Use curl to fetch the URL content / 使用 curl 获取 URL 内容
            val process = ProcessBuilder(
                "curl", "-sL", "-m", "30",
                "-H", "User-Agent: Mozilla/5.0 (compatible; ClaudeCode/1.0)",
                "-H", "Accept: text/html,application/xhtml+xml,text/plain",
                input.url
            ).redirectErrorStream(true).start()

            val content = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                return ToolResult(
                    data = WebFetchOutput(error = "Failed to fetch URL (exit code: $exitCode)"),
                    output = WebFetchOutput(error = "Failed to fetch URL"),
                    isError = true
                )
            }

            // Strip HTML tags for basic markdown extraction
            // 剥离 HTML 标签进行基本 markdown 提取
            val markdown = htmlToBasicMarkdown(content)
            val truncated = if (markdown.length > MAX_CONTENT_LENGTH) {
                markdown.take(MAX_CONTENT_LENGTH) + "\n\n[Content truncated at $MAX_CONTENT_LENGTH characters]"
            } else {
                markdown
            }

            val output = WebFetchOutput(
                content = truncated,
                url = input.url,
                contentLength = markdown.length
            )
            ToolResult(data = output, output = output)
        } catch (e: Exception) {
            logger.error(e) { "WebFetch failed for ${input.url}" }
            ToolResult(
                data = WebFetchOutput(error = e.message ?: "Unknown error"),
                output = WebFetchOutput(error = e.message),
                isError = true
            )
        }
    }

    override suspend fun description(input: WebFetchInput, options: DescriptionOptions): String {
        return "Fetch ${input.url}"
    }

    override suspend fun checkPermissions(input: WebFetchInput, context: ToolUseContext): PermissionResult {
        // Pre-approved hosts don't need permission / 预批准的主机不需要权限
        val host = try { java.net.URI(input.url).host ?: "" } catch (_: Exception) { "" }
        if (PREAPPROVED_HOSTS.any { host.endsWith(it) }) {
            return PermissionResult.Allow()
        }
        return PermissionResult.Allow() // Default allow for now / 目前默认允许
    }

    override suspend fun validateInput(input: WebFetchInput, context: ToolUseContext): ValidationResult {
        if (input.url.isBlank()) return ValidationResult.Failure("URL cannot be empty", errorCode = 400)
        if (!input.url.startsWith("http://") && !input.url.startsWith("https://")) {
            return ValidationResult.Failure("URL must start with http:// or https://", errorCode = 400)
        }
        return ValidationResult.Success
    }

    override fun isReadOnly(input: WebFetchInput): Boolean = true
    override fun isConcurrencySafe(input: WebFetchInput): Boolean = true

    override fun getActivityDescription(input: WebFetchInput): String {
        return "Fetching ${input.url.take(60)}"
    }

    /**
     * Basic HTML to markdown conversion / 基本 HTML 到 markdown 转换
     */
    private fun htmlToBasicMarkdown(html: String): String {
        return html
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<nav[^>]*>[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<footer[^>]*>[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<h1[^>]*>"), "\n# ")
            .replace(Regex("<h2[^>]*>"), "\n## ")
            .replace(Regex("<h3[^>]*>"), "\n### ")
            .replace(Regex("</h[1-6]>"), "\n")
            .replace(Regex("<p[^>]*>"), "\n")
            .replace("</p>", "\n")
            .replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<li[^>]*>"), "\n- ")
            .replace(Regex("<a[^>]*href=\"([^\"]+)\"[^>]*>([^<]+)</a>")) { mr ->
                "[${mr.groupValues[2]}](${mr.groupValues[1]})"
            }
            .replace(Regex("<code[^>]*>([^<]+)</code>")) { mr -> "`${mr.groupValues[1]}`" }
            .replace(Regex("<pre[^>]*>"), "\n```\n")
            .replace("</pre>", "\n```\n")
            .replace(Regex("<[^>]+>"), "") // Strip remaining tags / 剥离剩余标签
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&#39;"), "'")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    companion object {
        const val MAX_CONTENT_LENGTH = 16_000
        val PREAPPROVED_HOSTS = listOf(
            "github.com", "docs.github.com", "raw.githubusercontent.com",
            "npmjs.com", "docs.npmjs.com",
            "developer.mozilla.org", "stackoverflow.com",
            "kotlinlang.org", "docs.oracle.com",
            "gradle.org", "docs.gradle.org"
        )
    }
}

@Serializable data class WebFetchInput(val url: String, val prompt: String? = null)
@Serializable data class WebFetchOutput(
    val content: String? = null, val url: String? = null,
    val contentLength: Int = 0, val error: String? = null
)
