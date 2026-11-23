#!/usr/bin/env bash
# Compile and run the (JUnit 4) unit tests offline.
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

JUNIT4="$(find /home/omega/devtools /home/omega/.gradle -name 'junit-4*.jar' 2>/dev/null | head -1)"
HAMCREST="$(find /home/omega/devtools /home/omega/.gradle -name 'hamcrest-core*.jar' 2>/dev/null | head -1)"
if [ -z "$JUNIT4" ] || [ -z "$HAMCREST" ]; then
  echo "JUnit 4 / Hamcrest not found." >&2
  exit 1
fi

# Reuse build.sh dependency discovery by sourcing a minimal CP.
VELOCITY_JAR="${VELOCITY_JAR:-$(pwd)/../../../servers/Velocity26/velocity.jar}"
DEPS_JAR="${DEPS_JAR:-$(ls "$(pwd)"/libs/deps-*.jar | head -1)}"
CP="$DEPS_JAR:$VELOCITY_JAR:$JUNIT4:$HAMCREST"

# Main classes must be compiled first.
if [ ! -d target/classes ]; then
  bash build.sh >/dev/null
fi

rm -rf target/test-classes
mkdir -p target/test-classes
find src/test/java -name '*.java' > target/test-sources.txt
javac -encoding UTF-8 --release 21 -cp "$CP:target/classes" -d target/test-classes @target/test-sources.txt

echo "Running tests..."
java -cp "$CP:target/classes:target/test-classes" org.junit.runner.JUnitCore \
  net.omegasmp.discordauth.core.DurationParserTest \
  net.omegasmp.discordauth.core.TextRenderTest \
  net.omegasmp.discordauth.core.ConfigDefaultsTest
