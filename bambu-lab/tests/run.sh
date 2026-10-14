#!/bin/sh
# Run the off-hub tests. Needs only Java 8+; Groovy is fetched on first use.
#   tests/run.sh               everything
#   tests/run.sh ams           only tests whose name contains "ams"
# DRIVER= and APP= test another copy of either file (e.g. main's, to show a new test catches a bug).
set -u
TESTS="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$TESTS/../.." && pwd)"
JAR="$("$ROOT/tools/groovy-jar.sh")" || exit 1
DRIVER="${DRIVER:-$TESTS/../bambu-printer.groovy}"
APP="${APP:-$TESTS/../bambu-lab-app.groovy}"

java -cp "$JAR" groovy.ui.GroovyMain "$TESTS/run.groovy" "$DRIVER" "$@"; d=$?
java -cp "$JAR" groovy.ui.GroovyMain "$TESTS/app.groovy" "$APP" "$@"; a=$?
# 3 means a filter matched nothing in that file; that's only a failure if it matched nothing anywhere
if [ $d -eq 3 ] && [ $a -eq 3 ]; then exit 1; fi
if { [ $d -ne 0 ] && [ $d -ne 3 ]; } || { [ $a -ne 0 ] && [ $a -ne 3 ]; }; then exit 1; fi
exit 0
