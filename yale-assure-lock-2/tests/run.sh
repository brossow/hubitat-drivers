#!/bin/sh
# Run the off-hub tests. Needs only Java 8+; Groovy is fetched on first use.
#   tests/run.sh               everything
#   tests/run.sh setCode       only tests whose name contains "setCode"
#   DRIVER=other.groovy tests/run.sh   run against another copy (e.g. upstream 1.07)
set -eu
TESTS="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$TESTS/../.." && pwd)"
JAR="$("$ROOT/tools/groovy-jar.sh")"
exec java -cp "$JAR" groovy.ui.GroovyMain "$TESTS/run.groovy" \
    "${DRIVER:-$TESTS/../YaleAssure2.groovy}" "$@"
