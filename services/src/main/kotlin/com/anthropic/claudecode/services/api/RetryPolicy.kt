package com.anthropic.claudecode.services.api

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow

private val logger = KotlinLogging.logger {}

/**
 * RetryPolicy - configurable retry with exponential backoff
 * RetryPolicy - 可配置的指数退避重试
 *
 * Maps from TypeScript services/api/retry.ts.
 * Handles transient errors, rate limiting (429), and server errors (5xx).
 * 映射自 TypeScript services/api/retry.ts。
 * 处理瞬态错误、限流（429）和服务器错误（5xx）。
 */
class RetryPolicy(
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 1000,
    private val maxDelayMs: Long = 60_000,
    private val retryableStatuses: Set<Int> = setOf(429, 500, 502, 503, 529)
) {
    /**
     * Execute with retry / 带重试执行
     */
    suspend fun <T> execute(operation: suspend () -> T): T {
        var lastException: Exception? = null

        for (attempt in 0..maxRetries) {
            try {
                return operation()
            } catch (e: ApiException) {
                lastException = e

                if (attempt >= maxRetries || !shouldRetry(e)) {
                    throw e
                }

                val delay = calculateDelay(attempt, e)
                logger.warn { "API error ${e.statusCode}: ${e.message}. Retrying in ${delay}ms (attempt ${attempt + 1}/$maxRetries)" }
                delay(delay)

            } catch (e: java.net.http.HttpTimeoutException) {
                lastException = e
                if (attempt >= maxRetries) throw e

                val delay = calculateDelay(attempt, null)
                logger.warn { "Timeout. Retrying in ${delay}ms (attempt ${attempt + 1}/$maxRetries)" }
                delay(delay)

            } catch (e: java.io.IOException) {
                lastException = e
                if (attempt >= maxRetries) throw e

                val delay = calculateDelay(attempt, null)
                logger.warn { "IO error: ${e.message}. Retrying in ${delay}ms (attempt ${attempt + 1}/$maxRetries)" }
                delay(delay)
            }
        }

        throw lastException ?: RuntimeException("Retry exhausted")
    }

    private fun shouldRetry(e: ApiException): Boolean {
        return e.statusCode in retryableStatuses
    }

    private fun calculateDelay(attempt: Int, error: ApiException?): Long {
        // Check for Retry-After header (rate limiting)
        // 检查 Retry-After 头部（限流）
        if (error?.statusCode == 429 && error.retryAfterMs != null) {
            return error.retryAfterMs
        }

        // Exponential backoff with jitter / 带抖动的指数退避
        val exponential = baseDelayMs * 2.0.pow(attempt.toDouble()).toLong()
        val jitter = (Math.random() * baseDelayMs).toLong()
        return min(exponential + jitter, maxDelayMs)
    }
}

/**
 * API exception with status code and retry info
 * 带状态码和重试信息的 API 异常
 */
class ApiException(
    message: String,
    val statusCode: Int,
    val retryAfterMs: Long? = null,
    val errorType: String? = null
) : RuntimeException("HTTP $statusCode: $message")
