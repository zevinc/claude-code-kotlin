/**
 * Commands module build configuration
 * 命令模块构建配置
 * 
 * Contains: Command interface and 100+ command implementations
 * 包含：Command 接口和 100+ 命令实现
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
}
