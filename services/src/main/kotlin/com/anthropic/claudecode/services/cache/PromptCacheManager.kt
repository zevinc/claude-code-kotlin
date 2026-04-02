package com.anthropic.claudecode.services.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * PromptCacheManager - manages prompt caching for cost reduction
 * PromptCacheManager - 管理提示缓存以降低成本
 *
 * Maps from TypeScript services/claude/promptCache.ts.
 * Implements a cache-control strategy for system prompts and
 * frequently-used tool definitions to reduce API costs.
 * 映射自 TypeScript services/claude/promptCache.ts。
 * 为系统提示和常用工具定义实现缓存控制策略以降低 API 成本。
 */
class PromptCacheManager(
    private val maxCacheEntries: Int = MAX_ENTRIES
) {
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private var totalCacheHits: Long = 0
    private var totalCacheMisses: Long = 0

    /**
     * Get or compute a cached prompt segment
     * 获取或计算缓存的提示片段
     */
    fun getOrCompute(key: String, compute: () -> String): CachedPrompt {
        val hash = computeHash(key)
        val existing = cache[hash]

        if (existing != null && existing.content == key) {
            totalCacheHits++
            existing.hitCount++
            existing.lastAccessedMs = System.currentTimeMillis()
            return CachedPrompt(content = existing.content, cached = true, hash = hash)
        }

        totalCacheMisses++
        val content = compute()
        val entry = CacheEntry(
            content = content,
            hash = hash,
            createdMs = System.currentTimeMillis(),
            lastAccessedMs = System.currentTimeMillis()
        )

        // Evict if full / 如果满了则驱逐
        if (cache.size >= maxCacheEntries) {
            evictLeastUsed()
        }

        cache[hash] = entry
        return CachedPrompt(content = content, cached = false, hash = hash)
    }

    /**
     * Build cache-control markers for the API request
     * 为 API 请求构建缓存控制标记
     *
     * Returns content blocks with cache_control annotations
     * for the Anthropic prompt caching beta.
     * 返回带有 cache_control 注解的内容块，
     * 用于 Anthropic 提示缓存测试版。
     */
    fun buildCacheBreakpoints(
        systemPrompt: String,
        toolDefinitions: String? = null
    ): List<CacheBreakpoint> {
        val breakpoints = mutableListOf<CacheBreakpoint>()

        // System prompt is always cached / 系统提示始终被缓存
        breakpoints.add(CacheBreakpoint(
            content = systemPrompt,
            type = CacheBreakpointType.SYSTEM_PROMPT,
            cacheControl = true
        ))

        // Tool definitions are cached if stable / 工具定义如果稳定则被缓存
        if (toolDefinitions != null) {
            breakpoints.add(CacheBreakpoint(
                content = toolDefinitions,
                type = CacheBreakpointType.TOOL_DEFINITIONS,
                cacheControl = true
            ))
        }

        return breakpoints
    }

    /**
     * Get cache statistics / 获取缓存统计
     */
    fun getStats(): CacheStats = CacheStats(
        entries = cache.size,
        hits = totalCacheHits,
        misses = totalCacheMisses,
        hitRate = if (totalCacheHits + totalCacheMisses > 0) {
            totalCacheHits.toDouble() / (totalCacheHits + totalCacheMisses)
        } else 0.0
    )

    /**
     * Clear cache / 清除缓存
     */
    fun clear() {
        cache.clear()
        logger.info { "Prompt cache cleared" }
    }

    private fun evictLeastUsed() {
        val leastUsed = cache.entries
            .minByOrNull { it.value.hitCount * 1000 + it.value.lastAccessedMs / 1000 }
        if (leastUsed != null) {
            cache.remove(leastUsed.key)
            logger.debug { "Evicted cache entry: ${leastUsed.key}" }
        }
    }

    private fun computeHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).take(16)
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_ENTRIES = 100
    }
}

data class CacheEntry(
    val content: String,
    val hash: String,
    val createdMs: Long,
    var lastAccessedMs: Long,
    var hitCount: Long = 0
)

data class CachedPrompt(
    val content: String,
    val cached: Boolean,
    val hash: String
)

enum class CacheBreakpointType { SYSTEM_PROMPT, TOOL_DEFINITIONS, CONVERSATION_PREFIX }

data class CacheBreakpoint(
    val content: String,
    val type: CacheBreakpointType,
    val cacheControl: Boolean = false
)

@Serializable
data class CacheStats(
    val entries: Int = 0,
    val hits: Long = 0,
    val misses: Long = 0,
    val hitRate: Double = 0.0
)
