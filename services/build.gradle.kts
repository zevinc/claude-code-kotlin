/**
 * Services module build configuration
 * 服务模块构建配置
 * 
 * Contains: API client, MCP, OAuth, Plugins
 * 包含：API 客户端、MCP、OAuth、插件
 */
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    // Internal dependencies / 内部依赖
    implementation(project(":core"))
    
    // HTTP client / HTTP 客户端
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    implementation("io.ktor:ktor-client-websockets:2.3.7")
    
    // OAuth support / OAuth 支持
    implementation("io.ktor:ktor-client-auth:2.3.7")
    
    // Secure storage / 安全存储
    // Using Java keystore for credentials
    // 使用 Java keystore 存储凭据
}
