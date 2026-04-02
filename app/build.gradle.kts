/**
 * App module build configuration
 * 应用模块构建配置
 * 
 * Contains: Main entry point with Clikt CLI
 * 包含：Clikt CLI 主入口
 */
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("org.graalvm.buildtools.native")
}

dependencies {
    // Internal dependencies / 内部依赖
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation(project(":tools"))
    implementation(project(":commands"))
    implementation(project(":services"))
    implementation(project(":ui"))
    
    // CLI framework / CLI 框架
    implementation("com.github.ajalt.clikt:clikt:4.2.2")
}

application {
    mainClass.set("com.anthropic.claudecode.MainKt")
    applicationName = "claude-code"
}

// GraalVM Native Image configuration
// GraalVM 原生镜像配置
graalvmNative {
    binaries {
        named("main") {
            // Executable name / 可执行文件名
            imageName.set("claude-code")
            
            // Main class / 主类
            mainClass.set("com.anthropic.claudecode.MainKt")
            
            // Build options / 构建选项
            buildArgs.add("--no-fallback")
            buildArgs.add("--enable-preview")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            
            // Resource bundles / 资源包
            buildArgs.add("-H:IncludeResources=.*\\.properties")
            buildArgs.add("-H:IncludeResources=logback\\.xml")
            
            // Optimization level / 优化级别
            buildArgs.add("-O2")
        }
    }
    
    // Use GraalVM metadata repository
    // 使用 GraalVM 元数据仓库
    metadataRepository {
        enabled.set(true)
    }
}

// Distribution configuration / 分发配置
distributions {
    main {
        distributionBaseName.set("claude-code")
    }
}
