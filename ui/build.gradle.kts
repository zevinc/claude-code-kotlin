/**
 * UI module build configuration
 * UI 模块构建配置
 * 
 * Contains: Lanterna terminal UI components
 * 包含：Lanterna 终端 UI 组件
 */
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    // Internal dependencies / 内部依赖
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation(project(":services"))
    implementation(project(":tools"))
    implementation(project(":commands"))
    
    // Terminal UI framework / 终端 UI 框架
    implementation("com.googlecode.lanterna:lanterna:3.1.2")
    
    // Terminal colors and formatting (auxiliary)
    // 终端颜色和格式化（辅助）
    implementation("com.github.ajalt.mordant:mordant:2.3.0")
}
