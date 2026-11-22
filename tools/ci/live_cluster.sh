#!/usr/bin/env bash
# Boot a complete MultiForge cluster (master + two Forge dev servers), connect the
# headless bots, run the live compatibility and fight-state checks, and shut
# everything down again. This is the repeatable version of the manual procedure in
# docs/DEVELOPMENT.md.
#
# Requirements: a built mod (./gradlew :multiforge-forge:compileJava), Node 18+ with
# tools/compat/node_modules (npm install), RCON enabled in the run directories (the
# script writes server.properties if it is missing) and ~6 GiB of free memory.
#
# Usage:
#   tools/ci/live_cluster.sh                 # reuse run/ and run2/ worlds
#   FRESH=1 tools/ci/live_cluster.sh         # recreate the world and master storage
#   HEAP=1200M OUT=compat/evidence tools/ci/live_cluster.sh
#
# Exit code 0 only when both harnesses pass.
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
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
unset JAVA_TOOL_OPTIONS || true

RCON_PASSWORD=${RCON_PASSWORD:-mfcompat}
A_PORT=${A_PORT:-25565}
B_PORT=${B_PORT:-25566}
A_RCON=${A_RCON:-25575}
B_RCON=${B_RCON:-25576}
HEAP=${HEAP:-1000M}
OUT=${OUT:-compat/evidence}
RUNTIME=${RUNTIME:-$ROOT/.runtime/live-cluster}
FRESH=${FRESH:-0}
MASTER_PORT=${MASTER_PORT:-35353}

RUN1="$ROOT/multiforge-forge/run"
RUN2="$ROOT/multiforge-forge/run2"
mkdir -p "$RUNTIME" "$OUT" "$RUN1/config" "$RUN2/config" "$RUNTIME/master"

MASTER_PID=""
SERVER2_PID=""
BOT_A_PID=""
BOT_B_PID=""
GRADLE1_PID=""
FAILED=0

log() { printf '[live-cluster] %s\n' "$*"; }

