/**
 * Engine module build configuration
 * 引擎模块构建配置
 * 
 * Contains: QueryEngine, StreamHandler, ContextManager
 * 包含：查询引擎、流式处理、上下文管理
 */
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    // Internal dependencies / 内部依赖
    implementation(project(":core"))
    implementation(project(":tools"))
    implementation(project(":services"))
    
    // HTTP client for API calls / API 调用的 HTTP 客户端
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    
    // Server-sent events support (manual implementation over Ktor)
    // SSE 支持（基于 Ktor 的手动实现）
}
