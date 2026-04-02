package com.anthropic.claudecode.ui.permission

import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred

private val logger = KotlinLogging.logger {}

/**
 * PermissionUI - interactive permission prompt handler
 * PermissionUI - 交互式权限提示处理器
 *
 * Maps from TypeScript components/permissions/PermissionDialog.tsx.
 * Presents tool permission requests to the user and collects decisions.
 * Supports allow-once, allow-always, deny, and edit-input actions.
 * 映射自 TypeScript components/permissions/PermissionDialog.tsx。
 * 向用户展示工具权限请求并收集决策。
 * 支持允许一次、始终允许、拒绝和编辑输入操作。
 */
class PermissionUI(
    private val renderer: PermissionRenderer = PermissionRenderer()
) {
    private var pendingPrompt: CompletableDeferred<PermissionDecision>? = null

    /**
     * Show a permission prompt and wait for user decision
     * 显示权限提示并等待用户决策
     *
     * @param request The permission request details / 权限请求详情
     * @return User's decision / 用户的决策
     */
    suspend fun prompt(request: PermissionRequest): PermissionDecision {
        val deferred = CompletableDeferred<PermissionDecision>()
        pendingPrompt = deferred

        // Render the permission prompt / 渲染权限提示
        renderer.render(request)

        // Wait for user input / 等待用户输入
        return deferred.await()
    }

    /**
     * Resolve the pending permission prompt with a decision
     * 用决策解决待处理的权限提示
     */
    fun resolve(decision: PermissionDecision) {
        pendingPrompt?.complete(decision)
        pendingPrompt = null
    }

    /**
     * Check if a permission prompt is pending
     * 检查是否有待处理的权限提示
     */
    fun hasPendingPrompt(): Boolean = pendingPrompt?.isActive == true

    /**
     * Cancel the pending prompt / 取消待处理的提示
     */
    fun cancel() {
        pendingPrompt?.complete(PermissionDecision.Deny)
        pendingPrompt = null
    }
}

/**
 * Permission request details / 权限请求详情
 */
data class PermissionRequest(
    val toolName: String,
    val message: String,
    val explanation: PermissionExplanation? = null,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val inputSummary: String? = null,
    val suggestions: List<PermissionUpdate> = emptyList()
)

/**
 * User's permission decision / 用户的权限决策
 */
sealed class PermissionDecision {
    /** Allow this single tool use / 允许本次工具使用 */
    data object AllowOnce : PermissionDecision()

    /** Always allow this tool / 始终允许此工具 */
    data class AllowAlways(
        val scope: AllowScope = AllowScope.SESSION
    ) : PermissionDecision()

    /** Deny this tool use / 拒绝本次工具使用 */
    data object Deny : PermissionDecision()

    /** Deny and block this tool / 拒绝并阻止此工具 */
    data object DenyAlways : PermissionDecision()

    /** Allow with edited input / 允许但编辑输入 */
    data class AllowEdited(val feedback: String) : PermissionDecision()
}

enum class AllowScope { SESSION, PROJECT, GLOBAL }

/**
 * PermissionRenderer - renders permission prompts to terminal
 * PermissionRenderer - 在终端渲染权限提示
 */
class PermissionRenderer {
    fun render(request: PermissionRequest) {
        val riskColor = when (request.riskLevel) {
            RiskLevel.LOW -> "\u001b[32m"    // green
            RiskLevel.MEDIUM -> "\u001b[33m"  // yellow
            RiskLevel.HIGH -> "\u001b[31m"    // red
        }
        val reset = "\u001b[0m"

        println()
        println("${riskColor}--- Permission Required ---${reset}")
        println("  Tool: ${request.toolName}")
        println("  ${request.message}")
        if (request.inputSummary != null) {
            println("  Input: ${request.inputSummary}")
        }
        request.explanation?.let {
            println("  Risk: ${it.risk}")
            println("  ${it.explanation}")
        }
        println()
        println("  [y] Allow once  [a] Always allow  [n] Deny  [d] Deny always  [e] Edit")
        print("  > ")
    }

    fun renderDecision(decision: PermissionDecision) {
        val msg = when (decision) {
            is PermissionDecision.AllowOnce -> "\u001b[32mAllowed (once)\u001b[0m"
            is PermissionDecision.AllowAlways -> "\u001b[32mAllowed (always, ${decision.scope})\u001b[0m"
            is PermissionDecision.Deny -> "\u001b[31mDenied\u001b[0m"
            is PermissionDecision.DenyAlways -> "\u001b[31mDenied (always)\u001b[0m"
            is PermissionDecision.AllowEdited -> "\u001b[33mAllowed (edited)\u001b[0m"
        }
        println("  $msg")
    }
}
