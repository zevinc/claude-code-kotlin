package com.anthropic.claudecode.native

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * NativeSupport - GraalVM native image support utilities
 * NativeSupport - GraalVM 原生镜像支持工具
 *
 * Maps from TypeScript native/nativeSupport.ts.
 * Detects native image execution, provides platform-specific
 * optimizations and reflection registration.
 * 映射自 TypeScript native/nativeSupport.ts。
 * 检测原生镜像执行，提供平台特定的优化和反射注册。
 */
object NativeSupport {
    /** Whether running as GraalVM native image / 是否作为 GraalVM 原生镜像运行 */
    val isNativeImage: Boolean by lazy {
        System.getProperty("org.graalvm.nativeimage.imagecode") != null
    }

    /** Build information / 构建信息 */
    fun getBuildInfo(): BuildInfo {
        return BuildInfo(
            version = System.getProperty("claude.code.version", "0.1.0"),
            buildTime = System.getProperty("claude.code.buildTime", "unknown"),
            gitCommit = System.getProperty("claude.code.gitCommit", "unknown"),
            isNative = isNativeImage,
            jvmVersion = if (isNativeImage) "native" else System.getProperty("java.version", "unknown"),
            platform = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}"
        )
    }

    /** Get optimized temp directory for native images / 获取原生镜像优化的临时目录 */
    fun getTempDir(): String {
        return if (isNativeImage) {
            // Native images should use a predictable temp dir
            // 原生镜像应使用可预测的临时目录
            val dir = java.io.File(System.getProperty("java.io.tmpdir"), "claude-code-native")
            dir.mkdirs()
            dir.absolutePath
        } else {
            System.getProperty("java.io.tmpdir")
        }
    }

    /** Configure native image settings at startup / 在启动时配置原生镜像设置 */
    fun configure() {
        if (!isNativeImage) return

        logger.info { "Running as native image, applying optimizations" }

        // Set system properties for native image compatibility
        // 为原生镜像兼容性设置系统属性
        System.setProperty("kotlinx.coroutines.scheduler.core.pool.size", "2")
        System.setProperty("kotlinx.coroutines.scheduler.max.pool.size", "4")
    }

    /**
     * Get classes that need reflection registration for native image
     * 获取原生镜像需要反射注册的类
     */
    fun getReflectionClasses(): List<String> = listOf(
        "com.anthropic.claudecode.types.Message",
        "com.anthropic.claudecode.types.Message\$User",
        "com.anthropic.claudecode.types.Message\$Assistant",
        "com.anthropic.claudecode.types.Message\$System",
        "com.anthropic.claudecode.types.ContentBlock",
        "com.anthropic.claudecode.types.ContentBlock\$Text",
        "com.anthropic.claudecode.types.ContentBlock\$Image",
        "com.anthropic.claudecode.types.ContentBlock\$ToolUse",
        "com.anthropic.claudecode.types.ContentBlock\$ToolResult",
        "com.anthropic.claudecode.services.api.ApiMessage",
        "com.anthropic.claudecode.services.api.MessageRequest",
        "com.anthropic.claudecode.services.api.ApiTool",
        "com.anthropic.claudecode.utils.config.AppSettings",
        "com.anthropic.claudecode.utils.config.ClaudeMdConfig"
    )

    /**
     * Get resource patterns for native image
     * 获取原生镜像的资源模式
     */
    fun getResourcePatterns(): List<String> = listOf(
        "logback.xml",
        "version.properties",
        "META-INF/native-image/**"
    )
}

data class BuildInfo(
    val version: String,
    val buildTime: String,
    val gitCommit: String,
    val isNative: Boolean,
    val jvmVersion: String,
    val platform: String
) {
    fun toDisplayString(): String = buildString {
        appendLine("Claude Code Kotlin v$version")
        appendLine("  Build: $buildTime")
        appendLine("  Commit: $gitCommit")
        appendLine("  Runtime: ${if (isNative) "Native" else "JVM $jvmVersion"}")
        appendLine("  Platform: $platform")
    }
}
