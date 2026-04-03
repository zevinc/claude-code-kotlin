# Claude Code Kotlin

Claude Code 的 Kotlin 移植版 - 终端中的 AI 编程助手。

## 项目简介

本项目是 [Claude Code](https://github.com/anthropics/claude-code) 的 Kotlin/JVM 移植版本，提供完整的 AI 编程助手功能，支持交互式 REPL 和全屏 TUI 模式。

## 功能特性

- 🤖 **多模型支持** - 支持 Claude 系列模型，默认使用 claude-sonnet-4
- 💻 **双模式运行** - 交互式 REPL 和全屏 TUI 模式
- 🛠️ **丰富的工具集** - 文件操作、代码搜索、Bash 命令、Web 搜索等
- 🔌 **MCP 协议** - 支持 Model Context Protocol 扩展
- 📝 **会话管理** - 支持会话保存和恢复
- 🔗 **Hook 系统** - 可扩展的钩子机制
- 🚀 **Native Image** - 支持 GraalVM Native Image 编译

## 项目结构

```
claude-code-kotlin/
├── app/          # 应用入口点
├── core/         # 核心类型、工具、配置
├── engine/       # 查询引擎、流式处理
├── tools/        # 工具实现
├── commands/     # 命令实现
├── services/     # API、MCP、OAuth 服务
├── ui/           # Lanterna 终端 UI
└── native/       # GraalVM Native Image 配置
```

## 技术栈

- **语言**: Kotlin 1.9.22
- **JDK**: Java 17
- **构建工具**: Gradle 8.14
- **CLI 框架**: Clikt
- **终端 UI**: Lanterna
- **异步**: Kotlin Coroutines
- **序列化**: kotlinx-serialization
- **日志**: kotlin-logging + Logback
- **测试**: Kotest + MockK
- **Native Image**: GraalVM

## 快速开始

### 环境要求

- JDK 17+
- Gradle 8.x（项目包含 Gradle Wrapper）

### 构建项目

```bash
# 克隆项目
git clone https://github.com/anthropics/claude-code-kotlin.git
cd claude-code-kotlin

# 构建所有模块
./gradlew build

# 运行测试
./gradlew test
```

### 运行应用

```bash
# 交互模式
./gradlew :app:run

# 打印模式（非交互式）
./gradlew :app:run --args="--print '你的问题'"

# 指定模型
./gradlew :app:run --args="--model claude-opus-4"

# 启用调试模式
./gradlew :app:run --args="--debug --verbose"
```

### 子命令

```bash
# 系统诊断
./gradlew :app:run --args="doctor"

# 初始化配置
./gradlew :app:run --args="init"

# 配置管理
./gradlew :app:run --args="config"
```

## 命令行选项

| 选项 | 说明 |
|------|------|
| `-m, --model <name>` | 指定使用的模型 |
| `-v, --verbose` | 启用详细输出 |
| `--debug` | 启用调试模式 |
| `-p, --print` | 打印模式（非交互式） |
| `-r, --resume` | 恢复最近的会话 |
| `--session-id <id>` | 恢复指定会话 |
| `--no-color` | 禁用彩色输出 |
| `--tui` | 全屏 TUI 模式 |
| `--max-turns <n>` | 最大对话轮数 |

## 内置工具

| 工具 | 说明 |
|------|------|
| FileReadTool | 读取文件内容 |
| FileEditTool | 编辑文件 |
| FileWriteTool | 写入文件 |
| DeleteFileTool | 删除文件 |
| GlobTool | 文件模式匹配搜索 |
| GrepTool | 正则表达式搜索 |
| ListFilesTool | 列出目录内容 |
| BashTool | 执行 Shell 命令 |
| REPLTool | REPL 交互 |
| WebFetchTool | 获取网页内容 |
| WebSearchTool | Web 搜索 |
| AgentTool | Agent 执行 |
| TodoWriteTool | 任务管理 |
| SkillTool | 技能系统 |
| LoadMcpTool | 加载 MCP 工具 |

## 配置

### 环境变量

```bash
# API 密钥
export ANTHROPIC_API_KEY=your-api-key
```

### CLAUDE.md

在项目根目录创建 `CLAUDE.md` 文件可以自定义项目级别的配置和指令。

## Native Image 编译

```bash
# 安装 GraalVM JDK 17
# 设置 GRAALVM_HOME

# 编译 Native Image
./scripts/build-native.sh

# 生成的可执行文件位于 native/build/native/nativeCompile/
```

## 开发

### 代码风格

- 使用严格空安全检查 (`-Xjsr305=strict`)
- 支持上下文接收器 (`-Xcontext-receivers`)
- 双语注释（中英文）

### 运行测试

```bash
# 运行所有测试
./gradlew test

# 运行特定模块测试
./gradlew :core:test
./gradlew :engine:test

# 查看测试报告
open app/build/reports/tests/test/index.html
```

### 查看依赖

```bash
./gradlew allDeps
```

## 许可证

MIT License

## 贡献

欢迎提交 Issue 和 Pull Request。
