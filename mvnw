#!/bin/sh
# ----------------------------------------------------------------------------
# Maven Wrapper - enhanced minimal version
# ----------------------------------------------------------------------------

set -e

BASEDIR=$(cd "$(dirname "$0")" && pwd)
WRAPPER_JAR="$BASEDIR/.mvn/wrapper/maven-wrapper.jar"

if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: Java is not installed or not in PATH"
    echo "Please install JDK 21 and try again"
    exit 1
fi

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "ERROR: maven-wrapper.jar not found at $WRAPPER_JAR"
    echo "Re-run: mvn wrapper:wrapper"
    exit 1
fi

exec java \
  -Xms1024m \
  -Xmx1024m \
  -Dmaven.multiModuleProjectDirectory="$BASEDIR" \
  -classpath "$WRAPPER_JAR" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"