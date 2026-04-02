package com.anthropic.claudecode.services.doctor

import com.anthropic.claudecode.services.auth.AuthService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * DoctorService - performs environment health checks
 * DoctorService - 执行环境健康检查
 *
 * Maps from TypeScript commands/doctor.ts.
 * Checks: authentication, git, required tools, config files,
 * Node/Python runtimes, disk space, and network connectivity.
 * 映射自 TypeScript commands/doctor.ts。
 * 检查：认证、git、必需工具、配置文件、
 * Node/Python 运行时、磁盘空间和网络连接。
 */
class DoctorService(
    private val authService: AuthService = AuthService()
) {
    /**
     * Run all health checks / 运行所有健康检查
     */
    fun runAll(): DoctorReport {
        val checks = mutableListOf<HealthCheck>()

        checks.add(checkAuth())
        checks.add(checkGit())
        checks.add(checkConfigDir())
        checks.add(checkRipgrep())
        checks.add(checkNode())
        checks.add(checkPython())
        checks.add(checkDiskSpace())
        checks.add(checkNetwork())

        val passed = checks.count { it.status == CheckStatus.PASS }
        val warnings = checks.count { it.status == CheckStatus.WARN }
        val failed = checks.count { it.status == CheckStatus.FAIL }

        return DoctorReport(
            checks = checks,
            passed = passed,
            warnings = warnings,
            failed = failed,
            healthy = failed == 0
        )
    }

    private fun checkAuth(): HealthCheck {
        val status = authService.getAuthStatus()
        return if (status.isAuthenticated) {
            HealthCheck("Authentication", CheckStatus.PASS, "Authenticated via ${status.source}")
        } else {
            HealthCheck("Authentication", CheckStatus.FAIL,
                "Not authenticated. Set ANTHROPIC_API_KEY or run login.")
        }
    }

    private fun checkGit(): HealthCheck {
        return checkCommand("Git", "git", listOf("--version"))
    }

    private fun checkRipgrep(): HealthCheck {
        val result = checkCommand("Ripgrep", "rg", listOf("--version"))
        return if (result.status == CheckStatus.FAIL) {
            HealthCheck("Ripgrep", CheckStatus.WARN, "ripgrep not found. Grep will fall back to system grep.")
        } else result
    }

    private fun checkNode(): HealthCheck {
        return checkCommand("Node.js", "node", listOf("--version"))
    }

    private fun checkPython(): HealthCheck {
        val py3 = checkCommand("Python", "python3", listOf("--version"))
        if (py3.status == CheckStatus.PASS) return py3
        return checkCommand("Python", "python", listOf("--version"))
    }

    private fun checkConfigDir(): HealthCheck {
        val configDir = AuthService.defaultConfigDir()
        val dir = File(configDir)
        return if (dir.exists() && dir.isDirectory) {
            HealthCheck("Config directory", CheckStatus.PASS, configDir)
        } else {
            HealthCheck("Config directory", CheckStatus.WARN,
                "$configDir does not exist. Will be created on first use.")
        }
    }

    private fun checkDiskSpace(): HealthCheck {
        val cwd = File(System.getProperty("user.dir"))
        val freeGB = cwd.freeSpace / (1024.0 * 1024 * 1024)
        return if (freeGB > 1.0) {
            HealthCheck("Disk space", CheckStatus.PASS, "${"%.1f".format(freeGB)} GB free")
        } else {
            HealthCheck("Disk space", CheckStatus.WARN, "Low disk space: ${"%.2f".format(freeGB)} GB")
        }
    }

    private fun checkNetwork(): HealthCheck {
        return try {
            val process = ProcessBuilder("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                "--connect-timeout", "5", "https://api.anthropic.com")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val completed = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (completed && output.startsWith("2") || output.startsWith("4")) {
                HealthCheck("Network", CheckStatus.PASS, "API reachable (HTTP $output)")
            } else {
                HealthCheck("Network", CheckStatus.WARN, "API may be unreachable")
            }
        } catch (e: Exception) {
            HealthCheck("Network", CheckStatus.WARN, "Could not check: ${e.message}")
        }
    }

    private fun checkCommand(name: String, cmd: String, args: List<String>): HealthCheck {
        return try {
            val process = ProcessBuilder(listOf(cmd) + args)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val completed = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (completed && process.exitValue() == 0) {
                HealthCheck(name, CheckStatus.PASS, output.lines().firstOrNull() ?: "OK")
            } else {
                HealthCheck(name, CheckStatus.FAIL, "$cmd returned exit code ${process.exitValue()}")
            }
        } catch (e: Exception) {
            HealthCheck(name, CheckStatus.FAIL, "$cmd not found")
        }
    }
}

enum class CheckStatus { PASS, WARN, FAIL }

data class HealthCheck(
    val name: String,
    val status: CheckStatus,
    val detail: String = ""
)

data class DoctorReport(
    val checks: List<HealthCheck>,
    val passed: Int = 0,
    val warnings: Int = 0,
    val failed: Int = 0,
    val healthy: Boolean = true
) {
    fun toDisplayString(): String {
        val sb = StringBuilder()
        sb.appendLine("Doctor Report / 健康检查报告")
        sb.appendLine("=" .repeat(40))
        for (check in checks) {
            val icon = when (check.status) {
                CheckStatus.PASS -> "\u001b[32m[PASS]\u001b[0m"
                CheckStatus.WARN -> "\u001b[33m[WARN]\u001b[0m"
                CheckStatus.FAIL -> "\u001b[31m[FAIL]\u001b[0m"
            }
            sb.appendLine("$icon ${check.name}: ${check.detail}")
        }
        sb.appendLine()
        sb.appendLine("$passed passed, $warnings warnings, $failed failed")
        return sb.toString()
    }
}
