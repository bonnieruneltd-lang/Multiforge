# Client compatibility matrix

## Why a separate machine is needed

The 15 client targets in `compat/required-mods.json` hook rendering directly
(Embeddium, Oculus, Entity Culling, ImmediatelyFast, Oculus Extra, Dynamic FPS).
`cluster_check.py` and `player_state_check.py` can prove that a jar loads, but they
cannot prove that a frame is drawn, that a shader pack compiles, or that a mixin into
`LevelRenderer` still applies. A production-client run has launched all 15 targets under
software GL (6 FUNCTIONAL, 9 PARTIAL; see
[`REQUIRED_MOD_COMPATIBILITY.md`](REQUIRED_MOD_COMPATIBILITY.md)); the PARTIAL rows need a
hardware GPU to finish.

The GPU is the only part a container without `/dev/dri` cannot fake: shader
compilation needs a real GL context. Everything around it - pinned artifacts, instance
layout, launch, log judgement and evidence - is automated by
[`tools/compat/client_matrix.py`](../tools/compat/client_matrix.py). A run is boring on
purpose so that a rented GPU VM is enough to finish the matrix.

## Machine specification

| Item | Value | Why |
| --- | --- | --- |
| OS | Ubuntu 22.04 or 24.04 LTS, x86_64 | `client_matrix.py host` checks `ID=ubuntu`; Debian reports `WARN` but works |
| GPU | anything exposing OpenGL 3.2 core or newer | 1.20.1 refuses to start below 3.2 |
| Driver | NVIDIA proprietary >= 535 (not nouveau), Mesa >= 23 for AMD/Intel | nouveau lacks the GL 4.5 path Oculus needs |
| vCPU / RAM | 4 vCPU / 16 GiB (8 GiB works for launch checks) | client `MaxMemAlloc` is 4 GiB, assets and the OS need the rest |
| Disk | 40 GiB free | assets ~1 GiB, instance + mods ~1 GiB, logs and screenshots |
| Java | Temurin 17.0.21 | same JDK as the pinned server set |
| Launcher | Prism Launcher 8+, or none | `prepare` writes Prism `mmc-pack.json`/`instance.cfg`; `tools/compat/launch_client.py` runs the same instance from the official version JSONs |
| Network | outbound HTTPS only (Modrinth CDN, Mojang, Forge maven) | no inbound port is needed |
| Display | a desktop session, or `Xvfb` + `xvfb-run` | `run` falls back to `xvfb-run -a -s "-screen 0 1280x720x24"` |

A GPU passthrough VM works; so does a normal cloud GPU instance (T4, A10, L4, RTX
4000-class). Virtualised GPUs (`virgl`/`vmware`) are reported as `VIRTUAL`: they can
run the launch checks, but a driver bug there is not a mod incompatibility.

### Turning a fresh Ubuntu box into a matrix runner

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jre-headless mesa-utils xvfb \
                        libgl1-mesa-dri libx11-6 libxext6 libxrandr2 libxcursor1 \
                        libxi6 libxxf86vm1
# NVIDIA only: the proprietary driver, then reboot
sudo ubuntu-drivers install
# Prism Launcher (flatpak is fine, the CLI is the same)
flatpak install -y flathub org.prismlauncher.PrismLauncher
sudo ln -sf /var/lib/flatpak/exports/bin/org.prismlauncher.PrismLauncher /usr/local/bin/prismlauncher
```

Verify the machine before touching Minecraft - this is the first line of every
evidence file:

```bash
python3 tools/compat/client_matrix.py host --instance .runtime/client
```

`opengl` must be `PASS` with a `HARDWARE` renderer. `FAIL` with
`glxinfo missing (install mesa-utils)` or `no /dev/dri` means the machine will not
produce usable client evidence.

## One command on the GPU machine

The whole procedure below is wrapped in one script; run it from the repository root and
it aborts before starting Minecraft if the host cannot produce usable evidence:

```bash
./tools/compat/run_client_matrix.sh --java /path/to/java17
# software-only machine (evidence is labelled SOFTWARE, shader rows stay open):
./tools/compat/run_client_matrix.sh --allow-software
```

It probes the host, builds the mod jar, downloads and hash-verifies the pinned client
artifacts, assembles the instance, installs the production client, then runs one client
per shader pack (`bsl-shaders`, `complementary-reimagined`, `sildurs-vibrant-lite`) and
writes one `compat/evidence/client-matrix-<utc>.{md,json}` per run. `--dry-run` prints the
commands without executing them.

## One matrix run

```bash
# 1. pin and download the client artifacts (SHA-512 verified)
python3 tools/compat/resolve_required_mods.py --write --download-client .runtime/client-mods

# 2. build the MultiForge client jar
./gradlew :multiforge-forge:build

# 3. assemble the Prism instance from the manifest (verifies every hash)
python3 tools/compat/client_matrix.py prepare \
    --instance .runtime/client \
    --source .runtime/client-mods \
    --multiforge-jar multiforge-forge/build/libs/multiforge-<version>.jar

# 4. launch it, wait for the main menu (or a crash), judge the log, write evidence
python3 tools/compat/client_matrix.py run --instance .runtime/client \
    --shader bsl-shaders --settle 180 --out compat/evidence
```

Without Prism, the same run works from the official version JSONs (no GUI, no account):

```bash
python3 tools/compat/launch_client.py install --launcher-dir .runtime/launcher
python3 tools/compat/client_matrix.py run --instance .runtime/client \
    --shader bsl-shaders --settle 180 \
    --command 'python3 /workspace/tools/compat/launch_client.py run \
        --launcher-dir /workspace/.runtime/launcher --game-dir {game_dir} \
        --assets-dir /home/node/.gradle/caches/forge_gradle/assets \
        --java /workspace/.jdk/bin/java --memory-mb 3072'
