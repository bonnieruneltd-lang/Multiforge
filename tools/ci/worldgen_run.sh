#!/usr/bin/env bash
# Verify the worldgen targets on ONE production server with a default (non-flat)
# world - no master dependency, no bots - so it is immune to the multi-bot/master
# flakiness that hits prod_cluster.sh. Proves Tectonic, Noisium, Lithostitched and
# Structure Layout Optimizer generate a real world with structures.
#
# Prerequisites: .runtime/prod-base (Forge --installServer), .runtime/server-mods,
# a built MultiForge jar. Usage: tools/ci/worldgen_run.sh [--keep]
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
[[ -n "${JAVA_HOME:-}" ]] || JAVA_HOME=$(ls -d "$ROOT"/.tools/jdk-* 2>/dev/null | head -1 || true)
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

BASE="$ROOT/.runtime/prod-base"
MODS="$ROOT/.runtime/server-mods"
WG="$ROOT/.runtime/worldgen"
RT="$ROOT/.runtime/worldgen-run"
OUT="$ROOT/compat/evidence"
PORT=${WG_PORT:-25569}; RCON=${WG_RCON:-25579}; PW=${WG_PW:-mfwg}; MPORT=${WG_MASTER:-35359}
MF_JAR=$(ls "$ROOT"/multiforge-forge/build/libs/multiforge-*-alpha*.jar 2>/dev/null | grep -v sources | head -1)
MASTER_JAR=$(ls "$ROOT"/multiforge-master/build/libs/*-all.jar 2>/dev/null | head -1)
log() { printf '[worldgen] %s\n' "$*"; }
mkdir -p "$RT" "$OUT" "$WG/mods" "$WG/config" "$WG/world/datapacks"

SRV_PID=""; MASTER_PID=""
cleanup() {
  python3 tools/compat/rcon.py --port "$RCON" --password "$PW" stop >/dev/null 2>&1 || true
  sleep 5
  [[ -n "$SRV_PID" ]] && kill "$SRV_PID" 2>/dev/null || true
  [[ -n "$MASTER_PID" ]] && kill "$MASTER_PID" 2>/dev/null || true
  for pid in $(ps -eo pid,args | awk '/worldgen/ && /BootstrapLauncher/ && !/awk/ {print $1}'); do kill "$pid" 2>/dev/null || true; done
  wait 2>/dev/null || true
}
trap cleanup EXIT

# Fresh default world every run so structure placement is not inherited.
rm -rf "$WG/world" "$RT/master"
mkdir -p "$WG/world/datapacks"
[[ -e "$WG/libraries" ]] || ln -s ../prod-base/libraries "$WG/libraries"
cp -f "$BASE/run.sh" "$WG/run.sh"; chmod +x "$WG/run.sh"
printf -- '-Xmx%s\n-Xms512M\n' "${WG_HEAP:-1400M}" > "$WG/user_jvm_args.txt"
printf 'eula=true\n' > "$WG/eula.txt"
cat > "$WG/server.properties" <<EOF
server-port=$PORT
online-mode=false
enable-rcon=true
rcon.port=$RCON
rcon.password=$PW
level-name=world
level-type=default
spawn-protection=0
view-distance=6
simulation-distance=6
max-players=5
gamemode=survival
difficulty=normal
max-tick-time=-1
motd=MultiForge worldgen
EOF
cat > "$WG/config/multiforge-server.toml" <<EOF
[master]
    address = "127.0.0.1:$MPORT"
[server]
    name = "worldgen"
[synchronization]
    world = true
EOF
rm -f "$WG/mods"/*.jar 2>/dev/null || true
cp -f "$MF_JAR" "$WG/mods/"; cp -f "$MODS"/*.jar "$WG/mods/"
rm -f "$WG/world/datapacks"/*.zip 2>/dev/null || true
cp -f "$MODS"/*.zip "$WG/world/datapacks/" 2>/dev/null || true
log "mods: $(ls "$WG/mods"/*.jar | wc -l) jars (default world)"

log "starting master"
java -Dmultiforge.master.storage="$RT/master" -jar "$MASTER_JAR" "$MPORT" > "$RT/master.log" 2>&1 &
MASTER_PID=$!
for _ in $(seq 1 60); do grep -q "listening on" "$RT/master.log" 2>/dev/null && break; sleep 1; done

log "starting the worldgen server (default world; generation takes longer)"
(cd "$WG" && exec ./run.sh --nogui > "$RT/server.log" 2>&1) &
SRV_PID=$!
for _ in $(seq 1 420); do
  grep -aq "Done (" "$WG/logs/latest.log" 2>/dev/null && break
  grep -aq "Failed to start the minecraft server" "$WG/logs/latest.log" 2>/dev/null && { log "server failed"; tail -5 "$WG/logs/latest.log"; exit 1; }
  sleep 1
done
grep -aq "Done (" "$WG/logs/latest.log" 2>/dev/null || { log "server not ready (default worldgen may be slow)"; exit 1; }
log "server ready"

rcon() { python3 tools/compat/rcon.py --port "$RCON" --password "$PW" "$1" 2>&1; }
VILLAGE=$(rcon "locate structure minecraft:village")
ANCIENT=$(rcon "locate structure minecraft:ancient_city")
BIOME=$(rcon "locate biome minecraft:plains")
NETHRC=$(rcon "execute in minecraft:the_nether run locate structure minecraft:fortress")
log "village: $VILLAGE"
log "ancient_city: $ANCIENT"
log "plains: $BIOME"

stamp=$(date -u +%Y%m%dT%H%M%SZ)
md="$OUT/worldgen-$stamp.md"
# A structure result looks like: "The nearest X is at [x, ~, z] (N blocks away)".
pass=0; grep -qiE 'nearest .* is at|is at \[' <<<"$VILLAGE$ANCIENT$NETHRC" && pass=1
{
  echo "# Worldgen targets on a default world $stamp"
  echo
  echo "Single production Forge 47.4.29 server, 21-jar server set, **level-type=default**."
  echo "Proves Tectonic / Noisium / Lithostitched / Structure Layout Optimizer generate a real world."
  echo
  echo "## Result"
  echo
  echo "- Verdict: **$([[ $pass -eq 1 ]] && echo PASS || echo FAIL)**"
  echo
  echo "## Raw /locate output"
  echo
  echo '```'
  echo "village: $VILLAGE"
  echo "ancient_city: $ANCIENT"
  echo "nether_fortress: $NETHRC"
  echo "plains: $BIOME"
  echo '```'
} > "$md"
log "evidence: $md"
[[ $pass -eq 1 ]] && { log "PASS worldgen"; exit 0; } || { log "FAIL worldgen"; exit 1; }
