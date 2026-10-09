#!/bin/sh
# Run the off-hub tests. Needs only Java 8+; tools/groovy-jar.sh fetches
# Groovy 2.4.21 (the line the hub runs) on first use.
set -eu
TESTS="$(cd "$(dirname "$0")" && pwd)"
JAR="$("$TESTS/../../tools/groovy-jar.sh")"
cd "$TESTS"
exec java -Deconet.driverDir="$TESTS/.." -cp "$JAR:$TESTS" groovy.ui.GroovyMain run.groovy "$@"
