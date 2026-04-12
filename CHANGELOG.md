# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

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
