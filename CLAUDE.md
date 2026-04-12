# Memory Analyzer MCP Server

MCP server for JVM Heap Dump (.hprof) analysis using Eclipse MAT libraries.
Enables AI agents (Claude Code, etc.) to autonomously investigate OOM issues.

## Tech Stack

- **Language**: Kotlin (2.3+)
- **Build**: Gradle with Kotlin DSL, version catalog (`gradle/libs.versions.toml`)
- **MCP SDK**: `io.modelcontextprotocol:kotlin-sdk:0.11.0` (stdio transport)
- **Heap Analysis**: Eclipse MAT 1.16.1 (pulled via `dev.equo.p2deps` from P2 update site)
- **JVM**: 21
- **Distribution**: Shadow JAR (`./gradlew shadowJar`), npm wrapper (`npx jvm-heap-dump-mcp`)

## Architecture

```
com.github.djaler.jvmheapdumpmcp
  ├── Main.kt              — Entry point, stdio transport
  ├── server/              — MCP server wiring
  │   └── McpServerFactory — Tool registration
  ├── tools/               — MCP tool handlers (one file per group)
  ├── mat/                 — Eclipse MAT integration (isolated)
  │   ├── MatBootstrap     — OSGi context mocking for standalone MAT
  │   ├── SnapshotManager  — ISnapshot session cache
  │   └── MatFacade        — Kotlin facade over MAT API
  └── model/               — Data classes for tool results
```

### Key Design Decisions

- **MAT runs standalone** without Eclipse/OSGi — we mock the OSGi context (approach from Eclipse JIFA)
- **Session-based**: `open_heap_dump` returns a session ID; all tools use it to reference the cached `ISnapshot`
- **All MAT interaction** goes through `MatFacade` — tools never touch MAT classes directly
- **Tool results** are plain text (TextContent) formatted for LLM readability

## Bash Commands Rule

**Never** use multiple commands separated by newlines in a single Bash tool call.
- Chain dependent commands with `&&` on one line: `export JAVA_HOME=... && ./gradlew build`
- Use separate parallel Bash calls for independent commands
- If you need a repeated multi-step action — ask to create a helper script in `scripts/`

## Building & Running

```bash
# Build
./gradlew build

# Fat JAR
./gradlew shadowJar

# Run (stdio transport for MCP)
java -Xmx4g -jar build/libs/jvm-heap-dump-mcp-*-all.jar
```

## Testing

```bash
# Unit tests only (default, no heap dump needed)
./gradlew test

# All tests including integration (requires test heap dump in src/test/resources/)
./gradlew test -PincludeIntegration
```

## Dev Scripts

- `scripts/inspect-class.sh <ClassName> [jar]` — inspect a class via javap; searches gradle caches if jar is omitted
- `scripts/test-mcp.sh` — quick MCP protocol smoke test (initialize + tools/list) via named pipe
- `scripts/test-mcp-e2e.sh` — full end-to-end test of all 17 tools against the test heap dump

All scripts auto-detect `JAVA_HOME` and use relative paths from the project root.

## Adding New MCP Tools

1. Add method to `MatFacade` for the MAT operation
2. Create/update tool handler in `tools/` package
3. Register tool in `McpServerFactory`

## Changelog

When making changes, update `CHANGELOG.md`:
- Use [Keep a Changelog](https://keepachangelog.com/) format
- Sections: Added, Changed, Fixed, Removed
- Unreleased changes go under `## [Unreleased]`
- On release (git tag): rename `[Unreleased]` to `[x.y.z] - YYYY-MM-DD`