cleanup() {
  log "shutting down"
  python3 tools/compat/rcon.py --port "$A_RCON" --password "$RCON_PASSWORD" stop >/dev/null 2>&1 || true
  python3 tools/compat/rcon.py --port "$B_RCON" --password "$RCON_PASSWORD" stop >/dev/null 2>&1 || true
  sleep 5
  # RCON stop is graceful; kill anything still alive (a failed startup, a stuck save).
  for pid in $(ps -eo pid,args | awk '/forgeserveruserdev/ && !/awk/ {print $1}'); do
    kill "$pid" 2>/dev/null || true
  done
  for pid in "$SERVER2_PID" "$MASTER_PID" "$BOT_A_PID" "$BOT_B_PID" "$GRADLE1_PID"; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done
  pkill -f 'GradleWrapperMain.*:multiforge-forge:runServer' 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT

write_server_files() {
  local dir=$1 name=$2 port=$3 rcon=$4 motd=$5
  mkdir -p "$dir/config"
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
gamemode=creative
difficulty=normal
max-tick-time=-1
motd=$motd
EOF
  cat > "$dir/config/multiforge-server.toml" <<EOF
[master]
    address = "127.0.0.1:$MASTER_PORT"

[server]
    name = "$name"

[synchronization]
    world = true
EOF
}

wait_for_ready() {
  local logfile=$1 name=$2
  for _ in $(seq 1 180); do
    if grep -q "MultiForge ready" "$logfile" 2>/dev/null; then
      log "$name is ready"
      return 0
    fi
    sleep 1
  done
  log "$name did not become ready; see $logfile"
  return 1
}

log "building the master fat jar"
./gradlew --no-daemon :multiforge-master:fatJar > "$RUNTIME/master-build.log" 2>&1 || {
  log "master build failed"; exit 1;
}

if [[ "$FRESH" == "1" ]]; then
  log "recreating worlds and master storage"
  rm -rf "$RUN1/world" "$RUN1/world_nether" "$RUN1/world_the_end" \
         "$RUN2/world" "$RUN2/world_nether" "$RUN2/world_the_end" \
         "$RUNTIME/master"/*
  # A fresh cluster must not inherit jars from a previous mod-profile run. Set
  # MODS_DIR to install a controlled set again after this cleanup.
  if [[ -z "${MODS_DIR:-}" ]]; then
    rm -f "$RUN1/mods"/*.jar "$RUN2/mods"/*.jar
  fi
fi
write_server_files "$RUN1" live1 "$A_PORT" "$A_RCON" "MultiForge live1"
write_server_files "$RUN2" live2 "$B_PORT" "$B_RCON" "MultiForge live2"
if [[ -n "${MODS_DIR:-}" ]]; then
  log "installing mods from $MODS_DIR"
  mkdir -p "$RUN1/mods" "$RUN2/mods"
  cp "$MODS_DIR"/*.jar "$RUN1/mods/" 2>/dev/null || true
  cp "$MODS_DIR"/*.jar "$RUN2/mods/" 2>/dev/null || true
fi
rm -rf "$RUN1/logs" "$RUN2/logs"

MASTER_JAR=$(ls "$ROOT"/multiforge-master/build/libs/*-all.jar | head -1)
log "starting master ($MASTER_JAR)"
java -Dmultiforge.master.storage="$RUNTIME/master" -jar "$MASTER_JAR" "$MASTER_PORT" \
  > "$RUNTIME/master.log" 2>&1 &
MASTER_PID=$!
for _ in $(seq 1 60); do
  grep -q "listening on" "$RUNTIME/master.log" 2>/dev/null && break
  sleep 1
done
grep -q "listening on" "$RUNTIME/master.log" || { log "master did not start"; exit 1; }

log "starting live1 (Gradle dev run)"
(./gradlew --no-daemon :multiforge-forge:runServer -PmultiforgeRunHeap="$HEAP" \
   > "$RUNTIME/server1-gradle.log" 2>&1) &
GRADLE1_PID=$!
wait_for_ready "$RUN1/logs/latest.log" live1 || exit 1

log "starting live2 (same JVM command, run2 working directory)"
SERVER1_PID=$(ps -eo pid,args | awk '/forgeserveruserdev/ && !/awk/ {print $1; exit}')
if [[ -z "$SERVER1_PID" ]]; then
  log "could not find the live1 JVM"; exit 1
fi
python3 - "$SERVER1_PID" "$RUN2" "$RUNTIME/server2.pid" "$RUNTIME/server2.log" > "$RUNTIME/server2-launch.log" 2>&1 <<'PY'
import os, subprocess, sys
pid, cwd, pidfile, logfile = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
args = [a.decode() for a in open(f"/proc/{pid}/cmdline", "rb").read().split(b"\0") if a]
env = {}
for item in open(f"/proc/{pid}/environ", "rb").read().decode("utf-8", "ignore").split("\0"):
    if "=" in item:
        key, value = item.split("=", 1)
        env[key] = value
out = open(logfile, "wb")
proc = subprocess.Popen(args, cwd=cwd, env=env, stdout=out,
                        stderr=subprocess.STDOUT, start_new_session=True)
open(pidfile, "w").write(str(proc.pid))
print(proc.pid)
PY
SERVER2_PID=$(cat "$RUNTIME/server2.pid" 2>/dev/null || true)
[[ -n "$SERVER2_PID" ]] || { log "live2 did not launch"; exit 1; }
wait_for_ready "$RUN2/logs/latest.log" live2 || exit 1

if [[ ! -d "$ROOT/tools/compat/node_modules" ]]; then
  log "installing the headless bot dependencies"
  (cd "$ROOT/tools/compat" && npm install --no-audit --no-fund > "$RUNTIME/npm.log" 2>&1) || {
    log "npm install failed"; exit 1;
  }
fi

log "connecting the headless bots"
(cd "$ROOT/tools/compat" && node mcbot.js 127.0.0.1 "$A_PORT" SyncBotA 25599 \
   > "$RUNTIME/bot-a.log" 2>&1) &
BOT_A_PID=$!
(cd "$ROOT/tools/compat" && node mcbot.js 127.0.0.1 "$B_PORT" SyncBotB 25600 \
   > "$RUNTIME/bot-b.log" 2>&1) &
BOT_B_PID=$!
for _ in $(seq 1 40); do
  grep -q "LOGIN" "$RUNTIME/bot-a.log" 2>/dev/null && \
    grep -q "LOGIN" "$RUNTIME/bot-b.log" 2>/dev/null && break
  sleep 1
done
grep -q "LOGIN" "$RUNTIME/bot-a.log" || { log "bot A did not log in"; exit 1; }
grep -q "LOGIN" "$RUNTIME/bot-b.log" || { log "bot B did not log in"; exit 1; }
# Give the servers a moment to tick the player chunks and claim ownership.
sleep 10

log "running the cluster checks"
python3 tools/compat/cluster_check.py \
  --a-port "$A_RCON" --b-port "$B_RCON" --password "$RCON_PASSWORD" \
  --skip-mod-checks --out "$OUT" 2>&1 | tee "$RUNTIME/cluster_check.log"
CLUSTER_STATUS=${PIPESTATUS[0]}

log "running the forced-loss check (50% of block updates dropped, retransmit must converge)"
python3 tools/compat/packet_loss_check.py \
  --a-port "$A_RCON" --b-port "$B_RCON" --password "$RCON_PASSWORD" \
  --out "$OUT" 2>&1 | tee "$RUNTIME/packet_loss.log"
LOSS_STATUS=${PIPESTATUS[0]}

log "running the fight-state checks (starts a dragon fight, builds a raid, hands both over)"
python3 tools/compat/fight_sync_check.py \
  --a-port "$A_RCON" --b-port "$B_RCON" --password "$RCON_PASSWORD" \
  --out "$OUT" 2>&1 | tee "$RUNTIME/fight_check.log"
FIGHT_STATUS=${PIPESTATUS[0]}

if [[ "$CLUSTER_STATUS" -ne 0 || "$LOSS_STATUS" -ne 0 || "$FIGHT_STATUS" -ne 0 ]]; then
  FAILED=1
  log "FAILED (cluster=$CLUSTER_STATUS loss=$LOSS_STATUS fight=$FIGHT_STATUS)"
else
  log "PASS (cluster, forced-loss and fight checks)"
fi
exit "$FAILED"

