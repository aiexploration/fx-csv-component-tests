#!/usr/bin/env bash
# Interactive read-only queue browser using the RabbitMQ Management HTTP API.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLASSPATH_FILE="${SCRIPT_DIR}/target/test-classpath.txt"
MAIN_CLASS="com.fx.csvtest.tools.RabbitQueueInspector"

cd "$SCRIPT_DIR"

mvn -q -DskipTests test-compile

mvn -q \
  -Dtest=com.fx.csvtest.tools.TestClasspathWriterTest \
  -Dqueue.inspector.classpath.file="$CLASSPATH_FILE" \
  test

exec java -cp "$(cat "$CLASSPATH_FILE")" "$MAIN_CLASS" "$@"
