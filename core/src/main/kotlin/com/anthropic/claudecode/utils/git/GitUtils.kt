package com.anthropic.claudecode.utils.git

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Git utilities - provides git-related information for context
 * Git 工具函数 - 提供用于上下文的 git 相关信息
 *
 * Maps from TypeScript utils/git/ module.
 * Uses ProcessBuilder to invoke git commands.
 * 映射自 TypeScript utils/git/ 模块。
 * 使用 ProcessBuilder 调用 git 命令。
 */
object GitUtils {

    /**
     * Check if a directory is inside a git repository
     * 检查目录是否在 git 仓库内
     */
    fun isGitRepo(dir: Path = Path.of(System.getProperty("user.dir"))): Boolean {
        return runGitCommand(dir, "rev-parse", "--is-inside-work-tree")?.trim() == "true"
    }

    /**
     * Get the current git branch name
     * 获取当前 git 分支名称
     */
    fun getCurrentBranch(dir: Path = Path.of(System.getProperty("user.dir"))): String? {
        return runGitCommand(dir, "branch", "--show-current")?.trim()?.ifEmpty { null }
    }

    /**
     * Get the git repository root directory
     * 获取 git 仓库根目录
     */
    fun getRepoRoot(dir: Path = Path.of(System.getProperty("user.dir"))): String? {
        return runGitCommand(dir, "rev-parse", "--show-toplevel")?.trim()
    }

    /**
     * Get a compact git status summary
     * 获取紧凑的 git 状态摘要
     *
     * @return GitStatusSummary with counts / 包含计数的 GitStatusSummary
     */
    fun getStatusSummary(dir: Path = Path.of(System.getProperty("user.dir"))): GitStatusSummary {
        val output = runGitCommand(dir, "status", "--porcelain", "-b") ?: return GitStatusSummary()
        val lines = output.lines().filter { it.isNotBlank() }

        var branch = ""
        var aheadBehind = ""
        var staged = 0
        var modified = 0
        var untracked = 0
        var deleted = 0
        var conflicted = 0

        for (line in lines) {
            if (line.startsWith("##")) {
                // Parse branch and ahead/behind info
                // 解析分支和领先/落后信息
                val branchLine = line.removePrefix("## ")
                val parts = branchLine.split("...")
                branch = parts[0]
                if (parts.size > 1) {
                    aheadBehind = parts[1]
                }
                continue
            }

            val x = line.getOrNull(0) ?: ' '
            val y = line.getOrNull(1) ?: ' '

            when {
                x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D') -> conflicted++
                x == '?' -> untracked++
                x != ' ' && x != '?' -> staged++
            }
            when {
                y == 'M' -> modified++
                y == 'D' -> deleted++
            }
        }

        return GitStatusSummary(
            branch = branch,
            aheadBehind = aheadBehind.trim(),
            staged = staged,
            modified = modified,
            untracked = untracked,
            deleted = deleted,
            conflicted = conflicted
        )
    }

    /**
     * Get the diff of staged changes
     * 获取暂存更改的 diff
     */
    fun getStagedDiff(dir: Path = Path.of(System.getProperty("user.dir"))): String? {
        return runGitCommand(dir, "diff", "--cached", "--stat")
    }

    /**
     * Get the diff of unstaged changes
     * 获取未暂存更改的 diff
     */
    fun getUnstagedDiff(dir: Path = Path.of(System.getProperty("user.dir"))): String? {
        return runGitCommand(dir, "diff", "--stat")
    }

    /**
     * Get recent commit log
     * 获取最近的提交日志
     */
    fun getRecentLog(dir: Path = Path.of(System.getProperty("user.dir")), count: Int = 5): String? {
        return runGitCommand(dir, "log", "--oneline", "-n", count.toString())
    }

    /**
     * Get the current HEAD commit hash (short)
     * 获取当前 HEAD 提交哈希值（短）
     */
    fun getHeadCommit(dir: Path = Path.of(System.getProperty("user.dir"))): String? {
        return runGitCommand(dir, "rev-parse", "--short", "HEAD")?.trim()
    }

    /**
     * Get list of changed files (staged + unstaged)
     * 获取变更文件列表（暂存 + 未暂存）
     */
    fun getChangedFiles(dir: Path = Path.of(System.getProperty("user.dir"))): List<String> {
        val output = runGitCommand(dir, "status", "--porcelain") ?: return emptyList()
        return output.lines()
            .filter { it.isNotBlank() && !it.startsWith("##") }
            .map { it.substring(3).trim() }
    }

    /**
     * Read .gitignore patterns from repository root
     * 从仓库根目录读取 .gitignore 模式
     */
    fun readGitignore(dir: Path = Path.of(System.getProperty("user.dir"))): List<String> {
        val repoRoot = getRepoRoot(dir) ?: return emptyList()
        val gitignore = File(repoRoot, ".gitignore")
        if (!gitignore.exists()) return emptyList()

        return gitignore.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.trim() }
    }

    /**
     * Get default branch name (main or master)
     * 获取默认分支名称（main 或 master）
     */
    fun getDefaultBranch(dir: Path = Path.of(System.getProperty("user.dir"))): String {
        // Try remote HEAD first / 先尝试远程 HEAD
        val remoteHead = runGitCommand(dir, "symbolic-ref", "refs/remotes/origin/HEAD")
            ?.trim()?.substringAfterLast("/")
        if (!remoteHead.isNullOrBlank()) return remoteHead

        // Fallback: check if main or master exists
        // 回退：检查 main 或 master 是否存在
        val branches = runGitCommand(dir, "branch", "--list")?.lines()?.map { it.trim().removePrefix("* ") }
            ?: return "main"

        return when {
            "main" in branches -> "main"
            "master" in branches -> "master"
            else -> branches.firstOrNull() ?: "main"
        }
    }

    /**
     * Run a git command and return stdout
     * 运行 git 命令并返回标准输出
     */
    private fun runGitCommand(dir: Path, vararg args: String): String? {
        return try {
            val command = listOf("git") + args.toList()
            val process = ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(false)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) output else null
        } catch (e: Exception) {
            logger.debug { "Git command failed: git ${args.joinToString(" ")}: ${e.message}" }
            null
        }
    }
}

/**
 * Git status summary
 * Git 状态摘要
 */
data class GitStatusSummary(
    /** Current branch / 当前分支 */
    val branch: String = "",
    /** Ahead/behind info / 领先/落后信息 */
    val aheadBehind: String = "",
    /** Staged file count / 暂存文件数 */
    val staged: Int = 0,
    /** Modified file count / 修改文件数 */
    val modified: Int = 0,
    /** Untracked file count / 未跟踪文件数 */
    val untracked: Int = 0,
    /** Deleted file count / 删除文件数 */
    val deleted: Int = 0,
    /** Conflicted file count / 冲突文件数 */
    val conflicted: Int = 0
) {
    /** Whether the working tree is clean / 工作树是否干净 */
    val isClean: Boolean get() = staged == 0 && modified == 0 && untracked == 0 && deleted == 0 && conflicted == 0

    /** Human-readable summary / 人类可读的摘要 */
    fun toSummary(): String {
        if (isClean) return "clean"
        val parts = mutableListOf<String>()
        if (staged > 0) parts.add("$staged staged")
        if (modified > 0) parts.add("$modified modified")
        if (untracked > 0) parts.add("$untracked untracked")
        if (deleted > 0) parts.add("$deleted deleted")
        if (conflicted > 0) parts.add("$conflicted conflicted")
        return parts.joinToString(", ")
    }
}
