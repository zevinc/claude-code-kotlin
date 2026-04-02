package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * In-memory task store shared across task tools
 * 任务工具间共享的内存任务存储
 */
object TaskStore {
    private val tasks = ConcurrentHashMap<String, TaskItem>()
    private val idCounter = AtomicInteger(1)

    fun create(item: TaskItem): TaskItem {
        val id = "task-${idCounter.getAndIncrement()}"
        val task = item.copy(id = id)
        tasks[id] = task
        return task
    }

    fun update(id: String, updater: (TaskItem) -> TaskItem): TaskItem? {
        val existing = tasks[id] ?: return null
        val updated = updater(existing)
        tasks[id] = updated
        return updated
    }

    fun get(id: String): TaskItem? = tasks[id]
    fun getAll(): List<TaskItem> = tasks.values.toList()
    fun delete(id: String): Boolean = tasks.remove(id) != null
}

@Serializable
data class TaskItem(
    val id: String = "",
    val content: String,
    val status: String = "pending",
    val activeForm: String = "",
    val owner: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

// ==================== TaskCreateTool ====================

/**
 * TaskCreate tool - creates a new task
 * TaskCreate 工具 - 创建新任务
 */
class TaskCreateTool : Tool<TaskCreateInput, TaskCreateOutput> {
    override val name = "TaskCreateTool"
    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Create a new task for tracking work items. / 创建新任务用于跟踪工作项。",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(json: JsonObject): TaskCreateInput {
        return TaskCreateInput(
            content = json["content"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing: content"),
            activeForm = json["activeForm"]?.jsonPrimitive?.content ?: "",
            status = json["status"]?.jsonPrimitive?.content ?: "pending"
        )
    }

    override suspend fun call(
        input: TaskCreateInput, context: ToolUseContext, canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant, onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<TaskCreateOutput> {
        val task = TaskStore.create(TaskItem(
            content = input.content, activeForm = input.activeForm, status = input.status
        ))
        val output = TaskCreateOutput(taskId = task.id, content = task.content, status = task.status)
        return ToolResult(data = output, output = output)
    }

    override suspend fun description(input: TaskCreateInput, options: DescriptionOptions): String {
        return "Create task: ${input.content.take(40)}"
    }

    override suspend fun checkPermissions(input: TaskCreateInput, context: ToolUseContext) = PermissionResult.Allow()

    override suspend fun validateInput(input: TaskCreateInput, context: ToolUseContext): ValidationResult {
        if (input.content.isBlank()) return ValidationResult.Failure("Content cannot be empty", errorCode = 400)
        return ValidationResult.Success
    }
}

@Serializable data class TaskCreateInput(val content: String, val activeForm: String = "", val status: String = "pending")
@Serializable data class TaskCreateOutput(val taskId: String, val content: String, val status: String)

// ==================== TaskUpdateTool ====================

/**
 * TaskUpdate tool - updates an existing task
 * TaskUpdate 工具 - 更新现有任务
 */
class TaskUpdateTool : Tool<TaskUpdateInput, TaskUpdateOutput> {
    override val name = "TaskUpdateTool"
    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Update an existing task's status or content. / 更新现有任务的状态或内容。",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(json: JsonObject): TaskUpdateInput {
        return TaskUpdateInput(
            taskId = json["task_id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing: task_id"),
            status = json["status"]?.jsonPrimitive?.content,
            content = json["content"]?.jsonPrimitive?.content,
            activeForm = json["activeForm"]?.jsonPrimitive?.content
        )
    }

    override suspend fun call(
        input: TaskUpdateInput, context: ToolUseContext, canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant, onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<TaskUpdateOutput> {
        val updated = TaskStore.update(input.taskId) { task ->
            task.copy(
                status = input.status ?: task.status,
                content = input.content ?: task.content,
                activeForm = input.activeForm ?: task.activeForm
            )
        }
        if (updated == null) {
            return ToolResult(
                data = TaskUpdateOutput(error = "Task not found: ${input.taskId}"),
                output = TaskUpdateOutput(error = "Task not found"),
                isError = true
            )
        }
        val output = TaskUpdateOutput(taskId = updated.id, status = updated.status, success = true)
        return ToolResult(data = output, output = output)
    }

    override suspend fun description(input: TaskUpdateInput, options: DescriptionOptions): String {
        return "Update task ${input.taskId}"
    }

    override suspend fun checkPermissions(input: TaskUpdateInput, context: ToolUseContext) = PermissionResult.Allow()

    override suspend fun validateInput(input: TaskUpdateInput, context: ToolUseContext): ValidationResult {
        if (input.taskId.isBlank()) return ValidationResult.Failure("task_id cannot be empty", errorCode = 400)
        return ValidationResult.Success
    }
}

@Serializable data class TaskUpdateInput(
    val taskId: String, val status: String? = null,
    val content: String? = null, val activeForm: String? = null
)
@Serializable data class TaskUpdateOutput(
    val taskId: String? = null, val status: String? = null,
    val success: Boolean = false, val error: String? = null
)

// ==================== TaskListTool ====================

/**
 * TaskList tool - lists all tasks
 * TaskList 工具 - 列出所有任务
 */
class TaskListTool : Tool<TaskListInput, TaskListOutput> {
    override val name = "TaskListTool"
    override val inputJSONSchema = ToolInputJSONSchema(
        description = "List all tasks, optionally filtered by status. / 列出所有任务，可按状态过滤。",
        schema = JsonObject(emptyMap())
    )

    override fun parseInput(json: JsonObject): TaskListInput {
        return TaskListInput(
            statusFilter = json["status"]?.jsonPrimitive?.content
        )
    }

    override suspend fun call(
        input: TaskListInput, context: ToolUseContext, canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant, onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<TaskListOutput> {
        var tasks = TaskStore.getAll()
        if (input.statusFilter != null) {
            tasks = tasks.filter { it.status == input.statusFilter }
        }
        val output = TaskListOutput(tasks = tasks, totalCount = tasks.size)
        return ToolResult(data = output, output = output)
    }

    override suspend fun description(input: TaskListInput, options: DescriptionOptions): String {
        return "List tasks${input.statusFilter?.let { " (status=$it)" } ?: ""}"
    }

    override suspend fun checkPermissions(input: TaskListInput, context: ToolUseContext) = PermissionResult.Allow()
    override suspend fun validateInput(input: TaskListInput, context: ToolUseContext) = ValidationResult.Success

    override fun isReadOnly(input: TaskListInput): Boolean = true
    override fun isConcurrencySafe(input: TaskListInput): Boolean = true
}

@Serializable data class TaskListInput(val statusFilter: String? = null)
@Serializable data class TaskListOutput(val tasks: List<TaskItem> = emptyList(), val totalCount: Int = 0)
