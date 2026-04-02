package com.anthropic.claudecode.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * External permission mode - modes exposed to users via CLI/config
 * 外部权限模式 - 通过 CLI/配置暴露给用户的模式
 */
@Serializable
enum class ExternalPermissionMode {
    @SerialName("acceptEdits")
    ACCEPT_EDITS,

    @SerialName("bypassPermissions")
    BYPASS_PERMISSIONS,

    @SerialName("default")
    DEFAULT,

    @SerialName("dontAsk")
    DONT_ASK,

    @SerialName("plan")
    PLAN
}

/**
 * Internal permission mode - includes internal-only modes
 * 内部权限模式 - 包含仅限内部使用的模式
 */
@Serializable
enum class PermissionMode {
    /** Ask user for each action / 每个操作询问用户 */
    @SerialName("default")
    DEFAULT,

    /** Auto-accept file edits / 自动接受文件编辑 */
    @SerialName("acceptEdits")
    ACCEPT_EDITS,

    /** Skip all permission checks / 跳过所有权限检查 */
    @SerialName("bypassPermissions")
    BYPASS_PERMISSIONS,

    /** Don't ask, deny by default / 不询问，默认拒绝 */
    @SerialName("dontAsk")
    DONT_ASK,

    /** Plan mode (read-only) / 计划模式（只读） */
    @SerialName("plan")
    PLAN,

    /** AI-driven permission decisions / AI 驱动的权限决策 */
    @SerialName("auto")
    AUTO,

    /** Bubble up to parent agent / 冒泡到父代理 */
    @SerialName("bubble")
    BUBBLE
}

/**
 * Permission behavior - the action to take for a permission check
 * 权限行为 - 权限检查时采取的操作
 */
@Serializable
enum class PermissionBehavior {
    @SerialName("allow")
    ALLOW,

    @SerialName("deny")
    DENY,

    @SerialName("ask")
    ASK
}

/**
 * Source of a permission rule
 * 权限规则的来源
 */
@Serializable
enum class PermissionRuleSource {
    @SerialName("userSettings")
    USER_SETTINGS,

    @SerialName("projectSettings")
    PROJECT_SETTINGS,

    @SerialName("localSettings")
    LOCAL_SETTINGS,

    @SerialName("flagSettings")
    FLAG_SETTINGS,

    @SerialName("policySettings")
    POLICY_SETTINGS,

    @SerialName("cliArg")
    CLI_ARG,

    @SerialName("command")
    COMMAND,

    @SerialName("session")
    SESSION
}

/**
 * Value of a permission rule (tool name and optional content)
 * 权限规则的值（工具名称和可选内容）
 */
@Serializable
data class PermissionRuleValue(
    /** Tool name the rule applies to / 规则适用的工具名称 */
    val toolName: String,
    /** Optional rule content (e.g., path pattern) / 可选的规则内容（例如路径模式） */
    val ruleContent: String? = null
)

/**
 * A complete permission rule with source and behavior
 * 包含来源和行为的完整权限规则
 */
@Serializable
data class PermissionRule(
    /** Where this rule was defined / 此规则的定义位置 */
    val source: PermissionRuleSource,
    /** What behavior this rule enforces / 此规则强制的行为 */
    val ruleBehavior: PermissionBehavior,
    /** The rule's value / 规则的值 */
    val ruleValue: PermissionRuleValue
)

/**
 * Destination for permission updates
 * 权限更新的目标位置
 */
@Serializable
enum class PermissionUpdateDestination {
    @SerialName("userSettings")
    USER_SETTINGS,

    @SerialName("projectSettings")
    PROJECT_SETTINGS,

    @SerialName("localSettings")
    LOCAL_SETTINGS,

    @SerialName("session")
    SESSION,

    @SerialName("cliArg")
    CLI_ARG
}

/**
 * Permission update operations - sealed class representing different update types
 * 权限更新操作 - 表示不同更新类型的密封类
 *
 * Maps from TypeScript discriminated union type PermissionUpdate.
 * 映射自 TypeScript 判别联合类型 PermissionUpdate。
 */
@Serializable
sealed class PermissionUpdate {
    /**
     * Add new permission rules / 添加新的权限规则
     */
    @Serializable
    @SerialName("addRules")
    data class AddRules(
        val destination: PermissionUpdateDestination,
        val rules: List<PermissionRuleValue>,
        val behavior: PermissionBehavior
    ) : PermissionUpdate()

    /**
     * Replace existing rules / 替换现有规则
     */
    @Serializable
    @SerialName("replaceRules")
    data class ReplaceRules(
        val destination: PermissionUpdateDestination,
        val rules: List<PermissionRuleValue>,
        val behavior: PermissionBehavior
    ) : PermissionUpdate()

