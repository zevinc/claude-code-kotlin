package com.anthropic.claudecode.services.session

import com.anthropic.claudecode.types.Message
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.*

private val logger = KotlinLogging.logger {}

/**
 * Session persistence - saves and restores conversation sessions
 * 会话持久化 - 保存和恢复对话会话
 *
 * Maps from TypeScript assistant/sessionHistory.ts and state persistence.
 * Stores sessions as JSONL files in ~/.claude/sessions/.
 * 映射自 TypeScript assistant/sessionHistory.ts 和状态持久化。
 * 将会话存储为 ~/.claude/sessions/ 中的 JSONL 文件。
 */
class SessionPersistence(
    private val sessionsDir: Path = Path(System.getProperty("user.home"), ".claude", "sessions")
) {
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        sessionsDir.createDirectories()
    }

    /**
     * Save a session to disk
     * 将会话保存到磁盘
     *
     * @param sessionId Session identifier / 会话标识符
     * @param messages Messages to save / 要保存的消息
     * @param metadata Optional metadata / 可选的元数据
     */
    fun saveSession(
        sessionId: String,
        messages: List<Message>,
        metadata: SessionMetadata = SessionMetadata()
    ) {
        try {
            val sessionFile = sessionsDir.resolve("$sessionId.jsonl")
            val metadataFile = sessionsDir.resolve("$sessionId.meta.json")

            // Write messages as JSONL / 将消息写入 JSONL
            sessionFile.writeText(
                messages.joinToString("\n") { msg ->
                    json.encodeToString(msg)
                }
            )

            // Write metadata / 写入元数据
            metadataFile.writeText(json.encodeToString(metadata.copy(
                updatedAt = Instant.now().toString(),
                messageCount = messages.size
            )))

            logger.info { "Session $sessionId saved (${messages.size} messages)" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save session $sessionId" }
        }
    }

    /**
     * Load a session from disk
     * 从磁盘加载会话
     *
     * @param sessionId Session identifier / 会话标识符
     * @return Pair of messages and metadata, or null if not found / 消息和元数据的 Pair，未找到时为 null
     */
    fun loadSession(sessionId: String): SessionData? {
        try {
            val sessionFile = sessionsDir.resolve("$sessionId.jsonl")
            val metadataFile = sessionsDir.resolve("$sessionId.meta.json")

            if (!sessionFile.exists()) return null

            val messages = sessionFile.readLines()
                .filter { it.isNotBlank() }
                .map { line -> json.decodeFromString<Message>(line) }

            val metadata = if (metadataFile.exists()) {
                try {
                    json.decodeFromString<SessionMetadata>(metadataFile.readText())
                } catch (e: Exception) {
                    SessionMetadata()
                }
            } else {
                SessionMetadata()
            }

            logger.info { "Session $sessionId loaded (${messages.size} messages)" }
            return SessionData(messages = messages, metadata = metadata)
        } catch (e: Exception) {
            logger.error(e) { "Failed to load session $sessionId" }
            return null
        }
    }

    /**
     * List all saved sessions
     * 列出所有已保存的会话
     */
    fun listSessions(): List<SessionSummary> {
        return try {
            sessionsDir.listDirectoryEntries("*.meta.json")
                .mapNotNull { metaFile ->
                    try {
                        val meta = json.decodeFromString<SessionMetadata>(metaFile.readText())
                        val sessionId = metaFile.fileName.toString().removeSuffix(".meta.json")
                        SessionSummary(
                            sessionId = sessionId,
                            cwd = meta.cwd,
                            model = meta.model,
                            messageCount = meta.messageCount,
                            createdAt = meta.createdAt,
                            updatedAt = meta.updatedAt
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                .sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            logger.error(e) { "Failed to list sessions" }
            emptyList()
        }
    }

    /**
     * Get the most recent session ID
     * 获取最近的会话 ID
     */
    fun getMostRecentSessionId(): String? {
        return listSessions().firstOrNull()?.sessionId
    }

    /**
     * Delete a session
     * 删除会话
     */
    fun deleteSession(sessionId: String) {
        try {
            sessionsDir.resolve("$sessionId.jsonl").deleteIfExists()
            sessionsDir.resolve("$sessionId.meta.json").deleteIfExists()
            logger.info { "Session $sessionId deleted" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete session $sessionId" }
        }
    }

    /**
     * Append a single message to an existing session
     * 向现有会话追加单条消息
     */
    fun appendMessage(sessionId: String, message: Message) {
        try {
            val sessionFile = sessionsDir.resolve("$sessionId.jsonl")
            sessionFile.appendText("\n" + json.encodeToString(message))
        } catch (e: Exception) {
            logger.error(e) { "Failed to append message to session $sessionId" }
        }
    }
}

/**
 * Session metadata / 会话元数据
 */
@kotlinx.serialization.Serializable
data class SessionMetadata(
    val cwd: String = System.getProperty("user.dir"),
    val model: String = "claude-sonnet-4-20250514",
    val messageCount: Int = 0,
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = Instant.now().toString(),
    val gitBranch: String? = null,
    val tags: List<String> = emptyList()
)

/**
 * Session data bundle / 会话数据包
 */
data class SessionData(
    val messages: List<Message>,
    val metadata: SessionMetadata
)

/**
 * Session summary for listing / 用于列表的会话摘要
 */
data class SessionSummary(
    val sessionId: String,
    val cwd: String,
    val model: String,
    val messageCount: Int,
    val createdAt: String,
    val updatedAt: String
)
