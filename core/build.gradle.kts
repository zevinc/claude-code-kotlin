/**
 * Core module build configuration
 * 核心模块构建配置
 * 
 * Contains: types, config, utilities
 * 包含：类型、配置、工具函数
 */
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    // Validation library / 验证库
    implementation("io.konform:konform:0.4.0")
    
    // Functional programming utilities / 函数式编程工具
    implementation("io.arrow-kt:arrow-core:1.2.1")
    
    // Date/time handling / 日期时间处理
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
    
    // UUID generation / UUID 生成
    implementation("com.benasher44:uuid:0.8.2")
}
