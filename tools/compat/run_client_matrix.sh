#!/usr/bin/env bash
# Run the complete GPU client matrix on a prepared machine and record evidence.
#
# The script is the one-command wrapper around tools/compat/client_matrix.py and
# tools/compat/launch_client.py: it refuses to start on a machine without a usable
# OpenGL renderer, pins and downloads the client artifacts, assembles the Prism
# instance, installs the production Forge client, then runs one client per shader
# pack and writes compat/evidence/client-matrix-<utc>.{md,json} for each.
#
# Usage:
#   tools/compat/run_client_matrix.sh [options]
#
#   --workdir DIR         instance root                (default .runtime/client)
#   --launcher DIR        launcher root for the client (default .runtime/launcher)
#   --java PATH           Java 17 executable            (default: java on PATH)
#   --assets DIR          Minecraft assets              (default: launcher/assets,
#                                                        else the ForgeGradle cache)
#   --shaders "a b c"     shader target keys, one run each
#   --settle SECONDS      time for Oculus to compile a pack (default 200)
#   --memory-mb MB        client heap (default 4096)
#   --multiforge-jar PATH client jar to add to mods/     (default: build it)
#   --allow-software      continue on software OpenGL (llvmpipe); evidence is labelled
#   --skip-build          do not run ./gradlew :multiforge-forge:build
#   --refresh-manifest    re-pin versions from Modrinth before assembling the instance
#   --dry-run             print the commands instead of running them
#   -h, --help            this text
#
# Requirements: Ubuntu 22.04/24.04, an OpenGL 3.2+ core renderer (a real GPU for
# shader rows), Java 17, python3, and outbound HTTPS.  See docs/CLIENT_MATRIX.md.
#
# Exit code: 0 when every run reported STARTED, 1 when the host check or a run
# failed, 2 on a usage error.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
WORKDIR="$ROOT/.runtime/client"
LAUNCHER="$ROOT/.runtime/launcher"
JAVA=${JAVA:-$(command -v java || true)}
ASSETS=""
SHADERS="bsl-shaders complementary-reimagined sildurs-vibrant-lite"
SETTLE=200
MEMORY_MB=4096
MULTIFORGE_JAR=""
ALLOW_SOFTWARE=0
SKIP_BUILD=0
REFRESH_MANIFEST=0
DRY_RUN=0

usage() { sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --workdir) WORKDIR=$2; shift 2 ;;
    --launcher) LAUNCHER=$2; shift 2 ;;
    --java) JAVA=$2; shift 2 ;;
    --assets) ASSETS=$2; shift 2 ;;
    --shaders) SHADERS=$2; shift 2 ;;
    --settle) SETTLE=$2; shift 2 ;;
    --memory-mb) MEMORY_MB=$2; shift 2 ;;
    --multiforge-jar) MULTIFORGE_JAR=$2; shift 2 ;;
    --allow-software) ALLOW_SOFTWARE=1; shift ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --refresh-manifest) REFRESH_MANIFEST=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown argument $1" >&2; exit 2 ;;
  esac
done

run() {
  if [[ $DRY_RUN -eq 1 ]]; then
    printf '+ %s\n' "$*"
  else
    "$@"
  fi
}

[[ -n "$JAVA" ]] || { echo "error: no java found; pass --java /path/to/java" >&2; exit 2; }
command -v python3 >/dev/null || { echo "error: python3 is required" >&2; exit 2; }
mkdir -p "$ROOT/.runtime/logs"

# Absolute paths: the client is started with cwd=<game_dir>, so a relative launcher or
# assets path would be resolved twice and the launcher would look in a nested directory.
WORKDIR=$(realpath -m "$WORKDIR")
LAUNCHER=$(realpath -m "$LAUNCHER")
if [[ -n "$ASSETS" ]]; then ASSETS=$(realpath -m "$ASSETS"); fi

echo "== host probe ($(uname -srm))"
if ! run python3 "$ROOT/tools/compat/client_matrix.py" host \
      --instance "$WORKDIR" --java "$JAVA"; then
  if [[ $ALLOW_SOFTWARE -eq 1 ]]; then
    echo "warning: host probe failed; continuing because --allow-software was given" >&2
  else
    echo "error: fix the GPU/driver/Java rows above (docs/CLIENT_MATRIX.md) or pass" >&2
    echo "       --allow-software to record a labelled software-OpenGL run" >&2
    exit 1
  fi
fi

if [[ -z "$MULTIFORGE_JAR" && $SKIP_BUILD -eq 0 && -x "$ROOT/gradlew" ]]; then
  echo "== build the MultiForge client jar"
  run "$ROOT/gradlew" -q :multiforge-forge:build
fi

if [[ -z "$ASSETS" ]]; then
  if [[ -d "$LAUNCHER/assets" ]]; then
    ASSETS="$LAUNCHER/assets"
  else
    ASSETS="$HOME/.gradle/caches/forge_gradle/assets"
  fi
fi

if [[ $REFRESH_MANIFEST -eq 1 ]]; then
  echo "== re-pin the client artifacts from Modrinth"
  run python3 "$ROOT/tools/compat/resolve_required_mods.py" --write \
      --download-client "$ROOT/.runtime/client-mods"
fi

echo "== assemble the instance (versions and SHA-512 from compat/required-mods.json)"
PREPARE=(prepare --instance "$WORKDIR" --java "$JAVA")
if [[ -d "$ROOT/.runtime/client-mods" ]]; then
  PREPARE+=(--source "$ROOT/.runtime/client-mods")
fi
if [[ -n "$MULTIFORGE_JAR" ]]; then
  PREPARE+=(--multiforge-jar "$MULTIFORGE_JAR")
fi
run python3 "$ROOT/tools/compat/client_matrix.py" "${PREPARE[@]}"

echo "== install the production client"
run python3 "$ROOT/tools/compat/launch_client.py" install \
    --launcher-dir "$LAUNCHER" --java "$JAVA"

status=0
for shader in $SHADERS; do
  echo "== client run with $shader"
  run python3 "$ROOT/tools/compat/client_matrix.py" run \
    --instance "$WORKDIR" --shader "$shader" --settle "$SETTLE" --java "$JAVA" \
    --command "python3 $ROOT/tools/compat/launch_client.py run --launcher-dir $LAUNCHER --game-dir {game_dir} --assets-dir $ASSETS --java $JAVA --memory-mb $MEMORY_MB" \
    || status=1
done

echo "== evidence written to $ROOT/compat/evidence/client-matrix-*.md"
exit $status
