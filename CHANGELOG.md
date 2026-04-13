# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

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
