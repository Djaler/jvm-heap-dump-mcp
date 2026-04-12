#!/usr/bin/env bash
# Test MCP server via stdio. Sends initialize + initialized + tools/list, prints responses.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || echo /usr/lib/jvm/java-21)}"
JAR="$(ls "$PROJECT_DIR"/build/libs/jvm-heap-dump-mcp-*-all.jar 2>/dev/null | head -1)"
if [ -z "$JAR" ]; then echo "JAR not found. Run ./gradlew shadowJar first." >&2; exit 1; fi

FIFO=$(mktemp -u /tmp/mcp-fifo-XXXXXX)
mkfifo "$FIFO"
trap "rm -f $FIFO" EXIT

$JAVA_HOME/bin/java -Xmx4g -jar "$JAR" < "$FIFO" 2>/tmp/mcp-test-stderr.log &
MCP_PID=$!

exec 3>"$FIFO"

sleep 2

# Initialize
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0"}}}' >&3
sleep 1

# Initialized notification
echo '{"jsonrpc":"2.0","method":"notifications/initialized"}' >&3
sleep 1

# List tools
echo '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' >&3
sleep 2

# Optional: call a tool (pass arg $1 to test a specific tool)
if [ "${1:-}" = "open" ]; then
    echo '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"open_heap_dump","arguments":{"path":"'"$PROJECT_DIR"'/src/test/resources/test-heap-dump.hprof"}}}' >&3
    sleep 30
fi

exec 3>&-
kill $MCP_PID 2>/dev/null || true
wait $MCP_PID 2>/dev/null || true
