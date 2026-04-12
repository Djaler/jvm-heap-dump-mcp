#!/usr/bin/env bash
# End-to-end test of all MCP tools against the real test heap dump.
# Uses a FIFO for stdin and a log file for stdout; polls for responses.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || echo /usr/lib/jvm/java-21)}"
JAVA="$JAVA_HOME/bin/java"
JAR="$(ls "$PROJECT_DIR"/build/libs/jvm-heap-dump-mcp-*-all.jar 2>/dev/null | head -1)"
if [ -z "$JAR" ]; then echo "JAR not found. Run ./gradlew shadowJar first." >&2; exit 1; fi
DUMP="$PROJECT_DIR/src/test/resources/test-heap-dump.hprof"
STDERR_LOG=/tmp/mcp-e2e-stderr.log
STDOUT_LOG=/tmp/mcp-e2e-stdout.log

PASS=0
FAIL=0
NEXT_LINE_FILE=/tmp/mcp-e2e-next-line
echo 1 > "$NEXT_LINE_FILE"  # tracks how many lines from STDOUT_LOG have been consumed

# ── helpers ──────────────────────────────────────────────────────────────────

log()  { echo "[INFO]  $*"; }
ok()   { echo "[PASS]  $*"; PASS=$((PASS+1)); }
fail() { echo "[FAIL]  $*"; FAIL=$((FAIL+1)); }

# Send one JSON-RPC message and wait up to $2 seconds for a new JSON line.
# Skips non-JSON lines (e.g. startup log lines that leak to stdout).
# Writes the response JSON to RESP_FILE; prints it to stdout.
# Uses NEXT_LINE_FILE (file) to persist line-pointer across subshell calls.
RESP_FILE=/tmp/mcp-e2e-resp
send_and_receive() {
    local msg="$1"
    local timeout_sec="${2:-15}"
    echo "$msg" >&3
    local elapsed=0
    local cur
    cur=$(cat "$NEXT_LINE_FILE")
    while [ "$elapsed" -lt "$timeout_sec" ]; do
        local total
        total=$(wc -l < "$STDOUT_LOG" 2>/dev/null | tr -d ' ')
        while [ "$cur" -le "$total" ]; do
            local line
            line=$(sed -n "${cur}p" "$STDOUT_LOG")
            cur=$((cur+1))
            # Accept only lines that look like JSON objects (start with '{')
            case "$line" in
                '{'*)
                    echo "$cur" > "$NEXT_LINE_FILE"
                    echo "$line"
                    return
                    ;;
            esac
        done
        sleep 1
        elapsed=$((elapsed+1))
    done
    # Advance past any non-JSON lines we saw so the next call doesn't re-scan them
    echo "$cur" > "$NEXT_LINE_FILE"
    echo '{"error":{"message":"TIMEOUT: no response within '"$timeout_sec"'s"}}'
}

check_result() {
    local label="$1"
    local response="$2"
    if echo "$response" | jq -e '.result' >/dev/null 2>&1; then
        ok "$label"
    else
        local errmsg
        errmsg=$(echo "$response" | jq -r '.error.message // .error // "unknown error"' 2>/dev/null || echo "$response")
        fail "$label — $errmsg"
    fi
}

# ── startup ──────────────────────────────────────────────────────────────────

log "Starting MCP server..."
> "$STDOUT_LOG"
> "$STDERR_LOG"

FIFO=$(mktemp -u /tmp/mcp-e2e-fifo-XXXXXX)
mkfifo "$FIFO"
MCP_PID=0
cleanup() {
    exec 3>&- 2>/dev/null || true
    [ "$MCP_PID" -ne 0 ] && kill "$MCP_PID" 2>/dev/null || true
    wait "$MCP_PID" 2>/dev/null || true
    rm -f "$FIFO"
}
trap cleanup EXIT

$JAVA -Xmx4g -jar "$JAR" < "$FIFO" >> "$STDOUT_LOG" 2>"$STDERR_LOG" &
MCP_PID=$!

# Open write end of FIFO so server never sees EOF.
exec 3>"$FIFO"

log "Server PID: $MCP_PID"
sleep 2

# ── initialize ───────────────────────────────────────────────────────────────

log "Sending initialize..."
INIT_RESP=$(send_and_receive '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"e2e-test","version":"1.0"}}}' 15)
log "initialize response: $INIT_RESP"
check_result "initialize" "$INIT_RESP"

# Initialized notification — no response expected, don't advance line counter.
echo '{"jsonrpc":"2.0","method":"notifications/initialized"}' >&3
sleep 1

# ── tools/list ───────────────────────────────────────────────────────────────

log "Sending tools/list..."
LIST_RESP=$(send_and_receive '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' 15)
log "tools/list tools: $(echo "$LIST_RESP" | jq -c '.result.tools | map(.name)' 2>/dev/null || echo "$LIST_RESP")"
check_result "tools/list" "$LIST_RESP"

