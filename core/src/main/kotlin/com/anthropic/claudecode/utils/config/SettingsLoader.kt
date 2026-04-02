package com.anthropic.claudecode.utils.config

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Settings loader - loads and merges settings from multiple sources
 * 设置加载器 - 从多个来源加载和合并设置
 *
 * Maps from TypeScript utils/config.ts.
 * Settings are loaded from (in priority order):
 * 1. ~/.claude/settings.json (user global)
 * 2. .claude/settings.json (project)
 * 3. .claude/settings.local.json (local, git-ignored)
 * 映射自 TypeScript utils/config.ts。
 * 设置按优先级顺序加载。
 */
object SettingsLoader {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * Merged application settings
     * 合并后的应用设置
     */
    @Serializable
    data class AppSettings(
        /** Permission mode / 权限模式 */
        val mode: String? = null,
        /** Custom API key / 自定义 API 密钥 */
        val apiKey: String? = null,
        /** Custom model / 自定义模型 */
        val model: String? = null,
        /** Max budget in USD / 最大预算（美元） */
        val maxBudget: Double? = null,
        /** Always allow rules / 始终允许的规则 */
        val allowRules: List<String> = emptyList(),
        /** Always deny rules / 始终拒绝的规则 */
        val denyRules: List<String> = emptyList(),
        /** Always ask rules / 始终询问的规则 */
        val askRules: List<String> = emptyList(),
        /** Custom system prompt / 自定义系统提示 */
        val customSystemPrompt: String? = null,
        /** Custom theme / 自定义主题 */
        val theme: String? = null,
        /** Hook definitions / 钩子定义 */
        val hooks: Map<String, List<HookConfig>> = emptyMap(),
        /** Additional settings as raw JSON / 额外设置作为原始 JSON */
        val extra: Map<String, JsonElement> = emptyMap()
    )

    @Serializable
    data class HookConfig(
        /** Hook type: command, callback, function / 钩子类型 */
        val type: String = "command",
        /** Command to execute / 要执行的命令 */
        val command: String? = null,
        /** Timeout in ms / 超时时间（毫秒） */
        val timeout: Long? = null
    )

    /**
     * Load settings from all sources for the given project directory
     * 为给定项目目录从所有来源加载设置
     */
    fun load(projectDir: String): AppSettings {
        val configDir = System.getenv("CLAUDE_CONFIG_DIR")
            ?: "${System.getProperty("user.home")}/.claude"

        // Load in priority order (later overrides earlier)
        // 按优先级顺序加载（后面的覆盖前面的）
        val userSettings = loadJsonSettings("$configDir/settings.json")
        val projectSettings = loadJsonSettings("$projectDir/.claude/settings.json")
        val localSettings = loadJsonSettings("$projectDir/.claude/settings.local.json")

        return mergeSettings(userSettings, projectSettings, localSettings)
    }

    private fun loadJsonSettings(path: String): JsonObject? {
        val file = File(path)
        if (!file.exists() || !file.isFile) return null

        return try {
            json.parseToJsonElement(file.readText()).jsonObject
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse settings: $path" }
            null
        }
    }

    private fun mergeSettings(vararg sources: JsonObject?): AppSettings {
        var mode: String? = null
        var apiKey: String? = null
        var model: String? = null
        var maxBudget: Double? = null
        val allowRules = mutableListOf<String>()
        val denyRules = mutableListOf<String>()
        val askRules = mutableListOf<String>()
        var customSystemPrompt: String? = null
        var theme: String? = null
        val hooks = mutableMapOf<String, List<HookConfig>>()
        val extra = mutableMapOf<String, JsonElement>()

        for (source in sources) {
            if (source == null) continue

            source["mode"]?.jsonPrimitive?.content?.let { mode = it }
            source["apiKey"]?.jsonPrimitive?.content?.let { apiKey = it }
            source["model"]?.jsonPrimitive?.content?.let { model = it }
            source["maxBudget"]?.jsonPrimitive?.doubleOrNull?.let { maxBudget = it }
            source["customSystemPrompt"]?.jsonPrimitive?.content?.let { customSystemPrompt = it }
            source["theme"]?.jsonPrimitive?.content?.let { theme = it }

            source["allowRules"]?.jsonArray?.forEach { rule ->
                rule.jsonPrimitive.content.let { allowRules.add(it) }
            }
            source["denyRules"]?.jsonArray?.forEach { rule ->
                rule.jsonPrimitive.content.let { denyRules.add(it) }
            }
            source["askRules"]?.jsonArray?.forEach { rule ->
                rule.jsonPrimitive.content.let { askRules.add(it) }
            }

            // Store remaining keys as extra / 将其余键存为额外设置
            for ((key, value) in source) {
                if (key !in KNOWN_KEYS) {
                    extra[key] = value
                }
            }
        }

        return AppSettings(
            mode = mode, apiKey = apiKey, model = model, maxBudget = maxBudget,
            allowRules = allowRules, denyRules = denyRules, askRules = askRules,
            customSystemPrompt = customSystemPrompt, theme = theme,
            hooks = hooks, extra = extra
        )
    }

    private val KNOWN_KEYS = setOf(
        "mode", "apiKey", "model", "maxBudget", "allowRules", "denyRules",
        "askRules", "customSystemPrompt", "theme", "hooks"
    )
}
