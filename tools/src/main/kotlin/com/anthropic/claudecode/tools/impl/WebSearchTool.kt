package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * WebSearch tool - searches the web and returns results
 * WebSearch 工具 - 搜索网页并返回结果
 *
 * Maps from TypeScript tools/WebSearchTool/WebSearchTool.ts.
 * Uses the Anthropic native web_search tool or external search API.
 * 映射自 TypeScript tools/WebSearchTool/WebSearchTool.ts。
 * 使用 Anthropic 原生 web_search 工具或外部搜索 API。
 */
class WebSearchTool : Tool<WebSearchInput, WebSearchOutput> {
    override val name: String = "WebSearchTool"

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Search the web for information. Returns relevant snippets and URLs. / 搜索网页获取信息。返回相关片段和 URL。",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(json: JsonObject): WebSearchInput {
        val query = json["query"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing required parameter: query")
        val timeRange = json["time_range"]?.jsonPrimitive?.content
        return WebSearchInput(query = query, timeRange = timeRange)
    }

    override suspend fun call(
        input: WebSearchInput,
        context: ToolUseContext,
        canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant,
        onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<WebSearchOutput> {
        logger.info { "Web search: ${input.query}" }

        // Use DuckDuckGo lite as fallback search
        // 使用 DuckDuckGo lite 作为后备搜索
        return try {
            val encodedQuery = java.net.URLEncoder.encode(input.query, "UTF-8")
            val searchUrl = "https://lite.duckduckgo.com/lite/?q=$encodedQuery"

            val process = ProcessBuilder(
                "curl", "-sL", "-m", "15",
                "-H", "User-Agent: Mozilla/5.0 (compatible; ClaudeCode/1.0)",
                searchUrl
            ).redirectErrorStream(true).start()

            val html = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                return ToolResult(
                    data = WebSearchOutput(error = "Search failed"),
                    output = WebSearchOutput(error = "Search failed"),
                    isError = true
                )
            }

            val results = parseSearchResults(html)
            val output = WebSearchOutput(
                query = input.query,
                results = results,
                resultCount = results.size
            )
            ToolResult(data = output, output = output)
        } catch (e: Exception) {
            logger.error(e) { "Web search failed for: ${input.query}" }
            ToolResult(
                data = WebSearchOutput(error = e.message),
                output = WebSearchOutput(error = e.message),
                isError = true
            )
        }
    }

    override suspend fun description(input: WebSearchInput, options: DescriptionOptions): String {
        return "Search: ${input.query.take(60)}"
    }

    override suspend fun checkPermissions(input: WebSearchInput, context: ToolUseContext): PermissionResult {
        return PermissionResult.Allow()
    }

    override suspend fun validateInput(input: WebSearchInput, context: ToolUseContext): ValidationResult {
        if (input.query.isBlank()) return ValidationResult.Failure("Query cannot be empty / 查询不能为空", errorCode = 400)
        if (input.query.length > 200) return ValidationResult.Failure("Query too long (max 200 chars) / 查询太长（最大 200 字符）", errorCode = 400)
        return ValidationResult.Success
    }

    override fun isReadOnly(input: WebSearchInput): Boolean = true
    override fun isConcurrencySafe(input: WebSearchInput): Boolean = true

    override fun getActivityDescription(input: WebSearchInput): String {
        return "Searching: ${input.query.take(40)}"
    }

    private fun parseSearchResults(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        // Extract links and snippets from DuckDuckGo lite HTML
        // 从 DuckDuckGo lite HTML 中提取链接和片段
        val linkPattern = Regex("""<a[^>]*href="([^"]*)"[^>]*class="result-link"[^>]*>([^<]*)</a>""")
        val snippetPattern = Regex("""<td class="result-snippet">([^<]*)</td>""")

        val links = linkPattern.findAll(html).toList()
        val snippets = snippetPattern.findAll(html).toList()

        for (i in links.indices.take(MAX_RESULTS)) {
            val url = links[i].groupValues[1]
            val title = links[i].groupValues[2].trim()
            val snippet = snippets.getOrNull(i)?.groupValues?.get(1)?.trim() ?: ""

            if (url.isNotBlank() && title.isNotBlank()) {
                results.add(SearchResult(url = url, title = title, snippet = snippet))
            }
        }

        // Fallback: basic link extraction if structured parsing fails
        // 回退：如果结构化解析失败则进行基本链接提取
        if (results.isEmpty()) {
            val fallbackPattern = Regex("""<a[^>]*href="(https?://[^"]+ )"[^>]*>([^<]+)</a>""")
            for (match in fallbackPattern.findAll(html).take(MAX_RESULTS)) {
                val url = match.groupValues[1]
                val title = match.groupValues[2].trim()
                if (!url.contains("duckduckgo.com") && title.length > 3) {
                    results.add(SearchResult(url = url, title = title, snippet = ""))
                }
            }
        }

        return results
    }

    companion object {
        const val MAX_RESULTS = 8
    }
}

@Serializable data class WebSearchInput(val query: String, val timeRange: String? = null)
@Serializable data class WebSearchOutput(
    val query: String? = null, val results: List<SearchResult> = emptyList(),
    val resultCount: Int = 0, val error: String? = null
)
@Serializable data class SearchResult(val url: String, val title: String, val snippet: String)
