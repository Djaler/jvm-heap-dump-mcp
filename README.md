# Memory Analyzer MCP Server

MCP server for JVM heap dump (.hprof) analysis powered by [Eclipse MAT](https://eclipse.dev/mat/).
Enables AI agents (Claude Code, Cursor, etc.) to autonomously investigate OutOfMemoryError issues.

## Quick Start

**Requirements:** Java 21+, Node.js 18+ (for npx)

Add to your project's `.mcp.json`:

```json
{
  "mcpServers": {
    "memory-analyzer": {
      "command": "npx",
      "args": ["-y", "jvm-heap-dump-mcp"]
    }
  }
}
```

Restart Claude Code. The MCP handshake completes instantly; the JAR (~28 MB) downloads in the background on first use. Tools become available via `notifications/tools/list_changed` once the download finishes (~30–60 seconds on a typical connection). If an agent calls a tool before the JAR is ready, it will get a clear "still initializing" error and can retry.

## Pre-downloading the JAR (recommended on slow or corporate networks)

You can warm the cache explicitly before starting your MCP client. This runs the download synchronously in your terminal with visible progress and a clear error if something fails (SSL, proxy, etc.):

```bash
npx -y jvm-heap-dump-mcp --prepare
```

Once it prints `Done. JAR cached at ...`, every subsequent MCP server startup is instant. The JAR lives in `~/.cache/jvm-heap-dump-mcp/` keyed by version, so running `--prepare` after upgrading to a new version pre-downloads the new JAR without touching the old one.

If a download gets interrupted (network drop, you kill the process, Claude Code restarts mid-download), the next `--prepare` or MCP startup **resumes from where it stopped** via HTTP Range — no need to re-download the ~28 MB from zero.

## What It Does

Ask your AI agent to analyze a heap dump:

> "Open the heap dump at /tmp/oom.hprof and find what's causing the OOM"

The agent will use the tools to:
1. **Open** the dump and get a summary (heap size, object count)
2. **Dominator tree** to find objects retaining the most memory
3. **Drill down** into the largest retainers
4. **Path to GC roots** to understand why objects can't be garbage collected
5. **Threads** with stack traces to see what was happening at the time of OOM
6. **Close** the dump when done

## Tools

| Tool | Description |
|------|-------------|
| `open_heap_dump` | Open a .hprof file, returns session ID + heap summary |
| `close_heap_dump` | Close session and free memory |
| `list_sessions` | List open sessions |
| `get_heap_summary` | Heap size, object/class counts, GC roots |
| `get_leak_suspects` | Eclipse MAT's automatic leak detection |
| `get_class_histogram` | Memory consumption by class (object count, shallow/retained heap) |
| `get_class_instances` | All instances of a class with sizes |
| `get_dominator_tree` | Top objects by retained heap |
| `get_dominator_tree_children` | Drill into dominator subtree |
| `get_object_info` | Object details: class, sizes, field values |
| `get_outbound_references` | Objects referenced by this object |
| `get_inbound_references` | Objects referencing this object |
| `get_path_to_gc_roots` | Shortest paths to GC roots |
| `get_threads` | Thread list with retained heap and stack traces |
| `execute_oql` | Run OQL queries (SQL-like for heap objects) |
| `find_strings` | Search String objects by regex |
| `inspect_array` | View array contents (byte[], int[], Object[], etc.) |

## Updating

npx caches the package locally. To update to the latest version:

```bash
npx -y jvm-heap-dump-mcp@latest
```

The JAR for each version is cached separately under `~/.cache/jvm-heap-dump-mcp/`, so upgrading only re-downloads the JAR if the version changed.

## Troubleshooting

**"Still initializing" errors that don't go away:** the background download might be repeatedly getting killed. Run `npx -y jvm-heap-dump-mcp --prepare` in a terminal — you'll see the real error (network, SSL, proxy) in plain text. It also resumes from any partial `.downloading` file in `~/.cache/jvm-heap-dump-mcp/` instead of starting over.

**"Failed to reconnect" after an update:** clear the npx cache and restart. The `_npx` cache can corrupt on macOS when a previous install was interrupted:

```bash
rm -rf ~/.npm/_npx
```

## Memory Requirements

The MCP server needs heap proportional to the dump size. Default is `-Xmx4g`. For larger dumps, configure via environment variable:

```json
{
  "mcpServers": {
    "memory-analyzer": {
      "command": "npx",
      "args": ["-y", "jvm-heap-dump-mcp"],
      "env": {
        "JAVA_OPTS": "-Xmx8g"
      }
    }
  }
}
```

## Building from Source

```bash
./gradlew build          # compile + unit tests
./gradlew shadowJar      # fat JAR at build/libs/jvm-heap-dump-mcp-*-all.jar
./gradlew test -PincludeIntegration  # run integration tests (requires test heap dump)
```

## License

MIT