# ── open_heap_dump ───────────────────────────────────────────────────────────

log "Calling open_heap_dump (parsing 185 MB dump — up to 120 s)..."
OPEN_REQ=$(printf '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"open_heap_dump","arguments":{"path":"%s"}}}' "$DUMP")
OPEN_RESP=$(send_and_receive "$OPEN_REQ" 120)
log "open_heap_dump response (first 500 chars): $(echo "$OPEN_RESP" | cut -c1-500)"
check_result "open_heap_dump" "$OPEN_RESP"

SESSION_ID=$(echo "$OPEN_RESP" | jq -r '.result.content[0].text' 2>/dev/null | grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | head -1 || true)
if [ -z "$SESSION_ID" ]; then
    fail "Could not extract session ID from open_heap_dump response"
    log "Full open response: $OPEN_RESP"
    echo ""
    echo "Results: $PASS passed, $FAIL failed"
    echo "Stderr log: $STDERR_LOG"
    tail -20 "$STDERR_LOG" || true
    exit 1
fi
log "Session ID: $SESSION_ID"

# ── get_heap_summary ─────────────────────────────────────────────────────────

log "Calling get_heap_summary..."
SUMMARY_RESP=$(send_and_receive '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_heap_summary","arguments":{"id":"'"$SESSION_ID"'"}}}' 30)
log "get_heap_summary snippet: $(echo "$SUMMARY_RESP" | jq -r '.result.content[0].text' 2>/dev/null | head -5)"
check_result "get_heap_summary" "$SUMMARY_RESP"

# ── get_class_histogram ──────────────────────────────────────────────────────

log "Calling get_class_histogram..."
HISTO_RESP=$(send_and_receive '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"get_class_histogram","arguments":{"id":"'"$SESSION_ID"'"}}}' 60)
log "get_class_histogram snippet: $(echo "$HISTO_RESP" | jq -r '.result.content[0].text' 2>/dev/null | head -3)"
check_result "get_class_histogram" "$HISTO_RESP"

# ── get_leak_suspects ────────────────────────────────────────────────────────

log "Calling get_leak_suspects..."
LEAK_RESP=$(send_and_receive '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"get_leak_suspects","arguments":{"id":"'"$SESSION_ID"'"}}}' 120)
log "get_leak_suspects snippet: $(echo "$LEAK_RESP" | jq -r '.result.content[0].text' 2>/dev/null | head -3)"
check_result "get_leak_suspects" "$LEAK_RESP"

# ── execute_oql ──────────────────────────────────────────────────────────────

log "Calling execute_oql..."
OQL_RESP=$(send_and_receive '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"execute_oql","arguments":{"id":"'"$SESSION_ID"'","query":"SELECT * FROM java.lang.String","limit":5}}}' 60)
log "execute_oql snippet: $(echo "$OQL_RESP" | jq -r '.result.content[0].text' 2>/dev/null | head -3)"
check_result "execute_oql" "$OQL_RESP"

# ── get_dominator_tree ───────────────────────────────────────────────────────

log "Calling get_dominator_tree..."
DOM_RESP=$(send_and_receive '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"get_dominator_tree","arguments":{"id":"'"$SESSION_ID"'"}}}' 120)
log "get_dominator_tree snippet: $(echo "$DOM_RESP" | jq -r '.result.content[0].text' 2>/dev/null | head -3)"
check_result "get_dominator_tree" "$DOM_RESP"

# ── get_threads ──────────────────────────────────────────────────────────────

log "Calling get_threads..."
THREADS_RESP=$(send_and_receive '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"get_threads","arguments":{"id":"'"$SESSION_ID"'"}}}' 60)
log "get_threads snippet: $(echo "$THREADS_RESP" | jq -r '.result.content[0].text' 2>/dev/null | head -3)"
check_result "get_threads" "$THREADS_RESP"

# ── close_heap_dump ──────────────────────────────────────────────────────────

log "Calling close_heap_dump..."
CLOSE_RESP=$(send_and_receive '{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"close_heap_dump","arguments":{"id":"'"$SESSION_ID"'"}}}' 15)
log "close_heap_dump: $(echo "$CLOSE_RESP" | jq -r '.result.content[0].text' 2>/dev/null)"
check_result "close_heap_dump" "$CLOSE_RESP"

# ── summary ──────────────────────────────────────────────────────────────────

echo ""
echo "══════════════════════════════════════════"
echo " Results: $PASS passed, $FAIL failed"
echo "══════════════════════════════════════════"
echo " Stdout log : $STDOUT_LOG"
echo " Stderr log : $STDERR_LOG"
echo "══════════════════════════════════════════"

if [ "$FAIL" -gt 0 ]; then
    echo ""
    echo "Server stderr (last 40 lines):"
    tail -40 "$STDERR_LOG" || true
    exit 1
fi
