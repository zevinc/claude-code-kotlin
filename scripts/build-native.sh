#!/usr/bin/env bash
# Claude Code Kotlin - GraalVM Native Image Build Script
# Claude Code Kotlin - GraalVM 原生镜像构建脚本
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "Claude Code - Native Image Builder"
echo "==================================="

# Check for GraalVM
# 检查 GraalVM
if ! command -v native-image &>/dev/null; then
    echo "WARNING: native-image not found in PATH."
    echo "Install GraalVM and run: gu install native-image"
    echo ""
    echo "Alternatives:"
    echo "  brew install graalvm-ce-java17"
    echo "  sdk install java 22.3.r17-grl"
    echo ""
    echo "Falling back to JVM distribution build..."
    cd "$PROJECT_DIR"
    ./gradlew :app:installDist --no-configuration-cache
    echo "JVM distribution built at: app/build/install/claude-code/"
    exit 0
fi

echo "GraalVM: $(native-image --version)"
echo ""

cd "$PROJECT_DIR"

echo "Building native image (this may take several minutes)..."
./gradlew :app:nativeCompile --no-configuration-cache

NATIVE_BIN="$PROJECT_DIR/app/build/native/nativeCompile/claude-code"

if [ -f "$NATIVE_BIN" ]; then
    echo ""
    echo "Native image built successfully!"
    echo "  Binary: $NATIVE_BIN"
    echo "  Size: $(du -h "$NATIVE_BIN" | cut -f1)"
    echo ""
    echo "Run: $NATIVE_BIN --help"
else
    echo "ERROR: Native image build failed."
    exit 1
fi
