package com.anthropic.claudecode.services.session

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * SessionManager - manages full session lifecycle
 * SessionManager - 管理完整会话生命周期
 *
 * Maps from TypeScript services/session.ts.
 * Handles: session creation, persistence (JSON Lines),
 * resume from ID, listing recent sessions, and cleanup.
 * 映射自 TypeScript services/session.ts。
 * 处理：会话创建、持久化（JSON Lines）、
 * 从 ID 恢复、列出最近会话和清理。
 */
class SessionManager(
    private val sessionsDir: String = defaultSessionsDir()
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private var currentSession: SessionInfo? = null

    /**
     * Create a new session / 创建新会话
     */
    fun createSession(projectDir: String? = null): SessionInfo {
        val session = SessionInfo(
            id = UUID.randomUUID().toString(),
            startedAt = Instant.now().toString(),
            projectDir = projectDir ?: System.getProperty("user.dir"),
            status = SessionStatus.ACTIVE
        )

        currentSession = session

        // Ensure sessions directory exists / 确保会话目录存在
        File(sessionsDir).mkdirs()

        // Write session metadata / 写入会话元数据
        val metaFile = File(sessionsDir, "${session.id}.meta.json")
        metaFile.writeText(json.encodeToString(SessionInfo.serializer(), session))

        logger.info { "Created session: ${session.id}" }
        return session
    }

    /**
     * Get the current active session / 获取当前活跃会话
     */
    fun getCurrentSession(): SessionInfo? = currentSession

    /**
     * End the current session / 结束当前会话
     */
    fun endSession() {
        val session = currentSession ?: return
        val updated = session.copy(
            endedAt = Instant.now().toString(),
            status = SessionStatus.COMPLETED
        )

        // Update metadata / 更新元数据
        val metaFile = File(sessionsDir, "${session.id}.meta.json")
        metaFile.writeText(json.encodeToString(SessionInfo.serializer(), updated))

        currentSession = null
        logger.info { "Ended session: ${session.id}" }
    }

    /**
     * Resume a session by ID / 通过 ID 恢复会话
     */
    fun resumeSession(sessionId: String): SessionInfo? {
        val metaFile = File(sessionsDir, "$sessionId.meta.json")
        if (!metaFile.exists()) {
            logger.warn { "Session not found: $sessionId" }
            return null
        }

        return try {
            val session = json.decodeFromString(SessionInfo.serializer(), metaFile.readText())
            val resumed = session.copy(
                status = SessionStatus.ACTIVE,
                resumedAt = Instant.now().toString()
            )
            currentSession = resumed

            // Update metadata / 更新元数据
            metaFile.writeText(json.encodeToString(SessionInfo.serializer(), resumed))

            logger.info { "Resumed session: $sessionId" }
            resumed
        } catch (e: Exception) {
            logger.error(e) { "Failed to resume session: $sessionId" }
            null
        }
    }

    /**
     * Resume the most recent session / 恢复最近的会话
     */
    fun resumeLatest(): SessionInfo? {
        val sessions = listRecentSessions(limit = 1)
        return sessions.firstOrNull()?.let { resumeSession(it.id) }
    }

    /**
     * List recent sessions / 列出最近的会话
     */
    fun listRecentSessions(limit: Int = 10): List<SessionInfo> {
        val dir = File(sessionsDir)
        if (!dir.exists()) return emptyList()

        return dir.listFiles { f -> f.name.endsWith(".meta.json") }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString(SessionInfo.serializer(), file.readText())
                } catch (_: Exception) { null }
            }
            ?.sortedByDescending { it.startedAt }
            ?.take(limit)
            ?: emptyList()
    }

    /**
     * Append a message to the session log (JSONL format)
     * 向会话日志追加消息（JSONL 格式）
     */
    fun appendToLog(entry: String) {
        val session = currentSession ?: return
        val logFile = File(sessionsDir, "${session.id}.jsonl")
        logFile.appendText(entry + "\n")
    }

    /**
     * Read session log entries / 读取会话日志条目
     */
    fun readLog(sessionId: String): List<String> {
        val logFile = File(sessionsDir, "$sessionId.jsonl")
        if (!logFile.exists()) return emptyList()
        return logFile.readLines().filter { it.isNotBlank() }
    }

    /**
     * Delete a session / 删除会话
     */
    fun deleteSession(sessionId: String) {
        File(sessionsDir, "$sessionId.meta.json").delete()
        File(sessionsDir, "$sessionId.jsonl").delete()
        logger.info { "Deleted session: $sessionId" }
    }

    /**
     * Cleanup old sessions (older than daysToKeep)
     * 清理旧会话（早于 daysToKeep 天）
     */
    fun cleanup(daysToKeep: Int = 30) {
        val cutoff = Instant.now().minusSeconds(daysToKeep * 86400L)
        val sessions = listRecentSessions(limit = 1000)
        var deleted = 0

        for (session in sessions) {
            try {
                val started = Instant.parse(session.startedAt)
                if (started.isBefore(cutoff)) {
                    deleteSession(session.id)
                    deleted++
                }
            } catch (_: Exception) {}
        }

        if (deleted > 0) {
            logger.info { "Cleaned up $deleted old sessions" }
        }
    }

    companion object {
        fun defaultSessionsDir(): String {
            val configDir = System.getenv("CLAUDE_CONFIG_DIR")
                ?: "${System.getProperty("user.home")}/.claude"
            return "$configDir/sessions"
        }
    }
}

@Serializable
data class SessionInfo(
    val id: String,
    val startedAt: String,
    val endedAt: String? = null,
    val resumedAt: String? = null,
    val projectDir: String = "",
    val status: SessionStatus = SessionStatus.ACTIVE,
    val model: String? = null,
    val totalCostUsd: Double = 0.0,
    val messageCount: Int = 0
)

@Serializable
enum class SessionStatus {
    ACTIVE, COMPLETED, ERROR, INTERRUPTED
}
