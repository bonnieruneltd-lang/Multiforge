#!/usr/bin/env bash
# Boot a PRODUCTION MultiForge cluster (master + two Forge 47.4.29 dedicated
# servers with the pinned server mod set) and run the compatibility checks.
#
# Why production and not tools/ci/live_cluster.sh: the required server mods ship
# SRG-named jars whose refmaps do not resolve in a ForgeGradle dev run. Only a
# real `--installServer` Forge install loads them, which is what the gameplay
# compatibility rows (Beautified Chat, TPA, AntiXray, minecarts, heads, ...) need.
#
# Prerequisites (see docs/DEVELOPMENT.md):
#   python3 tools/compat/resolve_required_mods.py --write --download .runtime/server-mods
#   java -jar forge-installer.jar --installServer .runtime/prod-base
#   ./gradlew :multiforge-forge:build :multiforge-master:fatJar
#
# Usage:
#   tools/ci/prod_cluster.sh                     # reuse worlds, run checks
#   FRESH=1 tools/ci/prod_cluster.sh             # recreate worlds + master storage
#   HEAP=1200M EXPECTED=19 tools/ci/prod_cluster.sh
#
# The game dirs reuse the install through a symlinked `libraries/` so the ~160 MiB
# library tree exists once. Exit code 0 only when every check passes.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"

if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME=$(ls -d "$ROOT"/.tools/jdk-* 2>/dev/null | head -1 || true)
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JAVA_HOME is not set and no .tools/jdk-* was found" >&2
  exit 2
fi
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

RCON_PASSWORD=${RCON_PASSWORD:-mfprod}
A_PORT=${A_PORT:-25565};  B_PORT=${B_PORT:-25566}
A_RCON=${A_RCON:-25575};  B_RCON=${B_RCON:-25576}
A_CTRL=${A_CTRL:-25599};  B_CTRL=${B_CTRL:-25600}
HEAP=${HEAP:-1200M}
EXPECTED=${EXPECTED:-19}
OUT=${OUT:-compat/evidence}
FRESH=${FRESH:-0}
MASTER_PORT=${MASTER_PORT:-35353}

