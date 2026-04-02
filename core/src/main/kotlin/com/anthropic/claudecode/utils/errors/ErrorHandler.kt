package com.anthropic.claudecode.utils.errors

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.PrintWriter
import java.io.StringWriter

private val logger = KotlinLogging.logger {}

/**
 * ErrorHandler - centralized error handling and recovery
 * ErrorHandler - 集中式错误处理和恢复
 *
 * Maps from TypeScript utils/errors.ts and errorHandling.ts.
 * Categorizes errors, provides user-friendly messages,
 * and suggests recovery actions.
 * 映射自 TypeScript utils/errors.ts 和 errorHandling.ts。
 * 对错误进行分类，提供用户友好的消息，并建议恢复操作。
 */
object ErrorHandler {

    /**
     * Handle an error and produce a user-friendly result
     * 处理错误并生成用户友好的结果
     */
    fun handle(error: Throwable, context: ErrorContext = ErrorContext()): ErrorResult {
        val category = categorize(error)
        val userMessage = getUserMessage(category, error)
        val recovery = getRecoveryActions(category)

        logger.error(error) { "Error [${category.name}] in ${context.operation}: ${error.message}" }

        return ErrorResult(
            category = category,
            message = userMessage,
            technicalDetail = error.message ?: "Unknown error",
            stackTrace = if (context.includeStackTrace) getStackTrace(error) else null,
            recoveryActions = recovery,
            retryable = isRetryable(category)
        )
    }

    /**
     * Categorize an error / 对错误进行分类
     */
    fun categorize(error: Throwable): ErrorCategory = when {
        error is java.net.SocketTimeoutException -> ErrorCategory.NETWORK_TIMEOUT
        error is java.net.ConnectException -> ErrorCategory.NETWORK_CONNECT
        error is java.net.UnknownHostException -> ErrorCategory.NETWORK_DNS
        error is java.io.IOException -> ErrorCategory.IO
        error is SecurityException -> ErrorCategory.PERMISSION
        error is OutOfMemoryError -> ErrorCategory.RESOURCE
        error is IllegalArgumentException -> ErrorCategory.VALIDATION
        error is IllegalStateException -> ErrorCategory.STATE
        error.message?.contains("rate limit", ignoreCase = true) == true -> ErrorCategory.RATE_LIMIT
        error.message?.contains("401", ignoreCase = true) == true -> ErrorCategory.AUTH
        error.message?.contains("413", ignoreCase = true) == true -> ErrorCategory.CONTEXT_TOO_LONG
        error.message?.contains("overloaded", ignoreCase = true) == true -> ErrorCategory.OVERLOADED
        else -> ErrorCategory.UNKNOWN
    }

    private fun getUserMessage(category: ErrorCategory, error: Throwable): String = when (category) {
        ErrorCategory.NETWORK_TIMEOUT -> "Request timed out. Please check your network connection."
        ErrorCategory.NETWORK_CONNECT -> "Cannot connect to the API server. Please check your network."
        ErrorCategory.NETWORK_DNS -> "DNS resolution failed. Please check your network settings."
        ErrorCategory.IO -> "File system error: ${error.message}"
        ErrorCategory.PERMISSION -> "Permission denied: ${error.message}"
        ErrorCategory.RESOURCE -> "System resources exhausted. Try reducing context or closing other applications."
        ErrorCategory.VALIDATION -> "Invalid input: ${error.message}"
        ErrorCategory.STATE -> "Invalid state: ${error.message}"
        ErrorCategory.RATE_LIMIT -> "API rate limit reached. Waiting before retrying..."
        ErrorCategory.AUTH -> "Authentication failed. Please check your API key."
        ErrorCategory.CONTEXT_TOO_LONG -> "Context too long. Try using /compact to reduce context size."
        ErrorCategory.OVERLOADED -> "API server is overloaded. Retrying shortly..."
        ErrorCategory.UNKNOWN -> "Unexpected error: ${error.message}"
    }

    private fun getRecoveryActions(category: ErrorCategory): List<String> = when (category) {
        ErrorCategory.NETWORK_TIMEOUT, ErrorCategory.NETWORK_CONNECT, ErrorCategory.NETWORK_DNS ->
            listOf("Check internet connection", "Try again in a few seconds", "Check proxy settings")
        ErrorCategory.AUTH ->
            listOf("Set ANTHROPIC_API_KEY environment variable", "Run /doctor to check configuration")
        ErrorCategory.RATE_LIMIT ->
            listOf("Wait and retry automatically", "Reduce request frequency")
        ErrorCategory.CONTEXT_TOO_LONG ->
            listOf("Use /compact to summarize context", "Start a new conversation with /clear")
        ErrorCategory.OVERLOADED ->
            listOf("Retry automatically", "Try again in a few minutes")
        ErrorCategory.IO ->
            listOf("Check file permissions", "Ensure disk space is available")
        ErrorCategory.RESOURCE ->
            listOf("Use /compact to reduce memory usage", "Restart the application")
        else -> listOf("Try again", "Use /doctor to check system health")
    }

    private fun isRetryable(category: ErrorCategory): Boolean = when (category) {
        ErrorCategory.RATE_LIMIT, ErrorCategory.OVERLOADED,
        ErrorCategory.NETWORK_TIMEOUT, ErrorCategory.NETWORK_CONNECT -> true
        else -> false
    }

    private fun getStackTrace(error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        return sw.toString().take(2000)
    }
}

enum class ErrorCategory {
    NETWORK_TIMEOUT, NETWORK_CONNECT, NETWORK_DNS,
    IO, PERMISSION, RESOURCE,
    VALIDATION, STATE,
    RATE_LIMIT, AUTH, CONTEXT_TOO_LONG, OVERLOADED,
    UNKNOWN
}

data class ErrorContext(
    val operation: String = "unknown",
    val toolName: String? = null,
    val includeStackTrace: Boolean = false
)

data class ErrorResult(
    val category: ErrorCategory,
    val message: String,
    val technicalDetail: String = "",
    val stackTrace: String? = null,
    val recoveryActions: List<String> = emptyList(),
    val retryable: Boolean = false
)
