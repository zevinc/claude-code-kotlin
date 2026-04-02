/**
 * Tools module build configuration
 * 工具模块构建配置
 * 
 * Contains: Tool interface and 40+ tool implementations
 * 包含：Tool 接口和 40+ 工具实现
 */
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    // Internal dependencies / 内部依赖
    implementation(project(":core"))
    implementation(project(":services"))    
    // File operations / 文件操作
    implementation("com.github.ajalt.mordant:mordant:2.3.0")
    
    // Process execution / 进程执行
    implementation("org.zeroturnaround:zt-exec:1.12")
    
    // Glob pattern matching / Glob 模式匹配
    implementation("io.github.azagniotov:ant-style-path-matcher:1.0.0")
    
    // Diff utilities / Diff 工具
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")
}
