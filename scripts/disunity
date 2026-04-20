#!/bin/sh
BASEDIR=$(cd "$(dirname "$0")" && pwd)
JAR="$BASEDIR/disunity.jar"

if [ ! -f "$JAR" ]; then
  JAR=$(ls "$BASEDIR/../disunity-dist/target"/disunity-dist-*-shaded.jar 2>/dev/null | head -n 1)
fi

if [ ! -f "$JAR" ]; then
  echo "Could not find disunity jar. Build it with: mvn -q clean package"
  exit 1
fi

java -jar "$JAR" "$@"
