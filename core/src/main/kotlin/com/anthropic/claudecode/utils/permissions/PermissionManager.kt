package com.anthropic.claudecode.utils.permissions

import com.anthropic.claudecode.types.PermissionMode
import com.anthropic.claudecode.types.ToolPermissionContext
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Permission manager - decides whether tools can execute
 * 权限管理器 - 决定工具是否可以执行
 *
 * Maps from TypeScript utils/permissions/permissions.ts.
 * Implements the multi-layer permission rule engine:
 * deny rules -> ask rules -> allow rules -> mode-based default.
 * 映射自 TypeScript utils/permissions/permissions.ts。
 * 实现多层权限规则引擎：
 * 拒绝规则 -> 询问规则 -> 允许规则 -> 基于模式的默认。
 */
class PermissionManager(
    private var context: ToolPermissionContext = ToolPermissionContext()
) {

    /**
     * Check permissions for a tool execution
     * 检查工具执行的权限
     *
     * @param toolName Tool identifier / 工具标识符
     * @param inputDescription Human-readable description of the input / 输入的人类可读描述
     * @return Permission decision / 权限决定
     */
    fun checkPermission(
        toolName: String,
        inputDescription: String = ""
    ): PermissionDecision {
        val target = "$toolName:$inputDescription"

        // Phase 1: Check deny rules (highest priority) / 阶段 1：检查拒绝规则（最高优先级）
        val denyMatch = matchRulesFromMap(context.alwaysDenyRules, target, toolName)
        if (denyMatch != null) {
            logger.info { "Tool '$toolName' denied by rule: $denyMatch" }
            return PermissionDecision(
                behavior = PermissionBehavior.DENY,
                message = "Denied by rule: $denyMatch"
            )
        }

        // Phase 2: Check ask rules / 阶段 2：检查询问规则
        val askMatch = matchRulesFromMap(context.alwaysAskRules, target, toolName)
        if (askMatch != null) {
            return PermissionDecision(
                behavior = PermissionBehavior.ASK,
                message = "Requires approval: $askMatch"
            )
        }

        // Phase 3: Check allow rules / 阶段 3：检查允许规则
        val allowMatch = matchRulesFromMap(context.alwaysAllowRules, target, toolName)
        if (allowMatch != null) {
            return PermissionDecision(
                behavior = PermissionBehavior.ALLOW,
                message = "Allowed by rule: $allowMatch"
            )
        }

        // Phase 4: Mode-based default / 阶段 4：基于模式的默认
        return when (context.mode) {
            PermissionMode.AUTO -> PermissionDecision(PermissionBehavior.ALLOW, "Auto-approved")
            PermissionMode.BYPASS_PERMISSIONS -> PermissionDecision(PermissionBehavior.ALLOW, "Bypass mode")
            PermissionMode.PLAN -> PermissionDecision(PermissionBehavior.DENY, "Plan mode: tools disabled")
            else -> PermissionDecision(PermissionBehavior.ASK, "Requires user approval")
        }
    }

    fun getContext(): ToolPermissionContext = context
    fun setContext(newContext: ToolPermissionContext) { context = newContext }

    /**
     * Match rules from a ToolPermissionRulesBySource map
     * 从 ToolPermissionRulesBySource 映射中匹配规则
     */
    private fun matchRulesFromMap(
        rulesMap: Map<*, List<String>>,
        target: String,
        toolName: String
    ): String? {
        for ((_, rules) in rulesMap) {
            for (pattern in rules) {
                if (matchWildcardPattern(pattern, target) || matchWildcardPattern(pattern, toolName)) {
                    return pattern
                }
            }
        }
        return null
    }

    companion object {
        /**
         * Match a wildcard pattern against a string
         * 将通配符模式与字符串进行匹配
         *
         * Supports: * (match any), ? (match single char)
         * 支持：*（匹配任意）、?（匹配单个字符）
         */
        fun matchWildcardPattern(pattern: String, text: String): Boolean {
            val regexPattern = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".")
            return try {
                Regex("^$regexPattern${'$'}", RegexOption.IGNORE_CASE).matches(text)
            } catch (e: Exception) {
                pattern == text
            }
        }
    }
}

/** Permission behavior / 权限行为 */
enum class PermissionBehavior { ALLOW, DENY, ASK }

/** Result of a permission check / 权限检查的结果 */
data class PermissionDecision(
    val behavior: PermissionBehavior,
    val message: String
)
