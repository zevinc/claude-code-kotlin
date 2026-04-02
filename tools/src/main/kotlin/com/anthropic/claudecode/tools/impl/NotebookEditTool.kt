package com.anthropic.claudecode.tools.impl

import com.anthropic.claudecode.tools.*
import com.anthropic.claudecode.types.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.*

private val logger = KotlinLogging.logger {}

/**
 * NotebookEdit tool - edits Jupyter notebook cells
 * NotebookEdit 工具 - 编辑 Jupyter 笔记本单元格
 *
 * Maps from TypeScript tools/NotebookEditTool/NotebookEditTool.ts.
 * Supports replace, insert, and delete operations on notebook cells.
 * 映射自 TypeScript tools/NotebookEditTool/NotebookEditTool.ts。
 * 支持对笔记本单元格的替换、插入和删除操作。
 */
class NotebookEditTool : Tool<NotebookEditInput, NotebookEditOutput> {
    override val name: String = "NotebookEditTool"

    override val inputJSONSchema = ToolInputJSONSchema(
        description = "Edit Jupyter notebook (.ipynb) cells. Supports replace, insert, and delete operations.",
        schema = JsonObject(emptyMap())
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override fun parseInput(jsonObj: JsonObject): NotebookEditInput {
        return NotebookEditInput(
            notebookPath = jsonObj["notebook_path"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing: notebook_path"),
            cellId = jsonObj["cell_id"]?.jsonPrimitive?.content,
            cellIndex = jsonObj["cell_index"]?.jsonPrimitive?.intOrNull,
            newSource = jsonObj["new_source"]?.jsonPrimitive?.content ?: "",
            cellType = jsonObj["cell_type"]?.jsonPrimitive?.content ?: "code",
            editMode = jsonObj["edit_mode"]?.jsonPrimitive?.content ?: "replace"
        )
    }

    override suspend fun call(
        input: NotebookEditInput,
        context: ToolUseContext,
        canUseTool: CanUseToolFn,
        parentMessage: Message.Assistant,
        onProgress: ((ToolProgressData) -> Unit)?
    ): ToolResult<NotebookEditOutput> {
        val path = Path(input.notebookPath)
        if (!path.exists()) {
            return ToolResult(
                data = NotebookEditOutput(error = "Notebook not found: ${input.notebookPath}"),
                output = NotebookEditOutput(error = "Notebook not found"),
                isError = true
            )
        }

        return try {
            val notebook = json.parseToJsonElement(path.readText()).jsonObject
            val cells = notebook["cells"]?.jsonArray?.toMutableList()
                ?: return ToolResult(
                    data = NotebookEditOutput(error = "Invalid notebook: no cells array"),
                    output = NotebookEditOutput(error = "Invalid notebook"),
                    isError = true
                )

            // Find target cell / 查找目标单元格
            val targetIndex = resolveTargetIndex(cells, input)
                ?: return ToolResult(
                    data = NotebookEditOutput(error = "Cell not found"),
                    output = NotebookEditOutput(error = "Cell not found"),
                    isError = true
                )

            // Apply edit / 应用编辑
            when (input.editMode) {
                "replace" -> {
                    if (targetIndex >= cells.size) {
                        return ToolResult(
                            data = NotebookEditOutput(error = "Cell index out of range"),
                            output = NotebookEditOutput(error = "Cell index out of range"),
                            isError = true
                        )
                    }
                    val existingCell = cells[targetIndex].jsonObject
                    val sourceLines = input.newSource.lines().map { line -> JsonPrimitive(line + "\n") }
                    val updatedCell = buildJsonObject {
                        for ((key, value) in existingCell) {
                            if (key == "source") {
                                put("source", JsonArray(sourceLines))
                            } else {
                                put(key, value)
                            }
                        }
                    }
                    cells[targetIndex] = updatedCell
                }
                "insert" -> {
                    val sourceLines = input.newSource.lines().map { line -> JsonPrimitive(line + "\n") }
                    val newCell = buildJsonObject {
                        put("cell_type", input.cellType)
                        put("source", JsonArray(sourceLines))
                        put("metadata", JsonObject(emptyMap()))
                        if (input.cellType == "code") {
                            put("outputs", JsonArray(emptyList()))
                            put("execution_count", JsonNull)
                        }
                    }
                    cells.add(targetIndex.coerceAtMost(cells.size), newCell)
                }
                "delete" -> {
                    if (targetIndex >= cells.size) {
                        return ToolResult(
                            data = NotebookEditOutput(error = "Cell index out of range"),
                            output = NotebookEditOutput(error = "Cell index out of range"),
                            isError = true
                        )
                    }
                    cells.removeAt(targetIndex)
                }
                else -> {
                    return ToolResult(
                        data = NotebookEditOutput(error = "Unknown edit_mode: ${input.editMode}"),
                        output = NotebookEditOutput(error = "Unknown edit_mode"),
                        isError = true
                    )
                }
            }

            // Write back / 写回
            val updatedNotebook = buildJsonObject {
                for ((key, value) in notebook) {
                    if (key == "cells") {
                        put("cells", JsonArray(cells))
                    } else {
                        put(key, value)
                    }
                }
            }
            path.writeText(json.encodeToString(JsonObject.serializer(), updatedNotebook))

            val result = NotebookEditOutput(
                success = true,
                editMode = input.editMode,
                cellIndex = targetIndex,
                totalCells = cells.size
            )
            ToolResult(data = result, output = result)
        } catch (e: Exception) {
            logger.error(e) { "NotebookEdit failed" }
            ToolResult(
                data = NotebookEditOutput(error = e.message),
                output = NotebookEditOutput(error = e.message),
                isError = true
            )
        }
    }

    override suspend fun description(input: NotebookEditInput, options: DescriptionOptions): String {
        return "${input.editMode.replaceFirstChar { ch -> ch.uppercase() }} cell in ${input.notebookPath}"
    }

    private fun resolveTargetIndex(cells: List<JsonElement>, input: NotebookEditInput): Int? {
        // By explicit index / 按显式索引
        if (input.cellIndex != null) return input.cellIndex

        // By cell ID / 按单元格 ID
        if (input.cellId != null) {
            return cells.indexOfFirst { cell ->
                cell.jsonObject["id"]?.jsonPrimitive?.content == input.cellId
            }.takeIf { it >= 0 }
        }

        // Default to end for insert, 0 for others / 插入默认末尾，其他默认 0
        return if (input.editMode == "insert") cells.size else 0
    }

    override suspend fun checkPermissions(input: NotebookEditInput, context: ToolUseContext): PermissionResult {
        return PermissionResult.Allow()
    }

    override suspend fun validateInput(input: NotebookEditInput, context: ToolUseContext): ValidationResult {
        if (!input.notebookPath.endsWith(".ipynb")) {
            return ValidationResult.Failure("File must be a .ipynb notebook", errorCode = 400)
        }
        if (input.editMode !in listOf("replace", "insert", "delete")) {
            return ValidationResult.Failure("edit_mode must be: replace, insert, or delete", errorCode = 400)
        }
        return ValidationResult.Success
    }

    override fun getActivityDescription(input: NotebookEditInput): String {
        return "Editing notebook ${input.notebookPath}"
    }
}

@Serializable data class NotebookEditInput(
    val notebookPath: String, val cellId: String? = null, val cellIndex: Int? = null,
    val newSource: String = "", val cellType: String = "code", val editMode: String = "replace"
)
@Serializable data class NotebookEditOutput(
    val success: Boolean = false, val editMode: String? = null,
    val cellIndex: Int? = null, val totalCells: Int = 0, val error: String? = null
)
