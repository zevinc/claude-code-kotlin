package com.anthropic.claudecode.tools

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Tool registry - manages discovery and lookup of available tools
 * 工具注册表 - 管理可用工具的发现和查找
 *
 * Maps from TypeScript tools.ts tool factory and registry pattern.
 * 映射自 TypeScript tools.ts 工具工厂和注册表模式。
 */
class ToolRegistry {
    /**
     * Registered tools indexed by name
     * 按名称索引的已注册工具
     */
    private val toolsByName = mutableMapOf<String, Tool<*, *>>()

    /**
     * Alias-to-canonical-name mapping
     * 别名到规范名称的映射
     */
    private val aliasMap = mutableMapOf<String, String>()

    /**
     * All registered tools as an ordered list
     * 所有已注册工具的有序列表
     */
    val tools: List<Tool<*, *>>
        get() = toolsByName.values.toList()

    /**
     * Register a tool in the registry
     * 在注册表中注册一个工具
     *
     * @param tool The tool to register / 要注册的工具
     */
    fun register(tool: Tool<*, *>) {
        if (toolsByName.containsKey(tool.name)) {
            logger.warn { "Tool '${tool.name}' is already registered, overwriting" }
        }
        toolsByName[tool.name] = tool

        // Register aliases / 注册别名
        for (alias in tool.aliases) {
            aliasMap[alias] = tool.name
        }
        logger.debug { "Registered tool: ${tool.name} (aliases: ${tool.aliases})" }
    }

    /**
     * Register multiple tools at once
     * 一次注册多个工具
     *
     * @param tools Tools to register / 要注册的工具
     */
    fun registerAll(vararg tools: Tool<*, *>) {
        for (tool in tools) {
            register(tool)
        }
    }

    /**
     * Look up a tool by name or alias
     * 通过名称或别名查找工具
     *
     * @param name Tool name or alias / 工具名称或别名
     * @return The tool, or null if not found / 找到的工具，如果未找到则返回 null
     */
    fun get(name: String): Tool<*, *>? {
        // Try direct name lookup / 尝试直接名称查找
        toolsByName[name]?.let { return it }

        // Try alias lookup / 尝试别名查找
        aliasMap[name]?.let { canonicalName ->
            return toolsByName[canonicalName]
        }

        return null
    }

    /**
     * Check if a tool is registered
     * 检查工具是否已注册
     *
     * @param name Tool name or alias / 工具名称或别名
     */
    fun has(name: String): Boolean = get(name) != null

    /**
     * Get only enabled tools
     * 仅获取已启用的工具
     */
    fun getEnabledTools(): List<Tool<*, *>> =
        tools.filter { it.isEnabled() }

    /**
     * Get tool names (including aliases)
     * 获取工具名称（包括别名）
     */
    fun getAllNames(): Set<String> =
        toolsByName.keys + aliasMap.keys

    /**
     * Remove a tool from the registry
     * 从注册表中移除工具
     *
     * @param name Tool name / 工具名称
     */
    fun unregister(name: String) {
        val tool = toolsByName.remove(name) ?: return
        // Remove aliases / 移除别名
        tool.aliases.forEach { aliasMap.remove(it) }
        logger.debug { "Unregistered tool: $name" }
    }

    /**
     * Clear all registered tools
     * 清除所有已注册的工具
     */
    fun clear() {
        toolsByName.clear()
        aliasMap.clear()
    }

    /**
     * Number of registered tools
     * 已注册工具的数量
     */
    val size: Int
        get() = toolsByName.size
}
