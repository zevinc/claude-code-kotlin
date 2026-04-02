package com.anthropic.claudecode.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Global configuration - loaded from ~/.claude.json
 * 全局配置 - 从 ~/.claude.json 加载
 */
@Serializable
data class GlobalConfig(
    /** API key for Anthropic / Anthropic API 密钥 */
    val apiKey: String? = null,
    /** Default model to use / 默认使用的模型 */
    val model: String? = null,
    /** Permission mode / 权限模式 */
    val permissionMode: String? = null,
    /** Custom themes / 自定义主题 */
    val theme: String? = null,
    /** Verbose output / 详细输出 */
    val verbose: Boolean = false,
    /** Output style preference / 输出样式偏好 */
    val outputStyle: String? = null
)

/**
 * Project configuration - loaded from .claude/config.json
 * 项目配置 - 从 .claude/config.json 加载
 */
@Serializable
data class ProjectConfig(
    /** Project-specific model override / 项目特定的模型覆盖 */
    val model: String? = null,
    /** Project permission settings / 项目权限设置 */
    val permissions: ProjectPermissions? = null,
    /** Custom system prompt / 自定义系统提示 */
    val systemPrompt: String? = null,
    /** Session costs per model / 每个模型的会话成本 */
    val sessionCosts: Map<String, ModelUsage>? = null
)

/**
 * Project-level permission configuration
 * 项目级别权限配置
 */
@Serializable
data class ProjectPermissions(
    /** Always allow rules / 始终允许的规则 */
    val allow: List<String> = emptyList(),
    /** Always deny rules / 始终拒绝的规则 */
    val deny: List<String> = emptyList(),
    /** Always ask rules / 始终询问的规则 */
    val ask: List<String> = emptyList()
)

/**
 * Per-model token usage tracking
 * 每个模型的令牌使用跟踪
 */
@Serializable
data class ModelUsage(
    /** Input tokens used / 使用的输入令牌 */
    val inputTokens: Long = 0,
    /** Output tokens used / 使用的输出令牌 */
    val outputTokens: Long = 0,
    /** Cache read tokens / 缓存读取令牌 */
    val cacheReadInputTokens: Long = 0,
    /** Cache creation tokens / 缓存创建令牌 */
    val cacheCreationInputTokens: Long = 0,
    /** Total cost in USD / 总成本（美元） */
    val totalCostUsd: Double = 0.0
)

/**
 * Settings JSON - user preferences stored in settings file
 * 设置 JSON - 存储在设置文件中的用户偏好
 */
@Serializable
data class SettingsJson(
    /** Enable verbose logging / 启用详细日志 */
    val verbose: Boolean = false,
    /** Default model / 默认模型 */
    val model: String? = null,
    /** Theme name / 主题名称 */
    val theme: String? = null,
    /** Output style / 输出样式 */
    @SerialName("output_style")
    val outputStyle: String? = null,
    /** Vim mode enabled / 是否启用 Vim 模式 */
    val vim: Boolean = false,
    /** Permission rules / 权限规则 */
    val permissions: Map<String, List<String>>? = null
)

/**
 * Model setting - either a model name or a model configuration
 * 模型设置 - 模型名称或模型配置
 */
@Serializable
data class ModelSetting(
    /** Model identifier (e.g., "claude-sonnet-4-20250514") / 模型标识符 */
    val modelId: String,
    /** Custom base URL for the API / API 的自定义基础 URL */
    val baseUrl: String? = null,
    /** Maximum tokens for output / 输出的最大令牌数 */
    val maxOutputTokens: Int? = null
)

/**
 * Configuration loader - loads and merges configuration from multiple sources
 * 配置加载器 - 从多个来源加载并合并配置
 */
object ConfigLoader {
    /**
     * Default global config path / 默认全局配置路径
     */
    val GLOBAL_CONFIG_PATH: Path
        get() = Path(System.getProperty("user.home"), ".claude.json")

    /**
     * Default project config directory / 默认项目配置目录
     */
    const val PROJECT_CONFIG_DIR = ".claude"

    /**
     * Default project config filename / 默认项目配置文件名
     */
    const val PROJECT_CONFIG_FILE = "config.json"

    /**
     * Session storage directory / 会话存储目录
     */
    val SESSION_DIR: Path
        get() = Path(System.getProperty("user.home"), ".claude", "sessions")

    /**
     * History file path / 历史文件路径
     */
    val HISTORY_FILE: Path
        get() = Path(System.getProperty("user.home"), ".claude", "history.jsonl")

    /**
     * Load global configuration from ~/.claude.json
     * 从 ~/.claude.json 加载全局配置
     */
    fun loadGlobalConfig(): GlobalConfig {
        val path = GLOBAL_CONFIG_PATH
        if (!path.exists()) return GlobalConfig()
        return try {
            kotlinx.serialization.json.Json.decodeFromString(path.readText())
        } catch (e: Exception) {
            GlobalConfig()
        }
    }

    /**
     * Load project configuration from .claude/config.json
     * 从 .claude/config.json 加载项目配置
     *
     * @param projectRoot Root directory of the project / 项目的根目录
     */
    fun loadProjectConfig(projectRoot: Path): ProjectConfig {
        val configPath = projectRoot.resolve(PROJECT_CONFIG_DIR).resolve(PROJECT_CONFIG_FILE)
        if (!configPath.exists()) return ProjectConfig()
        return try {
            kotlinx.serialization.json.Json.decodeFromString(configPath.readText())
        } catch (e: Exception) {
            ProjectConfig()
        }
    }
}
