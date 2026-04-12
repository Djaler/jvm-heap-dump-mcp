#!/usr/bin/env bash
# Usage: ./scripts/inspect-class.sh <fully.qualified.ClassName> [jar-path]
# Inspects a class from the project classpath using javap.
# If jar-path is omitted, searches in ~/.gradle/caches for the class.

set -euo pipefail

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || echo /usr/lib/jvm/java-21)}"

CLASS_NAME="${1:?Usage: inspect-class.sh <fully.qualified.ClassName> [jar-path]}"
JAR_PATH="${2:-}"

if [ -z "$JAR_PATH" ]; then
    CLASS_FILE="$(echo "$CLASS_NAME" | sed 's/\./\//g').class"
    echo "Searching for class $CLASS_NAME in gradle caches..." >&2
    FOUND=$(find ~/.gradle/caches/modules-2/files-2.1 -name "*.jar" -exec sh -c "$JAVA_HOME/bin/jar tf '{}' 2>/dev/null | grep -q '$CLASS_FILE' && echo '{}'" \; 2>/dev/null | head -1)
    if [ -z "$FOUND" ]; then
        echo "Class not found in gradle caches" >&2
        exit 1
    fi
    JAR_PATH="$FOUND"
    echo "Found in: $JAR_PATH" >&2
fi

"$JAVA_HOME/bin/javap" -classpath "$JAR_PATH" "$CLASS_NAME" 2>&1
