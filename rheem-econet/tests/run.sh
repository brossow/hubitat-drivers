#!/bin/sh
# Run the off-hub tests. Needs only Java 8+; fetches Groovy 2.4.21 (the line the
# hub runs) once into ~/.cache and verifies its checksum.
set -eu
GROOVY_VERSION=2.4.21
GROOVY_SHA1=8e4f4c30dbb9123fbf703f256cd721bbac5c902a
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/hubitat-groovy"
JAR="$CACHE/groovy-all-$GROOVY_VERSION.jar"

if [ ! -f "$JAR" ]; then
    mkdir -p "$CACHE"
    curl -sfL -o "$JAR.tmp" \
        "https://repo1.maven.org/maven2/org/codehaus/groovy/groovy-all/$GROOVY_VERSION/groovy-all-$GROOVY_VERSION.jar"
    echo "$GROOVY_SHA1  $JAR.tmp" | shasum -a 1 -c - >/dev/null
    mv "$JAR.tmp" "$JAR"
fi

TESTS="$(cd "$(dirname "$0")" && pwd)"
cd "$TESTS"
exec java -Deconet.driverDir="$TESTS/.." -cp "$JAR:$TESTS" groovy.ui.GroovyMain run.groovy "$@"
