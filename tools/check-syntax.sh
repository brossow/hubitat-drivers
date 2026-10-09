#!/bin/sh
# Syntax-check Groovy files with the Groovy line the hub runs.
#   tools/check-syntax.sh              every tracked .groovy file
#   tools/check-syntax.sh a.groovy ... just these files
# Needs only Java 8+; tools/groovy-jar.sh fetches Groovy on first use.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$("$ROOT/tools/groovy-jar.sh")"
cd "$ROOT"
if [ "$#" -eq 0 ]; then
    set -- $(git ls-files '*.groovy')
fi
exec java -cp "$JAR" groovy.ui.GroovyMain tools/SyntaxCheck.groovy "$@"
