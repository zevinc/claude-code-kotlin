#!/usr/bin/env bash
# Claude Code Kotlin - Installation Script
# Claude Code Kotlin - 安装脚本
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/bin}"

echo "Claude Code (Kotlin) Installer"
echo "=============================="

# Check Java version
# 检查 Java 版本
if ! command -v java &>/dev/null; then
    echo "ERROR: Java 17+ is required. Please install JDK 17 or later."
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d '"' -f 2 | cut -d '.' -f 1)
if [ "$JAVA_VERSION" -lt 17 ] 2>/dev/null; then
    echo "ERROR: Java 17+ is required (found: $JAVA_VERSION)"
    exit 1
fi
echo "Java version: $(java -version 2>&1 | head -1)"

# Build the project
# 构建项目
echo ""
echo "Building claude-code..."
cd "$PROJECT_DIR"
./gradlew :app:installDist --no-configuration-cache -q

DIST_DIR="$PROJECT_DIR/app/build/install/claude-code"

if [ ! -d "$DIST_DIR" ]; then
    echo "ERROR: Build failed. Distribution directory not found."
    exit 1
fi

# Create install directory
# 创建安装目录
mkdir -p "$INSTALL_DIR"

# Create wrapper script
# 创建包装脚本
cat > "$INSTALL_DIR/claude-code" << 'WRAPPER'
#!/usr/bin/env bash
# Claude Code (Kotlin) launcher
# Claude Code (Kotlin) 启动器
DIST_DIR="DIST_PLACEHOLDER"
exec "$DIST_DIR/bin/claude-code" "$@"
WRAPPER

sed -i '' "s|DIST_PLACEHOLDER|$DIST_DIR|g" "$INSTALL_DIR/claude-code" 2>/dev/null || \
    sed -i "s|DIST_PLACEHOLDER|$DIST_DIR|g" "$INSTALL_DIR/claude-code"
chmod +x "$INSTALL_DIR/claude-code"

echo ""
echo "Installation complete!"
echo "  Binary: $INSTALL_DIR/claude-code"
echo ""
echo "Make sure $INSTALL_DIR is in your PATH:"
echo "  export PATH=\"$INSTALL_DIR:\$PATH\""
echo ""
echo "Set your API key:"
echo "  export ANTHROPIC_API_KEY=sk-ant-..."
echo ""
echo "Run: claude-code"