```

`prepare` refuses to launch a corrupted download: a SHA-512 mismatch removes the file
and fails that target. `run` selects the pack by writing `shaderPack=` (and
`enableShaders=true`) into `config/oculus.properties`, the file Oculus 1.8 reads and
writes itself, plus the `shaderPack:` key in `options.txt` for Iris-style installs. That
makes the active pack reproducible instead of a GUI click. It then keeps the client alive
for `--settle` seconds after the menu marker while the pack compiles, terminates it, and
moves the previous run's logs to `logs-previous-<utc>/` so a stale `latest.log` can never
be attributed to the new run.

How the verdicts are derived, in order of strength:

1. `Found valid mod file <jar> with {<modids>} mods` (debug log) - the mod jar was
   accepted by Forge. A mod jar that is installed but absent from this line never loaded.
2. `Registering mixin config: <config>` (debug log) - the mod's mixins were applied; the
   configs are read from `mods.toml` (`[[mixins]] config=`) and `META-INF/MANIFEST.MF`
   (`MixinConfigs:`), so this also works for Embeddedium/Oculus which only use the manifest.
3. `Mixin apply failed <config>` - the target is `FAILED` even if the client still starts.
4. Shader packs: `VERIFIED` only when the pack is the one selected in
   `config/oculus.properties`, Oculus reports that shaders are enabled (or names the pack)
   and no shader compile error was logged. An installed but unselected pack, or a
   `Shaders are disabled because no valid shaderpack is selected` message, is `INSTALLED`.

The analysis prefers `logs/debug.log` because Forge logs (1) and (2) only at DEBUG level;
`logs/latest.log` would make every mod look merely `INSTALLED`.

A run is only good evidence when all of these hold, and `run` exits 0 only then:

* `host` shows `opengl PASS ... HARDWARE` and `java PASS`;
* every client target is `VERIFIED` in the `prepare` table;
* the log analysis verdict is `STARTED` (menu reached, no crash);
* no missing dependency is reported;
* the per-target table shows `LOADED` for every mod and `VERIFIED` for the active pack.

## Shader packs

Oculus compiles one pack at a time; BSL, Complementary Reimagined and Sildur's Lite
must be judged in separate runs so a compile error is attributed to the right pack:

```bash
python3 tools/compat/client_matrix.py run --instance .runtime/client \
    --shader bsl-shaders --out compat/evidence
python3 tools/compat/client_matrix.py run --instance .runtime/client \
    --shader complementary-reimagined --out compat/evidence
python3 tools/compat/client_matrix.py run --instance .runtime/client \
    --shader sildurs-vibrant-lite --out compat/evidence
```

Selecting the active pack is a launcher action (Prism: *Shader Packs* in the instance
settings). `--shader <key>` only tells the harness which pack the run is about; a pack
that is installed but not selected is reported as `INSTALLED`, never as `VERIFIED`.

## Software OpenGL (no GPU)

`LIBGL_ALWAYS_SOFTWARE=1` (Mesa `llvmpipe`) can run the client far enough to prove that
mixins apply and the mod list loads. The harness labels such a machine `SOFTWARE` and
its evidence must say so: launch and log checks are valid, shader-pack results are not.
Never copy a `SOFTWARE` run into the `FULLY COMPATIBLE` column.

```bash
LIBGL_ALWAYS_SOFTWARE=1 python3 tools/compat/client_matrix.py run \
    --instance .runtime/client --timeout 1800 --out compat/evidence
```

## Recording the result

1. Open the generated `compat/evidence/client-matrix-<utc>.md`; it contains the host
table, the instance table, the launch command and the per-target log verdicts.
2. Update the client rows of [`REQUIRED_MOD_COMPATIBILITY.md`](REQUIRED_MOD_COMPATIBILITY.md)
   with the status the harness printed and link the evidence file.
3. Update the scoreboard in that file and the status line in `README.md`. Only a
   `HARDWARE` run with `STARTED` and no `FAILED` row may move a client target to
   `FULLY COMPATIBLE`.

## Development client run

For a quick client-side smoke test of the mod itself (no compatibility set):

```bash
./gradlew :multiforge-forge:runClient        # GPU machine or desktop session
LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a \
    ./gradlew :multiforge-forge:runClient     # headless, software GL
```

The run directory is `multiforge-forge/run-client/`; it is git-ignored like `run/`.
This does not replace the matrix above: it has no Embeddium/Oculus and therefore
cannot judge the client targets. Production mods must not be dropped into a dev run -
their mixins fail with `@Shadow ... No refMap loaded` because the dev workspace uses
official names while the jars are SRG-named. Use the matrix instance for that.

## Recorded run

`compat/evidence/client-matrix-20261122T080046Z.md` is a fresh run of this runner on the
current tree: Debian container, no `/dev/dri`, Xvfb `:99`, Mesa llvmpipe, production Forge
47.4.29. It reached the menu (`Verdict: STARTED`), loaded all client mods, selected
`BSL_v10.1.8.zip` (`VERIFIED`, no shader compile error) and recorded the build/console logs.
It is labelled software GL, so the PARTIAL rows stay open until a hardware-GPU run; the file
lists exactly what each row still needs. An earlier software run is
`client-matrix-20261122T052900Z.md`.
