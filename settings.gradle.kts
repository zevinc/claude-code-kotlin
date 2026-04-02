/**
 * Gradle settings for Claude Code Kotlin port
 * Claude Code Kotlin 移植版的 Gradle 设置
 */
rootProject.name = "claude-code-kt"

// Include all submodules
// 包含所有子模块
include(
    ":core",      // Core types, utilities, config / 核心类型、工具、配置
    ":engine",    // Query engine, streaming / 查询引擎、流式处理
    ":tools",     // Tool implementations / 工具实现
    ":commands",  // Command implementations / 命令实现
    ":services",  // API, MCP, OAuth services / API、MCP、OAuth 服务
    ":ui",        // Lanterna terminal UI / Lanterna 终端 UI
    ":app",       // Application entry point / 应用入口
    ":native"     // GraalVM Native Image config / GraalVM Native Image 配置
)

// Plugin management
// 插件管理
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Dependency resolution management
// 依赖解析管理
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        // For snapshot versions if needed
        // 如果需要快照版本
        maven("https://oss.sonatype.org/content/repositories/snapshots")
    }
}
