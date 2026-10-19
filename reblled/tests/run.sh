#!/bin/sh
# Run the off-hub tests. Needs only Java 8+; Groovy is fetched on first use.
#   tests/run.sh               everything
#   tests/run.sh socket        only tests whose name contains "socket"
#   DRIVER=other.groovy tests/run.sh   test another copy of the driver
set -eu
TESTS="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$TESTS/../.." && pwd)"
JAR="$("$ROOT/tools/groovy-jar.sh")"
exec java -cp "$JAR" groovy.ui.GroovyMain "$TESTS/run.groovy" \
    "${DRIVER:-$TESTS/../reblled-controller.groovy}" "$TESTS/../packageManifest.json" "$@"
