#!/bin/sh
# Prints the path to the Groovy 2.4.21 jar (the line the hub runs), fetching it
# once into ~/.cache and verifying its checksum. Shared by tools/check-syntax.sh
# and the driver test runners; needs only curl and shasum.
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
echo "$JAR"