BASE="$ROOT/.runtime/prod-base"
P1="$ROOT/.runtime/prod1"
P2="$ROOT/.runtime/prod2"
RUNTIME="$ROOT/.runtime/prod-cluster"
MODS="$ROOT/.runtime/server-mods"
MF_JAR=$(ls "$ROOT"/multiforge-forge/build/libs/multiforge-*-alpha*.jar 2>/dev/null | grep -v sources | head -1)
MASTER_JAR=$(ls "$ROOT"/multiforge-master/build/libs/*-all.jar 2>/dev/null | head -1)

log() { printf '[prod-cluster] %s\n' "$*"; }

[[ -d "$BASE" ]] || { echo "error: $BASE missing; run the Forge --installServer step first" >&2; exit 2; }
[[ -d "$MODS" ]] || { echo "error: $MODS missing; run resolve_required_mods.py --download" >&2; exit 2; }
[[ -n "$MF_JAR" ]] || { echo "error: MultiForge jar missing; run ./gradlew :multiforge-forge:build" >&2; exit 2; }
[[ -n "$MASTER_JAR" ]] || { echo "error: master jar missing; run ./gradlew :multiforge-master:fatJar" >&2; exit 2; }

mkdir -p "$RUNTIME" "$OUT"
# Start from a clean slate: stale evidence logs would make the chat/gameplay checks
# read a previous run's lines, and a lingering bot would hold the control port so the
# new bot never logs in.
rm -f "$RUNTIME"/*.log 2>/dev/null || true
# Sweep any stragglers from an aborted previous run: a leftover master holds port
# 35353, a leftover server holds 2556x, a leftover bot holds a control port.
for pid in $(ps -eo pid,args | awk '/(multiforge-master.*-all\.jar)|BootstrapLauncher|mcbot\.js/ && !/awk/ {print $1}'); do
  kill "$pid" 2>/dev/null || true
done
sleep 2

write_server_files() {
  local dir=$1 name=$2 port=$3 rcon=$4
  mkdir -p "$dir/mods" "$dir/config" "$dir/world/datapacks"
  # Reuse the install's libraries through a symlink so the tree exists once.
  [[ -e "$dir/libraries" ]] || ln -s ../prod-base/libraries "$dir/libraries"
  cp -f "$BASE/run.sh" "$dir/run.sh"
  chmod +x "$dir/run.sh"
  printf -- '-Xmx%s\n-Xms512M\n' "$HEAP" > "$dir/user_jvm_args.txt"
  printf 'eula=true\n' > "$dir/eula.txt"
  cat > "$dir/server.properties" <<EOF
server-port=$port
server-ip=
online-mode=false
enable-rcon=true
rcon.port=$rcon
rcon.password=$RCON_PASSWORD
broadcast-rcon-to-ops=false
level-name=world
level-type=flat
spawn-protection=0
view-distance=6
simulation-distance=6
max-players=20
gamemode=survival
difficulty=normal
max-tick-time=-1
motd=MultiForge $name
EOF
  cat > "$dir/config/multiforge-server.toml" <<EOF
[master]
    address = "127.0.0.1:$MASTER_PORT"

[server]
    name = "$name"

[synchronization]
    world = true
EOF
  # Install the mod set + MultiForge jar (stop the server before touching mods/).
  rm -f "$dir/mods"/*.jar 2>/dev/null || true
  cp -f "$MF_JAR" "$dir/mods/"
  cp -f "$MODS"/*.jar "$dir/mods/"
  rm -f "$dir/world/datapacks"/*.zip 2>/dev/null || true
  cp -f "$MODS"/*.zip "$dir/world/datapacks/" 2>/dev/null || true
}

MASTER_PID=""; P1_PID=""; P2_PID=""; BOT_A_PID=""; BOT_B_PID=""
cleanup() {
  log "shutting down"
  python3 tools/compat/rcon.py --port "$A_RCON" --password "$RCON_PASSWORD" stop >/dev/null 2>&1 || true
  python3 tools/compat/rcon.py --port "$B_RCON" --password "$RCON_PASSWORD" stop >/dev/null 2>&1 || true
  sleep 6
  for pid in "$P1_PID" "$P2_PID" "$MASTER_PID" "$BOT_A_PID" "$BOT_B_PID"; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done
  for pid in $(ps -eo pid,args | awk '/prod1|prod2/ && /BootstrapLauncher/ && !/awk/ {print $1}'); do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT

wait_ready() {
  local logfile=$1 name=$2
  for _ in $(seq 1 240); do
    if grep -aq "MultiForge ready" "$logfile" 2>/dev/null; then log "$name is ready"; return 0; fi
    if grep -aq "Failed to start the minecraft server\|FATAL" "$logfile" 2>/dev/null; then
      log "$name failed to start (see $logfile)"; return 1
    fi
    sleep 1
  done
  log "$name did not become ready in time (see $logfile)"; return 1
}

# Wait until a TCP port accepts connections. mcbot connects exactly once and exits
# on ECONNREFUSED, so the game port must be open before the bots start.
wait_for_port() {
  local port=$1
  python3 - "$port" <<'PY'
import socket, sys, time
port = int(sys.argv[1])
for _ in range(180):
    try:
        s = socket.create_connection(("127.0.0.1", port), 1.0); s.close(); sys.exit(0)
    except OSError:
        time.sleep(0.5)
sys.exit(1)
PY
}

if [[ "$FRESH" == "1" ]]; then
  log "recreating production worlds and master storage"
  rm -rf "$P1/world" "$P2/world" "$RUNTIME/master"
fi
mkdir -p "$RUNTIME/master"
write_server_files "$P1" live1 "$A_PORT" "$A_RCON"
write_server_files "$P2" live2 "$B_PORT" "$B_RCON"
log "mods per server: $(ls "$P1/mods"/*.jar | wc -l) jars, datapacks: $(ls "$P1/world/datapacks"/*.zip 2>/dev/null | wc -l)"

log "starting master ($(basename "$MASTER_JAR"))"
java -Dmultiforge.master.storage="$RUNTIME/master" -jar "$MASTER_JAR" "$MASTER_PORT" \
  > "$RUNTIME/master.log" 2>&1 &
MASTER_PID=$!
for _ in $(seq 1 60); do grep -q "listening on" "$RUNTIME/master.log" 2>/dev/null && break; sleep 1; done
grep -q "listening on" "$RUNTIME/master.log" || { log "master did not start"; tail -5 "$RUNTIME/master.log"; exit 1; }

log "starting live1 (production)"
(cd "$P1" && exec ./run.sh --nogui > "$RUNTIME/server1.log" 2>&1) &
P1_PID=$!
wait_ready "$P1/logs/latest.log" "live1" || exit 1

log "starting live2 (production)"
(cd "$P2" && exec ./run.sh --nogui > "$RUNTIME/server2.log" 2>&1) &
P2_PID=$!
wait_ready "$P2/logs/latest.log" "live2" || exit 1

log "waiting for the game ports to accept connections"
wait_for_port "$A_PORT" || { log "port $A_PORT not accepting connections"; exit 1; }
wait_for_port "$B_PORT" || { log "port $B_PORT not accepting connections"; exit 1; }

if [[ ! -d "$ROOT/tools/compat/node_modules" ]]; then
  log "installing bot dependencies"
  (cd "$ROOT/tools/compat" && npm install --no-audit --no-fund > "$RUNTIME/npm.log" 2>&1) || { log "npm install failed"; exit 1; }
fi
log "connecting bots"
(cd "$ROOT/tools/compat" && node mcbot.js 127.0.0.1 "$A_PORT" SyncBotA "$A_CTRL" > "$RUNTIME/bot-a.log" 2>&1) &
BOT_A_PID=$!
(cd "$ROOT/tools/compat" && node mcbot.js 127.0.0.1 "$B_PORT" SyncBotB "$B_CTRL" > "$RUNTIME/bot-b.log" 2>&1) &
BOT_B_PID=$!
logged_in() { grep -q LOGIN "$RUNTIME/bot-a.log" 2>/dev/null && grep -q LOGIN "$RUNTIME/bot-b.log" 2>/dev/null; }
for _ in $(seq 1 60); do logged_in && break; sleep 1; done
if ! logged_in; then
  log "bots did not log in on the first try; retrying once"
  tail -3 "$RUNTIME/bot-a.log" 2>/dev/null | sed 's/^/[a] /'
  tail -3 "$RUNTIME/bot-b.log" 2>/dev/null | sed 's/^/[b] /'
  kill "$BOT_A_PID" "$BOT_B_PID" 2>/dev/null || true; sleep 2
  (cd "$ROOT/tools/compat" && node mcbot.js 127.0.0.1 "$A_PORT" SyncBotA "$A_CTRL" > "$RUNTIME/bot-a.log" 2>&1) &
  BOT_A_PID=$!
  (cd "$ROOT/tools/compat" && node mcbot.js 127.0.0.1 "$B_PORT" SyncBotB "$B_CTRL" > "$RUNTIME/bot-b.log" 2>&1) &
  BOT_B_PID=$!
  for _ in $(seq 1 45); do logged_in && break; sleep 1; done
fi
logged_in || { log "bots did not log in"; exit 1; }
sleep 10

STATUS=0
log "== cluster checks (expected targets $EXPECTED)"
python3 tools/compat/cluster_check.py --a-port "$A_RCON" --b-port "$B_RCON" \
  --password "$RCON_PASSWORD" --expected-targets "$EXPECTED" --out "$OUT" 2>&1 | tee "$RUNTIME/cluster.log"
[[ ${PIPESTATUS[0]} -eq 0 ]] || STATUS=1

log "== cross-server chat formatting (Beautified Chat hop)"
python3 tools/compat/chat_format_check.py --a-control "$A_CTRL" --b-log "$RUNTIME/bot-b.log" \
  --sender SyncBotA --out "$OUT" 2>&1 | tee "$RUNTIME/chat.log"
[[ ${PIPESTATUS[0]} -eq 0 ]] || STATUS=1

log "== mod gameplay checks"
python3 tools/compat/gameplay_check.py --a-port "$A_RCON" --b-port "$B_RCON" \
  --password "$RCON_PASSWORD" --a-control "$A_CTRL" --b-control "$B_CTRL" \
  --a-log "$RUNTIME/bot-a.log" --b-log "$RUNTIME/bot-b.log" --out "$OUT" 2>&1 | tee "$RUNTIME/gameplay.log"
[[ ${PIPESTATUS[0]} -eq 0 ]] || STATUS=1

if [[ "$STATUS" -eq 0 ]]; then log "PASS (cluster + chat + gameplay)"; else log "FAILED (see $RUNTIME)"; fi
exit "$STATUS"

