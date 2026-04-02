package com.anthropic.claudecode.services.session

import com.anthropic.claudecode.types.Message
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Extension to append a message to a session's persistent log
 * 扩展：将消息追加到会话的持久化日志
 */
fun SessionManager.appendMessage(sessionId: String, message: Message) {
    try {
        val persistence = SessionPersistence()
        persistence.appendMessage(sessionId, message)
    } catch (e: Exception) {
        logger.debug { "Failed to persist message to session $sessionId: ${e.message}" }
    }
}
