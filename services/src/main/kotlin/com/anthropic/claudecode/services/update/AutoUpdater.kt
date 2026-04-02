package com.anthropic.claudecode.services.update

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

/**
 * AutoUpdater - checks for new versions
 * AutoUpdater - 检查新版本
 *
 * Maps from TypeScript utils/autoUpdater.ts.
 * Checks a version endpoint periodically and notifies the user
 * if a newer version is available. Caches check results for 24 hours.
 * 映射自 TypeScript utils/autoUpdater.ts。
 * 定期检查版本端点并在有新版本可用时通知用户。缓存检查结果 24 小时。
 */
class AutoUpdater(
    private val currentVersion: String = CURRENT_VERSION,
    private val cacheDir: String = defaultCacheDir()
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Check for updates if enough time has passed since last check
     * 如果自上次检查以来已过去足够时间，则检查更新
     */
    fun checkForUpdates(): UpdateCheckResult {
        val cached = loadCache()
        if (cached != null && !isStale(cached.checkedAt)) {
            return if (isNewer(cached.latestVersion, currentVersion)) {
                UpdateCheckResult(
                    updateAvailable = true,
                    currentVersion = currentVersion,
                    latestVersion = cached.latestVersion,
                    fromCache = true
                )
            } else {
                UpdateCheckResult(currentVersion = currentVersion, fromCache = true)
            }
        }

        return try {
            val latestVersion = fetchLatestVersion()
            saveCache(UpdateCache(latestVersion = latestVersion, checkedAt = Instant.now().toString()))

            if (isNewer(latestVersion, currentVersion)) {
                UpdateCheckResult(
                    updateAvailable = true,
                    currentVersion = currentVersion,
                    latestVersion = latestVersion
                )
            } else {
                UpdateCheckResult(currentVersion = currentVersion)
            }
        } catch (e: Exception) {
            logger.debug { "Update check failed: ${e.message}" }
            UpdateCheckResult(currentVersion = currentVersion, error = e.message)
        }
    }

    /**
     * Compare semantic versions / 比较语义版本号
     */
    fun isNewer(latest: String, current: String): Boolean {
        val latestParts = latest.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun fetchLatestVersion(): String {
        val endpoint = System.getenv("CLAUDE_VERSION_CHECK_URL")
            ?: "https://api.github.com/repos/anthropics/claude-code/releases/latest"

        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
            val request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                // Try to parse as GitHub release JSON
                // 尝试解析为 GitHub release JSON
                val body = response.body()
                val tagRegex = Regex(""""tag_name"\s*:\s*"([^"]+)"""")
                tagRegex.find(body)?.groupValues?.get(1) ?: currentVersion
            } else {
                logger.debug { "Version check returned status ${response.statusCode()}" }
                currentVersion
            }
        } catch (e: Exception) {
            logger.debug { "Version check HTTP failed: ${e.message}" }
            currentVersion
        }
    }

    private fun loadCache(): UpdateCache? {
        val cacheFile = File(cacheDir, "update_cache.json")
        if (!cacheFile.exists()) return null
        return try {
            json.decodeFromString(UpdateCache.serializer(), cacheFile.readText())
        } catch (_: Exception) { null }
    }

    private fun saveCache(cache: UpdateCache) {
        val cacheFile = File(cacheDir, "update_cache.json")
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeText(json.encodeToString(UpdateCache.serializer(), cache))
    }

    private fun isStale(checkedAt: String): Boolean {
        return try {
            val checked = Instant.parse(checkedAt)
            checked.plus(CHECK_INTERVAL_HOURS, ChronoUnit.HOURS).isBefore(Instant.now())
        } catch (_: Exception) { true }
    }

    companion object {
        const val CURRENT_VERSION = "0.1.0"
        const val CHECK_INTERVAL_HOURS = 24L

        fun defaultCacheDir(): String {
            val configDir = System.getenv("CLAUDE_CONFIG_DIR")
                ?: "${System.getProperty("user.home")}/.claude"
            return "$configDir/cache"
        }
    }
}

@Serializable
data class UpdateCache(val latestVersion: String, val checkedAt: String)

data class UpdateCheckResult(
    val updateAvailable: Boolean = false,
    val currentVersion: String = "",
    val latestVersion: String = "",
    val fromCache: Boolean = false,
    val error: String? = null
)