    /**
     * Remove existing rules / 移除现有规则
     */
    @Serializable
    @SerialName("removeRules")
    data class RemoveRules(
        val destination: PermissionUpdateDestination,
        val rules: List<PermissionRuleValue>,
        val behavior: PermissionBehavior
    ) : PermissionUpdate()

    /**
     * Set permission mode / 设置权限模式
     */
    @Serializable
    @SerialName("setMode")
    data class SetMode(
        val destination: PermissionUpdateDestination,
        val mode: ExternalPermissionMode
    ) : PermissionUpdate()

    /**
     * Add additional working directories / 添加额外的工作目录
     */
    @Serializable
    @SerialName("addDirectories")
    data class AddDirectories(
        val destination: PermissionUpdateDestination,
        val directories: List<String>
    ) : PermissionUpdate()

    /**
     * Remove working directories / 移除工作目录
     */
    @Serializable
    @SerialName("removeDirectories")
    data class RemoveDirectories(
        val destination: PermissionUpdateDestination,
        val directories: List<String>
    ) : PermissionUpdate()
}

/**
 * Additional working directory with source tracking
 * 带来源跟踪的额外工作目录
 */
@Serializable
data class AdditionalWorkingDirectory(
    /** Directory path / 目录路径 */
    val path: String,
    /** Where this directory was specified / 此目录的指定位置 */
    val source: PermissionRuleSource
)

/**
 * Permission decision result - sealed class hierarchy
 * 权限决策结果 - 密封类层次结构
 *
 * Represents the outcome of a permission check.
 * 表示权限检查的结果。
 */
@Serializable
sealed class PermissionResult {
    /**
     * Permission granted / 权限已授予
     */
    @Serializable
    @SerialName("allow")
    data class Allow(
        /** Updated input after permission check / 权限检查后更新的输入 */
        val updatedInput: Map<String, kotlinx.serialization.json.JsonElement>? = null,
        /** Whether user modified the input / 用户是否修改了输入 */
        val userModified: Boolean = false,
        /** Reason for the decision / 决策原因 */
        val decisionReason: PermissionDecisionReason? = null,
        /** Associated tool use ID / 关联的工具使用 ID */
        val toolUseId: String? = null,
        /** User feedback on acceptance / 用户接受时的反馈 */
        val acceptFeedback: String? = null
    ) : PermissionResult()

    /**
     * User interaction required / 需要用户交互
     */
    @Serializable
    @SerialName("ask")
    data class Ask(
        /** Message to display to user / 显示给用户的消息 */
        val message: String,
        /** Updated input if any / 更新后的输入（如有） */
        val updatedInput: Map<String, kotlinx.serialization.json.JsonElement>? = null,
        /** Reason for the decision / 决策原因 */
        val decisionReason: PermissionDecisionReason? = null,
        /** Suggested permission updates / 建议的权限更新 */
        val suggestions: List<PermissionUpdate> = emptyList(),
        /** Blocked file path / 被阻止的文件路径 */
        val blockedPath: String? = null,
        /** Pending classifier check / 待处理的分类器检查 */
        val pendingClassifierCheck: PendingClassifierCheck? = null
    ) : PermissionResult()

    /**
     * Permission denied / 权限被拒绝
     */
    @Serializable
    @SerialName("deny")
    data class Deny(
        /** Denial reason message / 拒绝原因消息 */
        val message: String,
        /** Reason for the decision / 决策原因 */
        val decisionReason: PermissionDecisionReason,
        /** Associated tool use ID / 关联的工具使用 ID */
        val toolUseId: String? = null
    ) : PermissionResult()

    /**
     * Pass through to the next handler / 传递给下一个处理器
     */
    @Serializable
    @SerialName("passthrough")
    data class Passthrough(
        /** Passthrough message / 传递消息 */
        val message: String,
        /** Reason for the decision / 决策原因 */
        val decisionReason: PermissionDecisionReason? = null,
        /** Suggested permission updates / 建议的权限更新 */
        val suggestions: List<PermissionUpdate> = emptyList(),
        /** Blocked file path / 被阻止的文件路径 */
        val blockedPath: String? = null,
        /** Pending classifier check / 待处理的分类器检查 */
        val pendingClassifierCheck: PendingClassifierCheck? = null
    ) : PermissionResult()
}

/**
 * Pending classifier check details
 * 待处理的分类器检查详情
 */
@Serializable
data class PendingClassifierCheck(
    /** Command to check / 要检查的命令 */
    val command: String,
    /** Current working directory / 当前工作目录 */
    val cwd: String,
    /** Descriptions for the classifier / 分类器的描述 */
    val descriptions: List<String>
)

/**
 * Permission decision reason - why a permission was granted/denied
 * 权限决策原因 - 为什么权限被授予/拒绝
 */
@Serializable
sealed class PermissionDecisionReason {
    /**
     * Decision based on a permission rule / 基于权限规则的决策
     */
    @Serializable
    @SerialName("rule")
    data class Rule(val rule: PermissionRule) : PermissionDecisionReason()

