package com.anthropic.claudecode.utils.tokens

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * TokenCounter - estimates and tracks token usage across sessions
 * TokenCounter - 估算和跟踪跨会话的令牌使用情况
 *
 * Maps from TypeScript utils/tokenCount.ts.
 * Uses character-based estimation (4 chars ~ 1 token for English).
 * Tracks input, output, cache read, and cache creation tokens.
 * 映射自 TypeScript utils/tokenCount.ts。
 * 使用基于字符的估算（英语中 4 个字符约等于 1 个令牌）。
 * 跟踪输入、输出、缓存读取和缓存创建令牌。
 */
class TokenCounter {
    private val _inputTokens = AtomicLong(0)
    private val _outputTokens = AtomicLong(0)
    private val _cacheReadTokens = AtomicLong(0)
    private val _cacheCreateTokens = AtomicLong(0)
    private val _apiCalls = AtomicLong(0)

    val inputTokens: Long get() = _inputTokens.get()
    val outputTokens: Long get() = _outputTokens.get()
    val cacheReadTokens: Long get() = _cacheReadTokens.get()
    val cacheCreateTokens: Long get() = _cacheCreateTokens.get()
    val apiCalls: Long get() = _apiCalls.get()

    /**
     * Estimate token count for a string
     * 估算字符串的令牌数
     *
     * Uses a simple heuristic: ~4 characters per token for English,
     * ~2 characters per token for CJK.
     * 使用简单启发式：英语每令牌约 4 个字符，
     * CJK 每令牌约 2 个字符。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0

        var charCount = 0
        var cjkCount = 0
        for (ch in text) {
            charCount++
            if (isCJK(ch)) cjkCount++
        }

        val nonCjkChars = charCount - cjkCount
        return (nonCjkChars / CHARS_PER_TOKEN_EN + cjkCount / CHARS_PER_TOKEN_CJK).toInt()
            .coerceAtLeast(1)
    }

    /**
     * Record token usage from an API response
     * 从 API 响应记录令牌使用情况
     */
    fun recordUsage(
        inputTokens: Long = 0,
        outputTokens: Long = 0,
        cacheReadTokens: Long = 0,
        cacheCreateTokens: Long = 0
    ) {
        _inputTokens.addAndGet(inputTokens)
        _outputTokens.addAndGet(outputTokens)
        _cacheReadTokens.addAndGet(cacheReadTokens)
        _cacheCreateTokens.addAndGet(cacheCreateTokens)
        _apiCalls.incrementAndGet()
    }

    /**
     * Get current usage summary / 获取当前使用摘要
     */
    fun getSummary(): TokenUsageSummary = TokenUsageSummary(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cacheReadTokens = cacheReadTokens,
        cacheCreateTokens = cacheCreateTokens,
        apiCalls = apiCalls
    )

    /**
     * Reset all counters / 重置所有计数器
     */
    fun reset() {
        _inputTokens.set(0)
        _outputTokens.set(0)
        _cacheReadTokens.set(0)
        _cacheCreateTokens.set(0)
        _apiCalls.set(0)
    }

    private fun isCJK(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    companion object {
        const val CHARS_PER_TOKEN_EN = 4.0
        const val CHARS_PER_TOKEN_CJK = 2.0
    }
}

@Serializable
data class TokenUsageSummary(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheCreateTokens: Long = 0,
    val apiCalls: Long = 0
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/**
 * CostCalculator - calculates API usage costs
 * CostCalculator - 计算 API 使用成本
 *
 * Maps from TypeScript utils/cost.ts.
 * Pricing is per million tokens, varying by model.
 * 映射自 TypeScript utils/cost.ts。
 * 定价按每百万令牌计算，因模型而异。
 */
object CostCalculator {

    /**
     * Calculate cost for given token usage and model
     * 计算给定令牌使用量和模型的成本
     */
    fun calculate(model: String, usage: TokenUsageSummary): CostBreakdown {
        val pricing = getPricing(model)

        val inputCost = usage.inputTokens * pricing.inputPerMillion / 1_000_000.0
        val outputCost = usage.outputTokens * pricing.outputPerMillion / 1_000_000.0
        val cacheReadCost = usage.cacheReadTokens * pricing.cacheReadPerMillion / 1_000_000.0
        val cacheCreateCost = usage.cacheCreateTokens * pricing.cacheCreatePerMillion / 1_000_000.0
        val totalCost = inputCost + outputCost + cacheReadCost + cacheCreateCost

        return CostBreakdown(
            inputCost = inputCost,
            outputCost = outputCost,
            cacheReadCost = cacheReadCost,
            cacheCreateCost = cacheCreateCost,
            totalCost = totalCost,
            model = model
        )
    }

    /**
     * Format cost as human-readable string
     * 将成本格式化为人类可读字符串
     */
    fun formatCost(cost: Double): String {
        return if (cost < 0.01) {
            "$${"%.4f".format(cost)}"
        } else {
            "$${"%.2f".format(cost)}"
        }
    }

    private fun getPricing(model: String): ModelPricing = when {
        "opus" in model.lowercase() -> ModelPricing(
            inputPerMillion = 15.0, outputPerMillion = 75.0,
            cacheReadPerMillion = 1.5, cacheCreatePerMillion = 18.75
        )
        "sonnet" in model.lowercase() -> ModelPricing(
            inputPerMillion = 3.0, outputPerMillion = 15.0,
            cacheReadPerMillion = 0.3, cacheCreatePerMillion = 3.75
        )
        "haiku" in model.lowercase() -> ModelPricing(
            inputPerMillion = 0.25, outputPerMillion = 1.25,
            cacheReadPerMillion = 0.03, cacheCreatePerMillion = 0.3
        )
        else -> ModelPricing(
            inputPerMillion = 3.0, outputPerMillion = 15.0,
            cacheReadPerMillion = 0.3, cacheCreatePerMillion = 3.75
        )
    }
}

data class ModelPricing(
    val inputPerMillion: Double,
    val outputPerMillion: Double,
    val cacheReadPerMillion: Double = 0.0,
    val cacheCreatePerMillion: Double = 0.0
)

@Serializable
data class CostBreakdown(
    val inputCost: Double = 0.0,
    val outputCost: Double = 0.0,
    val cacheReadCost: Double = 0.0,
    val cacheCreateCost: Double = 0.0,
    val totalCost: Double = 0.0,
    val model: String = ""
)
