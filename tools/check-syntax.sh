#!/bin/sh
# Syntax-check Groovy files with the Groovy line the hub runs.
#   tools/check-syntax.sh              every tracked .groovy file
#   tools/check-syntax.sh a.groovy ... just these files
# Needs only Java 8+; fetches Groovy 2.4.21 once into ~/.cache and verifies its
# checksum (the same cached jar rheem-econet/tests/run.sh uses).
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

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [ "$#" -eq 0 ]; then
    set -- $(git ls-files '*.groovy')
fi
exec java -cp "$JAR" groovy.ui.GroovyMain tools/SyntaxCheck.groovy "$@"
