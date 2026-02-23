#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

JAR="build/libs/ownclaw-0.1.0.jar"

# Build if JAR doesn't exist
if [ ! -f "$JAR" ]; then
    echo "JAR not found, building first..."
    ./build.sh
fi

# Use local JDK if available
if [ -d "$HOME/.jdk/jdk-21"* ] 2>/dev/null; then
    export JAVA_HOME="$(ls -d "$HOME"/.jdk/jdk-21* | head -1)"
fi

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

echo "=== OwnClaw ==="
echo "WebUI: http://localhost:${OWNCLAW_PORT:-8080}"
echo "Press Ctrl+C to stop"
echo ""

exec "$JAVA" \
    -jar "$JAR" \
    --server.port="${OWNCLAW_PORT:-8080}" \
    "$@"
