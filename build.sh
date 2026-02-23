#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== OwnClaw Build ==="

# Use local JDK if available
if [ -d "$HOME/.jdk/jdk-21"* ] 2>/dev/null; then
    export JAVA_HOME="$(ls -d "$HOME"/.jdk/jdk-21* | head -1)"
    echo "Using JAVA_HOME=$JAVA_HOME"
fi

./gradlew build -x test "$@"

echo ""
echo "Build complete. JAR: build/libs/ownclaw-0.1.0.jar"
