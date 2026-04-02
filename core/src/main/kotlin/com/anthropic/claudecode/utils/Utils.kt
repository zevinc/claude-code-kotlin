package com.anthropic.claudecode.utils

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.Path

private val logger = KotlinLogging.logger {}

/**
 * Environment variable utilities
 * 环境变量工具函数
 */
object EnvUtils {
    /**
     * Check if an environment variable is set to a truthy value
     * 检查环境变量是否设置为真值
     *
     * Truthy values: "1", "true", "yes", "on" (case-insensitive)
     * 真值："1"、"true"、"yes"、"on"（不区分大小写）
     */
    fun isEnvTruthy(value: String?): Boolean {
        if (value == null) return false
        return value.lowercase() in setOf("1", "true", "yes", "on")
    }

    /**
     * Check if an environment variable is set to a falsy value
     * 检查环境变量是否设置为假值
     *
     * Falsy values: "0", "false", "no", "off" (case-insensitive)
     * 假值："0"、"false"、"no"、"off"（不区分大小写）
     */
    fun isEnvFalsy(value: String?): Boolean {
        if (value == null) return true
        return value.lowercase() in setOf("0", "false", "no", "off", "")
    }

    /**
     * Get current working directory
     * 获取当前工作目录
     */
    fun getCwd(): Path = Path(System.getProperty("user.dir"))

    /**
     * Get user home directory
     * 获取用户主目录
     */
    fun getHome(): Path = Path(System.getProperty("user.home"))

    /**
     * Get the platform name (for compatibility checks)
     * 获取平台名称（用于兼容性检查）
     */
    fun getPlatform(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> "win32"
            os.contains("mac") || os.contains("darwin") -> "darwin"
            os.contains("linux") -> "linux"
            else -> os
        }
    }

    /**
     * Whether the current platform supports Unix-style process suspension (SIGSTOP/SIGCONT)
     * 当前平台是否支持 Unix 风格的进程挂起（SIGSTOP/SIGCONT）
     */
    val supportsSuspend: Boolean
        get() = getPlatform() != "win32"

    /** Whether running in CI environment / 是否在 CI 环境中运行 */
    val isCI: Boolean = System.getenv("CI") != null || System.getenv("CONTINUOUS_INTEGRATION") != null

    /** Whether terminal supports colors / 终端是否支持颜色 */
    val supportsColor: Boolean = System.getenv("NO_COLOR") == null && !isCI

    /** Current platform name / 当前平台名称 */
    @get:JvmName("platformName")
    val platform: String = System.getProperty("os.name", "unknown")

    /** Current architecture / 当前架构 */
    val arch: String = System.getProperty("os.arch", "unknown")
}

/**
 * Debug logging utilities
 * 调试日志工具
 */
object DebugUtils {
    private val debugLogger = KotlinLogging.logger("debug")

    /**
     * Log a message for debugging purposes
     * 记录用于调试目的的消息
     *
     * @param message Debug message / 调试消息
     * @param level Log level (default: "debug") / 日志级别（默认："debug"）
     */
    fun logForDebugging(message: String, level: String = "debug") {
        when (level) {
            "warn" -> debugLogger.warn { message }
            "error" -> debugLogger.error { message }
            "info" -> debugLogger.info { message }
            else -> debugLogger.debug { message }
        }
    }
}

/**
 * Error handling utilities
 * 错误处理工具
 */
object ErrorUtils {
    /**
     * Convert any value to an Exception
     * 将任意值转换为异常
     *
     * @param error The error value / 错误值
     * @return An Exception wrapping the error / 包装错误的异常
     */
    fun toError(error: Any?): Exception {
        return when (error) {
            is Exception -> error
            is String -> Exception(error)
            null -> Exception("Unknown error")
            else -> Exception(error.toString())
        }
    }

    /**
     * Safely log an error
     * 安全地记录错误
     */
    fun logError(error: Any?) {
        val exception = toError(error)
        logger.error(exception) { "Error occurred: ${exception.message}" }
    }
}

/**
 * String utilities / 字符串工具
 */
object StringUtils {
    /**
     * Truncate a string to the specified maximum length
     * 将字符串截断到指定的最大长度
     *
     * @param str Input string / 输入字符串
     * @param maxLength Maximum length / 最大长度
     * @param suffix Suffix to append when truncated / 截断时附加的后缀
     */
    fun truncate(str: String, maxLength: Int, suffix: String = "..."): String {
        if (str.length <= maxLength) return str
        return str.take(maxLength - suffix.length) + suffix
    }

    /**
     * Strip ANSI escape sequences from a string
     * 从字符串中去除 ANSI 转义序列
     */
    fun stripAnsi(str: String): String {
        return str.replace(Regex("\u001B\\[[;\\d]*[A-Za-z]"), "")
    }
}

/**
 * File path utilities / 文件路径工具
 */
object PathUtils {
    /**
     * Resolve a path relative to the project root
     * 解析相对于项目根目录的路径
     */
    fun resolveFromProjectRoot(projectRoot: Path, relativePath: String): Path {
        return projectRoot.resolve(relativePath).normalize()
    }

    /**
     * Check if a path is within the given root directory
     * 检查路径是否在给定的根目录内
     *
     * @param path Path to check / 要检查的路径
     * @param root Root directory / 根目录
     * @return true if path is within root / 如果路径在根目录内则返回 true
     */
    fun isWithinRoot(path: Path, root: Path): Boolean {
        val normalizedPath = path.toAbsolutePath().normalize()
        val normalizedRoot = root.toAbsolutePath().normalize()
        return normalizedPath.startsWith(normalizedRoot)
    }
}
