#!/usr/bin/env bash
# Build the unified DiscordAuth jar (Velocity + Paper) fully offline.
#
# Everything needed is already on this machine:
#   - Velocity API + Adventure        ../../servers/Velocity26/velocity.jar
#   - LimboAPI                        libs/limboapi-1.2.0.jar
#   - Paper/Purpur API 26.3           ../../servers/TESTrust/libraries/...
#   - PlaceholderAPI                  ../discord-auth-bridge/libs/placeholderapi-*.jar
#   - JDA + OkHttp + Kotlin + Gson    old shaded jar ../discord/discord-auth/target/discord-auth-1.7.6.jar
#
# The old shaded jar is reused only for its third-party libraries: its net/omegasmp classes
# and its velocity-plugin.json are removed before our classes are added.
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

VERSION="2.0.0"
JAR_NAME="DiscordAuth-${VERSION}.jar"
BASE="$(pwd)"

DEPS_JAR="${DEPS_JAR:-$BASE/../discord-auth/target/discord-auth-1.7.6.jar}"
VELOCITY_JAR="${VELOCITY_JAR:-$BASE/../../../servers/Velocity26/velocity.jar}"
LIMBO_JAR="${LIMBO_JAR:-$(ls "$BASE"/libs/limboapi-*.jar 2>/dev/null | head -1)}"
PAPI_JAR="${PAPI_JAR:-$(ls "$BASE"/../discord-auth-bridge/libs/placeholderapi-*.jar 2>/dev/null | head -1)}"
PAPER_API="${PAPER_API:-$(find "$BASE"/../../../servers -name 'purpur-api-*.jar' 2>/dev/null | head -1)}"
ADVENTURE_API="${ADVENTURE_API:-$(find "$BASE"/../../../servers -name 'adventure-api-*.jar' 2>/dev/null | head -1)}"
ADVENTURE_LEGACY="${ADVENTURE_LEGACY:-$(find "$BASE"/../../../servers -name 'adventure-text-serializer-legacy-*.jar' 2>/dev/null | head -1)}"
SNAKE_YAML="${SNAKE_YAML:-$(find "$BASE"/../../../servers -name 'snakeyaml-*.jar' 2>/dev/null | head -1)}"
BUNGEE_CHAT="${BUNGEE_CHAT:-$(find "$BASE"/../../../servers -name 'bungeecord-chat-*.jar' 2>/dev/null | head -1)}"

for f in "$DEPS_JAR" "$VELOCITY_JAR" "$LIMBO_JAR" "$PAPI_JAR" "$PAPER_API" "$ADVENTURE_API" "$ADVENTURE_LEGACY" "$BUNGEE_CHAT" "$SNAKE_YAML"; do
  if [ ! -f "$f" ]; then
    echo "Missing build dependency: $f" >&2
    echo "Set it explicitly, e.g. PAPER_API=/path/to/purpur-api.jar $0" >&2
    exit 1
  fi
done

CP="$DEPS_JAR:$VELOCITY_JAR:$LIMBO_JAR:$PAPI_JAR:$PAPER_API:$ADVENTURE_API:$ADVENTURE_LEGACY:$BUNGEE_CHAT:$SNAKE_YAML"

rm -rf target/classes target/fat
mkdir -p target/classes
find src/main/java -name '*.java' > target/sources.txt

echo "Compiling $(wc -l < target/sources.txt) source file(s)…"
javac -encoding UTF-8 --release 21 -nowarn -Xlint:-options -cp "$CP" -d target/classes @target/sources.txt

cp -r src/main/resources/. target/classes/

echo "Assembling fat jar…"
mkdir -p target/fat
unzip -q "$DEPS_JAR" -d target/fat
# Keep only third-party libraries from the old jar.
rm -rf target/fat/net/omegasmp
rm -f  target/fat/velocity-plugin.json target/fat/plugin.yml target/fat/config.json
rm -rf target/fat/META-INF/versions/*/module-info.class target/fat/module-info.class
rm -f  target/fat/META-INF/MANIFEST.MF target/fat/META-INF/*.SF target/fat/META-INF/*.RSA target/fat/META-INF/*.DSA
cp -r target/classes/. target/fat/

jar --create --file "target/${JAR_NAME}" -C target/fat .

echo "Done: $BASE/target/${JAR_NAME}"
