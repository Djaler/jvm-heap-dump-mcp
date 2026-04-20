# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `heap-dump-generator` submodule — standalone Kotlin program that generates test heap dumps with known data structures (collections with varying fill rates, maps with known contents, ThreadLocal values, retained set tree, leaked objects). Produces two dumps: baseline and "leaked" for histogram comparison tests.
- Gradle task `generateTestHeapDump` to regenerate test heap dumps.

## [0.1.6] - 2026-04-13

### Added

- `npx -y jvm-heap-dump-mcp --prepare` — downloads the JAR synchronously with progress on stdout and exits, so users on slow or corporate networks can warm the cache before starting the MCP client.

### Fixed

- Interrupted JAR downloads now resume via HTTP Range instead of restarting from zero. A `.downloading` file is no longer deleted on error, so subsequent runs pick up where the previous one left off.
- Pre-flight `HEAD` check detects a fully-downloaded-but-unrenamed file (race if the wrapper was killed between pipeline end and rename) and completes the rename without re-downloading.

## [0.1.5] - 2026-04-13

### Changed

- npm wrapper now answers the MCP `initialize` request instantly and downloads the JAR in the background. Tools become available via `notifications/tools/list_changed` once the download finishes. This eliminates the "Failed to reconnect" timeouts that occurred on slow or corporate networks.
- JAR is no longer bundled in the npm package (~28 MB → ~4 KB tarball). This also fixes the macOS `ENOTEMPTY` corruption in npx cache when upgrading versions.
- JAR is fetched from GitHub Releases into `~/.cache/jvm-heap-dump-mcp/` on first run per version. Subsequent startups are instant (warm cache).
- `tools/call` invocations that arrive before the JAR is ready return a clear "still initializing" error (JSON-RPC `-32000`) instead of hanging.

## [0.1.4] - 2026-04-13

### Fixed

- Bundled JAR directory renamed from `bin/` to `lib/` to avoid conflict with the `bin` field in package.json, which caused npm to skip extracting `package.json` and `run.mjs` from the tarball (resulted in "Failed to reconnect" errors)

## [0.1.3] - 2026-04-13

### Changed

- JAR is now bundled inside the npm package for instant startup — no more runtime download from GitHub Releases, no MCP initialize timeouts on slow connections

### Removed

- Runtime JAR download from GitHub Releases
- `~/.cache/jvm-heap-dump-mcp/` cache directory (can be safely deleted after upgrading)

## [0.1.2] - 2026-04-13

### Fixed

- `NoClassDefFoundError` when MAT tries to load classes via bundle classloader on certain heap dumps — mock Bundle now delegates `loadClass`/`getResource`/`getResources` to the system classloader

### Changed

- Error messages now include exception class name and root cause for easier diagnosis

## [0.1.1] - 2026-04-13

### Fixed

- Atomic JAR download to prevent corrupted cache on interrupted downloads
- npm publish workflow (allow same version, public access)

### Changed

- Added update instructions to README

## [0.1.0] - 2026-04-13

### Added

- MCP server with stdio transport using Kotlin MCP SDK 0.11.0
- Eclipse MAT 1.16.1 integration running standalone (without OSGi/Eclipse IDE)
- 17 MCP tools for heap dump analysis:
  - `open_heap_dump` / `close_heap_dump` / `list_sessions` — session management
  - `get_heap_summary` — heap size, object/class counts, GC roots
  - `get_leak_suspects` — automatic leak detection via MAT's FindLeaksQuery
  - `get_class_histogram` — memory consumption by class with sorting and filtering
  - `get_class_instances` — list all instances of a class
  - `get_dominator_tree` / `get_dominator_tree_children` — retained heap analysis
  - `get_object_info` — object details with field values (auto-resolves String fields)
  - `get_outbound_references` / `get_inbound_references` — object graph navigation
  - `get_path_to_gc_roots` — shortest paths to GC roots
  - `get_threads` — thread list with stack traces and retained heap
  - `execute_oql` — Object Query Language support
  - `find_strings` — search String objects by regex
  - `inspect_array` — view array contents (byte[], int[], Object[], etc.)
- npm wrapper package (`npx jvm-heap-dump-mcp`) for easy installation
- GitHub Actions CI/CD (build on push, release on tag with npm publish)
- Shadow JAR packaging (~31 MB) with all dependencies bundled
