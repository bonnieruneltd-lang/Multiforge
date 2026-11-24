#!/usr/bin/env bash
# Isolated single-server 2-bot gameplay run: verify Just Player Heads (player kill
# drops a head) and Darceno's Minecarts (a powered-rail cart transports) on ONE
# production server, away from the multi-server/master flakiness of prod_cluster.sh.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
[[ -n "${JAVA_HOME:-}" ]] || JAVA_HOME=$(ls -d "$ROOT"/.tools/jdk-* 2>/dev/null | head -1 || true)
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
BASE="$ROOT/.runtime/prod-base"; MODS="$ROOT/.runtime/server-mods"
DG="$ROOT/.runtime/single"; RT="$ROOT/.runtime/single-run"; OUT="$ROOT/compat/evidence"
PORT=${SP_PORT:-25571}; RCON=${SP_RCON:-25581}; A_CTRL=25597; B_CTRL=25598; PW=${SP_PW:-mfsingle}; MPORT=${SP_MASTER:-35361}
MF_JAR=$(ls "$ROOT"/multiforge-forge/build/libs/multiforge-*-alpha*.jar 2>/dev/null | grep -v sources | head -1)
MASTER_JAR=$(ls "$ROOT"/multiforge-master/build/libs/*-all.jar 2>/dev/null | head -1)
log(){ printf '[single] %s\n' "$*"; }
mkdir -p "$RT" "$OUT" "$DG/mods" "$DG/config" "$DG/world/datapacks"
SRV_PID=""; MASTER_PID=""; BA=""; BB=""
cleanup(){ python3 tools/compat/rcon.py --port "$RCON" --password "$PW" stop >/dev/null 2>&1||true; sleep 5
  for p in "$BA" "$BB" "$SRV_PID" "$MASTER_PID"; do [[ -n "$p" ]]&&kill "$p" 2>/dev/null||true; done
  for pid in $(ps -eo pid,args|awk '/single/&&/BootstrapLauncher/&&!/awk/{print $1}'); do kill "$pid" 2>/dev/null||true; done
  for pid in $(ps -eo pid,args|awk '/mcbot\\.js/&&!/awk/{print $1}'); do kill "$pid" 2>/dev/null||true; done
  wait 2>/dev/null||true; }
trap cleanup EXIT
rm -rf "$DG/world" "$RT/master"; mkdir -p "$DG/world/datapacks" "$RT/master"
[[ -e "$DG/libraries" ]]||ln -s ../prod-base/libraries "$DG/libraries"
cp -f "$BASE/run.sh" "$DG/run.sh"; chmod +x "$DG/run.sh"
printf -- '-Xmx%s\n-Xms512M\n' "${SP_HEAP:-1200M}" > "$DG/user_jvm_args.txt"; printf 'eula=true\n' > "$DG/eula.txt"
cat > "$DG/server.properties" <<EOF
server-port=$PORT
online-mode=false
enable-rcon=true
rcon.port=$RCON
rcon.password=$PW
level-name=world
level-type=flat
spawn-protection=0
view-distance=6
simulation-distance=6
max-players=10
gamemode=survival
difficulty=normal
max-tick-time=-1
motd=MultiForge single
EOF
cat > "$DG/config/multiforge-server.toml" <<EOF
[master]
    address = "127.0.0.1:$MPORT"
[server]
    name = "single"
[synchronization]
    world = true
EOF
rm -f "$DG/mods"/*.jar 2>/dev/null||true; cp -f "$MF_JAR" "$DG/mods/"; cp -f "$MODS"/*.jar "$DG/mods/"
rm -f "$DG/world/datapacks"/*.zip 2>/dev/null||true; cp -f "$MODS"/*.zip "$DG/world/datapacks/" 2>/dev/null||true
# Combatlog hotbar notice crashes the tick loop; disable it (documented mitigation).
sed -i 's/^\tenabled = true/\tenabled = false/' "$DG/config/combatlog.toml" 2>/dev/null||true

log "starting master + single server"
java -Dmultiforge.master.storage="$RT/master" -jar "$MASTER_JAR" "$MPORT" > "$RT/master.log" 2>&1 & MASTER_PID=$!
for _ in $(seq 1 60); do grep -q "listening on" "$RT/master.log" 2>/dev/null&&break; sleep 1; done
(cd "$DG" && exec ./run.sh --nogui > "$RT/server.log" 2>&1) & SRV_PID=$!
for _ in $(seq 1 300); do grep -aq "Done (" "$DG/logs/latest.log" 2>/dev/null&&break; sleep 1; done
grep -aq "Done (" "$DG/logs/latest.log" 2>/dev/null||{ log "server not ready"; exit 1; }
log "server ready"
python3 - "$PORT" <<'PY'
import socket,sys,time
p=int(sys.argv[1])
for _ in range(120):
    try: s=socket.create_connection(("127.0.0.1",p),1);s.close();sys.exit(0)
    except OSError: time.sleep(0.5)
sys.exit(1)
PY
if [[ ! -d tools/compat/node_modules ]]; then (cd tools/compat && npm install --no-audit --no-fund > "$RT/npm.log" 2>&1); fi
startb(){ (cd tools/compat && node mcbot.js 127.0.0.1 "$PORT" SyncBotA "$A_CTRL" > "$RT/bot-a.log" 2>&1)& BA=$!
          (cd tools/compat && node mcbot.js 127.0.0.1 "$PORT" SyncBotB "$B_CTRL" > "$RT/bot-b.log" 2>&1)& BB=$!; }
loggedin(){ grep -q LOGIN "$RT/bot-a.log" 2>/dev/null && grep -q LOGIN "$RT/bot-b.log" 2>/dev/null; }
for a in 1 2 3; do startb; for _ in $(seq 1 60); do loggedin&&break; sleep 1; done; loggedin&&break; kill "$BA" "$BB" 2>/dev/null||true; sleep 3; done
loggedin || { log "bots did not log in"; exit 1; }
sleep 6
rcon(){ python3 tools/compat/rcon.py --port "$RCON" --password "$PW" "$1" 2>&1; }
log "player-head"
rcon "kill @e[type=item]" >/dev/null
rcon "damage SyncBotB 200 minecraft:player_attack by SyncBotA" >/dev/null
sleep 6
items=$(rcon "execute as @e[type=item] run data get entity @s Item")
head=PASS; grep -q player_head <<<"$items"||head=FAIL
log "minecart (floor-integrated powered rail)"
rcon "fill 2 -61 2 12 -61 2 minecraft:redstone_block" >/dev/null
rcon "fill 2 -60 2 12 -60 2 minecraft:powered_rail" >/dev/null
rcon "kill @e[type=minecart]" >/dev/null
rcon "summon minecart 2.5 -58.9 2.5" >/dev/null
rcon "data merge entity @e[type=minecart,limit=1,sort=nearest] {Motion:[0.3d,0.0d,0.0d]}" >/dev/null
sleep 4
pos=$(rcon "data get entity @e[type=minecart,limit=1,sort=nearest] Pos")
moved=$(python3 -c "import re,sys;t='''$pos''';g=re.search(r'\\[(-?[0-9.]+)d?',t);print(f'{float(g.group(1))-2.5:.2f}' if g else 'na')")
cart=PASS; python3 -c "import sys;sys.exit(0 if float('$moved')>=1.5 else 1)" 2>/dev/null||cart=FAIL
stamp=$(date -u +%Y%m%dT%H%M%SZ); md="$OUT/gameplay-single-$stamp.md"
{ echo "# Single-server gameplay $stamp"; echo
  echo "One production server, level-type=flat, two bots co-located."
  echo "- player-head (Just Player Heads): **$head** — items: \`$(tr '\n' ' '<<<"$items"|cut -c1-160)\`"
  echo "- minecart-transport (Darceno's): **$cart** — moved $moved blocks (>=1.5 required); Pos: \`$(tr '\n' ' '<<<"$pos"|cut -c1-120)\`"; } > "$md"
log "player-head=$head minecart=$cart (moved $moved) -> $md"
[[ $head == PASS && $cart == PASS ]] && exit 0 || exit 1
