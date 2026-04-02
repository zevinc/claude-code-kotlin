/**
 * Root build configuration for Claude Code Kotlin port
 * Claude Code Kotlin 移植版的根构建配置
 */
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    // Kotlin JVM plugin / Kotlin JVM 插件
    kotlin("jvm") version "1.9.22" apply false
    // Kotlin serialization plugin / Kotlin 序列化插件
    kotlin("plugin.serialization") version "1.9.22" apply false
    // GraalVM Native Image plugin / GraalVM Native Image 插件
    id("org.graalvm.buildtools.native") version "0.9.28" apply false
}

// Common configuration for all subprojects
// 所有子项目的通用配置
subprojects {
    group = "com.anthropic"
    version = "0.1.0-SNAPSHOT"

    // Apply Kotlin JVM plugin to all subprojects
    // 对所有子项目应用 Kotlin JVM 插件
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    // Common dependencies / 通用依赖
    dependencies {
        val implementation by configurations
        val testImplementation by configurations
        
        // Kotlin standard library / Kotlin 标准库
        implementation(kotlin("stdlib"))
        
        // Coroutines / 协程
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        
        // JSON serialization / JSON 序列化
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
        
        // Logging / 日志
        implementation("io.github.oshai:kotlin-logging-jvm:5.1.0")
        implementation("ch.qos.logback:logback-classic:1.4.14")
        
        // Testing / 测试
        testImplementation(kotlin("test"))
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
        testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
        testImplementation("io.kotest:kotest-assertions-core:5.8.0")
        testImplementation("io.mockk:mockk:1.13.8")
    }

    // Kotlin compile options / Kotlin 编译选项
    tasks.withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = listOf(
                "-Xjsr305=strict",           // Strict null-safety / 严格空安全
                "-Xcontext-receivers",        // Context receivers / 上下文接收器
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
            )
        }
    }

    // Java compile options / Java 编译选项
    tasks.withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    // Test configuration / 测试配置
    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

// Task to show all dependencies
// 显示所有依赖的任务
tasks.register("allDeps") {
    description = "Shows dependencies for all subprojects"
    group = "help"
    dependsOn(subprojects.map { "${it.path}:dependencies" })
}
