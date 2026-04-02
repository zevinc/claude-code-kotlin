package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * TodoWriteTool - manages task lists with file persistence
 * TodoWriteTool - 管理具有文件持久化的任务列表
 *
 * Maps from TypeScript tools/TodoWriteTool.
 * Persists tasks to .claude/todos.json for session continuity.
 * 映射自 TypeScript tools/TodoWriteTool。
 * 将任务持久化到 .claude/todos.json 以保持会话连续性。
 */
class TodoWriteTool : Tool<TodoWriteInput, TodoWriteOutput> {
    override val name = "TodoWriteTool"
    override val aliases = listOf("TodoWrite")

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Create and manage a structured task list for tracking progress during coding sessions.",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(rawInput: JsonObject): TodoWriteInput {
        val todos = rawInput["todos"]?.jsonArray?.map { elem ->
            val obj = elem.jsonObject
            TodoItem(
                content = obj["content"]?.jsonPrimitive?.content ?: "",
                status = obj["status"]?.jsonPrimitive?.content ?: "pending",
                activeForm = obj["activeForm"]?.jsonPrimitive?.content ?: ""
            )
        } ?: emptyList()
        return TodoWriteInput(todos = todos)
    }

    override suspend fun call(
        input: TodoWriteInput, context: ToolUseContext, canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant, onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<TodoWriteOutput> {
        logger.info { "TodoWrite: ${input.todos.size} items" }

        // Persist to .claude/todos.json / 持久化到 .claude/todos.json
        val projectDir = System.getProperty("user.dir")
        val todoFile = File(projectDir, ".claude/todos.json")

        try {
            todoFile.parentFile?.mkdirs()
            val todosJson = buildJsonArray {
                for (todo in input.todos) {
                    add(buildJsonObject {
                        put("content", todo.content)
                        put("status", todo.status)
                        put("activeForm", todo.activeForm)
                    })
                }
            }
            todoFile.writeText(json.encodeToString(JsonArray.serializer(), todosJson))
        } catch (e: Exception) {
            logger.debug { "Failed to persist todos: ${e.message}" }
        }

        // Update app state / 更新应用状态
        try {
            context.setAppState { state ->
                state.copy(verbose = state.verbose) // triggers state update
            }
        } catch (_: Exception) { }

        val pending = input.todos.count { it.status == "pending" }
        val inProgress = input.todos.count { it.status == "in_progress" }
        val completed = input.todos.count { it.status == "completed" }

        val output = TodoWriteOutput(
            totalItems = input.todos.size,
            pending = pending,
            inProgress = inProgress,
            completed = completed,
            message = "Todos updated: $completed completed, $inProgress in progress, $pending pending"
        )
        return ToolResult(data = output, output = output)
    }

    override suspend fun description(input: TodoWriteInput, options: DescriptionOptions) = "Update task list"
    override suspend fun checkPermissions(input: TodoWriteInput, context: ToolUseContext) = PermissionResult.Allow()
    override fun isReadOnly(input: TodoWriteInput) = false
}

@Serializable data class TodoItem(
    val content: String, val status: String = "pending", val activeForm: String = ""
)
@Serializable data class TodoWriteInput(val todos: List<TodoItem> = emptyList())
@Serializable data class TodoWriteOutput(
    val totalItems: Int = 0, val pending: Int = 0,
    val inProgress: Int = 0, val completed: Int = 0,
    val message: String? = null
)
