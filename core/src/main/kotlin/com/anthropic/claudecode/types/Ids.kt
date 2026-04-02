package com.anthropic.claudecode.types

/**
 * Session ID branded type - provides type safety for session identifiers
 * 会话 ID 品牌类型 - 为会话标识符提供类型安全
 *
 * Uses inline value class for zero-overhead wrapping
 * 使用内联值类实现零开销包装
 */
@JvmInline
value class SessionId(val value: String) {
    override fun toString(): String = value
}

/**
 * Agent ID branded type - provides type safety for agent identifiers
 * 代理 ID 品牌类型 - 为代理标识符提供类型安全
 *
 * Agent IDs must match the pattern: alphanumeric with hyphens and underscores
 * 代理 ID 必须匹配模式：字母数字加连字符和下划线
 */
@JvmInline
value class AgentId(val value: String) {
    companion object {
        // Pattern for valid agent IDs / 有效代理 ID 的模式
        private val VALID_PATTERN = Regex("^[a-zA-Z0-9_-]+$")

        /**
         * Safely convert a string to AgentId, returns null if invalid
         * 安全地将字符串转换为 AgentId，如果无效返回 null
         */
        fun fromString(s: String): AgentId? =
            if (VALID_PATTERN.matches(s)) AgentId(s) else null
    }

    override fun toString(): String = value
}

/**
 * Helper to create a SessionId from a string
 * 从字符串创建 SessionId 的辅助函数
 */
fun String.asSessionId(): SessionId = SessionId(this)

/**
 * Helper to create an AgentId from a string
 * 从字符串创建 AgentId 的辅助函数
 */
fun String.asAgentId(): AgentId = AgentId(this)
