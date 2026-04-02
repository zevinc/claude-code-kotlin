package com.anthropic.claudecode.services.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * AuthService - manages API authentication
 * AuthService - 管理 API 认证
 *
 * Maps from TypeScript services/auth.ts.
 * Supports: API key (env/config), OAuth token refresh,
 * and credential storage via system keychain or config file.
 * 映射自 TypeScript services/auth.ts。
 * 支持：API Key（环境变量/配置文件）、OAuth 令牌刷新、
 * 以及通过系统钥匙串或配置文件进行凭据存储。
 */
class AuthService(
    private val configDir: String = defaultConfigDir()
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cachedCredential: AuthCredential? = null

    /**
     * Get the current API key, checking sources in priority order
     * 获取当前 API Key，按优先级顺序检查来源
     *
     * Priority: 1. Environment variable  2. Config file  3. OAuth token
     * 优先级：1. 环境变量  2. 配置文件  3. OAuth 令牌
     */
    fun getApiKey(): String? {
        // 1. Environment variable / 环境变量
        val envKey = System.getenv("ANTHROPIC_API_KEY")
        if (!envKey.isNullOrBlank()) {
            logger.debug { "Using API key from environment" }
            return envKey
        }

        // 2. Config file / 配置文件
        val configKey = loadFromConfig()?.apiKey
        if (!configKey.isNullOrBlank()) {
            logger.debug { "Using API key from config" }
            return configKey
        }

        // 3. OAuth token / OAuth 令牌
        val oauthToken = loadOAuthToken()
        if (oauthToken != null) {
            logger.debug { "Using OAuth token" }
            return oauthToken.accessToken
        }

        return null
    }

    /**
     * Check if authenticated / 检查是否已认证
     */
    fun isAuthenticated(): Boolean = getApiKey() != null

    /**
     * Save API key to config / 保存 API Key 到配置
     */
    fun saveApiKey(apiKey: String) {
        val configFile = File(configDir, "credentials.json")
        configFile.parentFile?.mkdirs()
        val cred = AuthCredential(apiKey = apiKey)
        configFile.writeText(json.encodeToString(AuthCredential.serializer(), cred))
        cachedCredential = cred
        logger.info { "API key saved to config" }
    }

    /**
     * Save OAuth tokens / 保存 OAuth 令牌
     */
    fun saveOAuthToken(token: OAuthToken) {
        val tokenFile = File(configDir, "oauth_token.json")
        tokenFile.parentFile?.mkdirs()
        tokenFile.writeText(json.encodeToString(OAuthToken.serializer(), token))
        logger.info { "OAuth token saved" }
    }

    /**
     * Get authentication status summary / 获取认证状态摘要
     */
    fun getAuthStatus(): AuthStatus {
        val envKey = System.getenv("ANTHROPIC_API_KEY")
        val configCred = loadFromConfig()
        val oauthToken = loadOAuthToken()

        return AuthStatus(
            hasEnvKey = !envKey.isNullOrBlank(),
            hasConfigKey = configCred?.apiKey?.isNotBlank() == true,
            hasOAuthToken = oauthToken != null,
            isAuthenticated = isAuthenticated(),
            source = when {
                !envKey.isNullOrBlank() -> "environment"
                configCred?.apiKey?.isNotBlank() == true -> "config"
                oauthToken != null -> "oauth"
                else -> "none"
            }
        )
    }

    /**
     * Clear stored credentials / 清除存储的凭据
     */
    fun clearCredentials() {
        File(configDir, "credentials.json").delete()
        File(configDir, "oauth_token.json").delete()
        cachedCredential = null
        logger.info { "Credentials cleared" }
    }

    private fun loadFromConfig(): AuthCredential? {
        if (cachedCredential != null) return cachedCredential
        val configFile = File(configDir, "credentials.json")
        if (!configFile.exists()) return null
        return try {
            val cred = json.decodeFromString(AuthCredential.serializer(), configFile.readText())
            cachedCredential = cred
            cred
        } catch (e: Exception) {
            logger.warn { "Failed to load credentials: ${e.message}" }
            null
        }
    }

    private fun loadOAuthToken(): OAuthToken? {
        val tokenFile = File(configDir, "oauth_token.json")
        if (!tokenFile.exists()) return null
        return try {
            json.decodeFromString(OAuthToken.serializer(), tokenFile.readText())
        } catch (e: Exception) {
            logger.warn { "Failed to load OAuth token: ${e.message}" }
            null
        }
    }

    companion object {
        fun defaultConfigDir(): String {
            return System.getenv("CLAUDE_CONFIG_DIR")
                ?: "${System.getProperty("user.home")}/.claude"
        }
    }
}

@Serializable
data class AuthCredential(
    val apiKey: String? = null,
    val organizationId: String? = null
)

@Serializable
data class OAuthToken(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: String? = null,
    val tokenType: String = "bearer"
)

data class AuthStatus(
    val hasEnvKey: Boolean = false,
    val hasConfigKey: Boolean = false,
    val hasOAuthToken: Boolean = false,
    val isAuthenticated: Boolean = false,
    val source: String = "none"
)
