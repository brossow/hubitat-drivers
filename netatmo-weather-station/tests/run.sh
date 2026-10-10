#!/bin/sh
# Run the off-hub tests. Needs only Java 8+; Groovy is fetched on first use.
#   tests/run.sh               everything
#   tests/run.sh refresh       only tests whose name contains "refresh"
set -eu
TESTS="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$TESTS/../.." && pwd)"
JAR="$("$ROOT/tools/groovy-jar.sh")"
exec java -cp "$JAR" groovy.ui.GroovyMain "$TESTS/run.groovy" "$TESTS/.." "$@"