    /**
     * Decision based on permission mode / 基于权限模式的决策
     */
    @Serializable
    @SerialName("mode")
    data class Mode(val mode: PermissionMode) : PermissionDecisionReason()

    /**
     * Decision from sub-command results / 来自子命令结果的决策
     */
    @Serializable
    @SerialName("subcommandResults")
    data class SubcommandResults(
        val reasons: Map<String, PermissionResult>
    ) : PermissionDecisionReason()

    /**
     * Decision from a hook / 来自钩子的决策
     */
    @Serializable
    @SerialName("hook")
    data class Hook(
        val hookName: String,
        val hookSource: String? = null,
        val reason: String? = null
    ) : PermissionDecisionReason()

    /**
     * Decision from async agent / 来自异步代理的决策
     */
    @Serializable
    @SerialName("asyncAgent")
    data class AsyncAgent(val reason: String) : PermissionDecisionReason()

    /**
     * Decision from sandbox override / 来自沙箱覆盖的决策
     */
    @Serializable
    @SerialName("sandboxOverride")
    data class SandboxOverride(val reason: String) : PermissionDecisionReason()

    /**
     * Decision from classifier / 来自分类器的决策
     */
    @Serializable
    @SerialName("classifier")
    data class Classifier(
        val classifier: String,
        val reason: String
    ) : PermissionDecisionReason()

    /**
     * Decision based on working directory / 基于工作目录的决策
     */
    @Serializable
    @SerialName("workingDir")
    data class WorkingDir(val reason: String) : PermissionDecisionReason()

    /**
     * Decision from safety check / 来自安全检查的决策
     */
    @Serializable
    @SerialName("safetyCheck")
    data class SafetyCheck(
        val reason: String,
        val classifierApprovable: Boolean
    ) : PermissionDecisionReason()

    /**
     * Other decision reason / 其他决策原因
     */
    @Serializable
    @SerialName("other")
    data class Other(val reason: String) : PermissionDecisionReason()
}

/**
 * Risk level for permission explanations
 * 权限解释的风险级别
 */
@Serializable
enum class RiskLevel {
    LOW, MEDIUM, HIGH
}

/**
 * Permission explanation for user display
 * 用于用户展示的权限解释
 */
@Serializable
data class PermissionExplanation(
    /** Risk level assessment / 风险级别评估 */
    val riskLevel: RiskLevel,
    /** User-friendly explanation / 用户友好的解释 */
    val explanation: String,
    /** Detailed reasoning / 详细推理 */
    val reasoning: String,
    /** Risk description / 风险描述 */
    val risk: String
)

/**
 * Tool permission rules organized by source
 * 按来源组织的工具权限规则
 */
typealias ToolPermissionRulesBySource = Map<PermissionRuleSource, List<String>>

/**
 * Tool permission context - carries all permission state for tool execution
 * 工具权限上下文 - 携带工具执行的所有权限状态
 *
 * This is a deeply immutable structure (maps TypeScript DeepImmutable).
 * 这是一个深度不可变的结构（映射 TypeScript DeepImmutable）。
 */
data class ToolPermissionContext(
    /** Current permission mode / 当前权限模式 */
    val mode: PermissionMode = PermissionMode.DEFAULT,
    /** Additional working directories / 额外的工作目录 */
    val additionalWorkingDirectories: Map<String, AdditionalWorkingDirectory> = emptyMap(),
    /** Rules that always allow / 始终允许的规则 */
    val alwaysAllowRules: ToolPermissionRulesBySource = emptyMap(),
    /** Rules that always deny / 始终拒绝的规则 */
    val alwaysDenyRules: ToolPermissionRulesBySource = emptyMap(),
    /** Rules that always ask / 始终询问的规则 */
    val alwaysAskRules: ToolPermissionRulesBySource = emptyMap(),
    /** Whether bypass permissions mode is available / 是否可用绕过权限模式 */
    val isBypassPermissionsModeAvailable: Boolean = false,
    /** Whether auto mode is available / 是否可用自动模式 */
    val isAutoModeAvailable: Boolean? = null,
    /** Rules stripped for safety / 出于安全被剥离的规则 */
    val strippedDangerousRules: ToolPermissionRulesBySource? = null,
    /** Whether to avoid permission prompts (e.g., background agents) / 是否避免权限提示（例如后台代理） */
    val shouldAvoidPermissionPrompts: Boolean? = null,
    /** Whether to await automated checks before showing dialog / 是否在显示对话框前等待自动检查 */
    val awaitAutomatedChecksBeforeDialog: Boolean? = null,
    /** Permission mode before plan mode entry / 进入计划模式前的权限模式 */
    val prePlanMode: PermissionMode? = null
)

/**
 * Factory function for empty tool permission context
 * 空工具权限上下文的工厂函数
 */
fun emptyToolPermissionContext(): ToolPermissionContext = ToolPermissionContext()
