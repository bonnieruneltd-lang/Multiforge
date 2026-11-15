#!/usr/bin/env python3
"""Install and launch the pinned Forge client without a GUI launcher.

The client matrix needs a production Minecraft client (the pinned mods are built for
SRG names, so they cannot be dropped into a ForgeGradle dev run - their mixins fail
with ``@Shadow ... No refMap loaded``).  Prism Launcher is the recommended way to get
one, but a rented Ubuntu GPU box may not have a desktop at all.  This script does what
a launcher does, from the official version JSONs:

* ``install`` - download the vanilla version JSON for the pinned Minecraft version and
  run the Forge installer's ``--installClient`` into ``--launcher-dir``;
* ``run``     - resolve libraries and natives, build the JVM/game argument lists from
  the merged JSONs and ``exec`` the client (offline, username ``Dev``), so signals and
  exit codes belong to the game.

Usage::

    python3 tools/compat/launch_client.py install --launcher-dir .runtime/launcher
    python3 tools/compat/launch_client.py run --launcher-dir .runtime/launcher \\
        --game-dir .runtime/client/minecraft --assets-dir <assets> --print-only
    python3 tools/compat/launch_client.py run --launcher-dir .runtime/launcher \\
        --game-dir .runtime/client/minecraft

``--assets-dir`` is only required when the launcher directory has no ``assets/``; a
ForgeGradle cache (``~/.gradle/caches/forge_gradle/assets``) works because the asset
index id is the same as the official one.

Exit codes: the client's own code, or 2 for a usage/installation error.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge"
USER_AGENT = "MultiForge-client-matrix/1.0 (https://github.com/bonnieruneltd-lang/Multiforge)"
DEFAULT_MC = "1.20.1"
DEFAULT_FORGE = "47.4.29"

#: Launcher features this script never enables; used to evaluate rule predicates.
FEATURES = {
    "is_demo_user": False,
    "has_custom_resolution": False,
    "has_quick_plays_support": False,
    "is_quick_play_singleplayer": False,
    "is_quick_play_multiplayer": False,
    "is_quick_play_realms": False,
}


def fetch(url: str, destination: Path) -> None:
    """Download ``url`` to ``destination`` unless it already exists.

    Args:
        url: absolute http(s) URL.
        destination: target file; parent directories are created.

    Raises:
        OSError: on network or filesystem failure.
    """
    if destination.is_file() and destination.stat().st_size > 0:
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=300) as response, destination.open("wb") as out:
        shutil.copyfileobj(response, out)


def load_json(path: Path) -> dict:
    """Read a version JSON file.

    Args:
        path: file to read.

    Returns:
        The parsed document.

    Raises:
        SystemExit: when the file is missing or malformed.
    """
    try:
        return json.loads(path.read_text())
    except FileNotFoundError:
        sys.exit(f"error: {path} not found; run `launch_client.py install` first")
    except json.JSONDecodeError as exc:
        sys.exit(f"error: {path} is not valid JSON: {exc}")


def vanilla_version_url(mc: str) -> str:
    """Resolve the Mojang metadata URL of a Minecraft version.

    Args:
        mc: version id such as ``1.20.1``.

    Returns:
        Absolute URL of the version JSON.

    Raises:
        SystemExit: when the version is not in the manifest.
    """
    request = urllib.request.Request(VERSION_MANIFEST, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        manifest = json.loads(response.read())
    for entry in manifest.get("versions", []):
        if entry.get("id") == mc:
            return entry["url"]
    sys.exit(f"error: Minecraft {mc} is not in the Mojang version manifest")


def install(launcher_dir: Path, mc: str, forge: str, java: Path) -> Path:
    """Install the vanilla metadata and the Forge client into ``launcher_dir``.

    Args:
        launcher_dir: launcher root that will hold ``versions/`` and ``libraries/``.
        mc: Minecraft version (``1.20.1``).
        forge: Forge version without the Minecraft prefix (``47.4.29``).
        java: Java 17 executable used to run the Forge installer.

    Returns:
        The directory holding the installed Forge version JSON.

    Side effects:
        Downloads the vanilla JSON, the Forge installer and every library the
        installer decides it needs; writes ``launcher_profiles.json`` so the
        installer accepts the directory as a launcher root.
    """
    versions = launcher_dir / "versions"
    vanilla_json = versions / mc / f"{mc}.json"
    if not vanilla_json.is_file():
        fetch(vanilla_version_url(mc), vanilla_json)
    profiles = launcher_dir / "launcher_profiles.json"
    if not profiles.is_file():
        profiles.write_text('{"profiles": {}}\n')

    installer = launcher_dir / f"forge-{mc}-{forge}-installer.jar"
    fetch(f"{FORGE_MAVEN}/{mc}-{forge}/forge-{mc}-{forge}-installer.jar", installer)
    version_dir = versions / f"{mc}-forge-{forge}"
    if not (version_dir / f"{mc}-forge-{forge}.json").is_file():
        code = subprocess.call([str(java), "-jar", str(installer), "--installClient",
                                str(launcher_dir)])
        if code != 0:
            sys.exit(f"error: the Forge installer exited with {code}")
    return version_dir


def rules_allow(rules: list[dict] | None) -> bool:
    """Evaluate a Minecraft library/argument ``rules`` block for Linux.

    Args:
        rules: list of ``{action, os?, features?}`` entries, or ``None``.

    Returns:
        True when the entry applies to this machine.  An entry without rules always
        applies; a matching ``disallow`` wins over any ``allow``.
    """
    if not rules:
        return True
    allowed = False
    for rule in rules:
        os_name = (rule.get("os") or {}).get("name")
        matches = os_name in (None, "linux")
        features = rule.get("features") or {}
        for feature, required in features.items():
            if FEATURES.get(feature, False) != required:
                matches = False
        if not matches:
            continue
        if rule.get("action") == "disallow":
            return False
        allowed = True
    return allowed


def _find_in_gradle(relative: str, gradle_caches: Path) -> Path | None:
    """Find a library jar that ForgeGradle already downloaded.

    Args:
        relative: maven relative path (``group/artifact/version/file.jar``).
        gradle_caches: a Gradle user home ``caches`` directory.

    Returns:
        The first matching jar under ``modules-2/files-2.1`` or ``None``.  This keeps
        the script usable in an environment whose network only reaches the launcher
        maven and whose Gradle cache is already warm.
    """
    parts = Path(relative).parts
    if len(parts) < 4:
        return None
    filename = parts[-1]
    root = gradle_caches / "modules-2" / "files-2.1" / "/".join(parts[:-3]) / parts[-3] / parts[-2]
    if not root.is_dir():
        return None
    for candidate in sorted(root.glob(f"*/{filename}")):
        return candidate
    return None


def download_verified(url: str, sha1: str, destination: Path) -> Path:
    """Download a library into place and verify its SHA-1.

    Args:
        url: Mojang/Forge download URL from the version JSON.
        sha1: expected hex digest; an empty string skips verification.
        destination: file to create (``libraries/<maven path>``).

    Returns:
        ``destination``.

    Raises:
        SystemExit: when the download does not match the expected digest, so a
            corrupted library can never be launched.
    """
    fetch(url, destination)
    if sha1:
        digest = hashlib.sha1()
        with destination.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
        if digest.hexdigest() != sha1:
            destination.unlink(missing_ok=True)
            sys.exit(f"error: SHA-1 mismatch for {destination.name}")
    return destination


def materialise(entry: dict, launcher_dir: Path, gradle_caches: Path | None) -> Path | None:
    """Return the local jar for a ``downloads`` entry, fetching it when needed.

    Args:
        entry: ``{"path", "url", "sha1"}`` from a version JSON ``downloads`` block.
        launcher_dir: launcher root that holds ``libraries/``.
        gradle_caches: optional Gradle ``caches`` directory used as a fallback.

    Returns:
        Path of the jar, or ``None`` when the entry has no path.
    """
    relative = entry.get("path")
    if not relative:
        return None
    path = _library_path(relative, launcher_dir, gradle_caches)
    if path.is_file():
        return path
    if entry.get("url"):
        return download_verified(entry["url"], entry.get("sha1", ""), launcher_dir / "libraries" / relative)
    return path


def _library_path(relative: str, launcher_dir: Path, gradle_caches: Path | None) -> Path:
    """Resolve one library path, falling back to the Gradle cache.

    Args:
        relative: maven relative path.
        launcher_dir: launcher root containing ``libraries/``.
        gradle_caches: optional Gradle ``caches`` directory.

    Returns:
        Path of the jar to put on the classpath, or the launcher path when neither
        location has it (the client will then report the missing library itself).
    """
    downloaded = launcher_dir / "libraries" / relative
    if downloaded.is_file():
        return downloaded
    if gradle_caches:
        cached = _find_in_gradle(relative, gradle_caches)
        if cached:
            return cached
    return downloaded


def _expand_args(entries: list, values: dict[str, str]) -> list[str]:
    """Flatten a Minecraft ``arguments`` list and substitute placeholders.

    Args:
        entries: mix of strings and ``{rules, value}`` objects.
        values: placeholder map (``auth_player_name``, ``classpath``, ...).

    Returns:
        The argument list for this machine, with every ``${...}`` replaced.
    """
    out: list[str] = []
    for entry in entries:
        if isinstance(entry, str):
            out.append(entry)
        elif isinstance(entry, dict) and rules_allow(entry.get("rules")):
            value = entry.get("value")
            out.extend(value if isinstance(value, list) else [value])
    expanded: list[str] = []
    for arg in out:
        for key, value in values.items():
            arg = arg.replace("${" + key + "}", value)
        expanded.append(arg)
    return expanded


def build_command(launcher_dir: Path, mc: str, forge: str, game_dir: Path,
                  assets_dir: Path | None, java: Path, username: str = "Dev",
                  memory_mb: int = 4096, gradle_caches: Path | None = None,
                  extra_jvm: list[str] | None = None) -> list[str]:
    """Build the full client command line from the installed version JSONs.

    Args:
        launcher_dir: launcher root produced by :func:`install`.
        mc: Minecraft version.
        forge: Forge version without the Minecraft prefix.
        game_dir: ``--gameDir``; the client writes ``logs/latest.log`` below it.
        assets_dir: ``--assetsDir``; defaults to ``launcher_dir/assets``.
        java: Java 17 executable.
        username: offline player name.
        memory_mb: ``-Xmx`` value.
        gradle_caches: optional Gradle ``caches`` directory used as a library fallback.
        extra_jvm: additional JVM arguments (for example ``-DFORCE_GL_SOFTWARE=1``).

    Returns:
        Argv list ready for :func:`subprocess.run`.

    Raises:
        SystemExit: when the version JSONs or the assets directory are missing.
    """
    version_id = f"{mc}-forge-{forge}"
    versions = launcher_dir / "versions"
    vanilla = load_json(versions / mc / f"{mc}.json")
    forge_json = load_json(versions / version_id / f"{version_id}.json")
    assets_dir = assets_dir or (launcher_dir / "assets")
    if not assets_dir.is_dir():
        sys.exit(f"error: assets directory {assets_dir} does not exist "
                 "(pass --assets-dir, e.g. the ForgeGradle cache)")

    # The vanilla jar must not stay on the classpath next to Forge's patched client jar:
    # modlauncher names it `minecraft` while the raw jar would appear as a second module
    # exporting net.minecraft.server, which fails module resolution.  The installer writes
    # the patched jar but (47.4.29) does not list it in the version JSON, so prefer it here.
    patched_client = (launcher_dir / "libraries" / "net" / "minecraftforge" / "forge"
                      / f"{mc}-{forge}" / f"forge-{mc}-{forge}-client.jar")
    game_jar = patched_client if patched_client.is_file() else (versions / mc / f"{mc}.jar")
    classpath: list[str] = [str(game_jar)]
    natives_dir = launcher_dir / "natives" / version_id
    natives_dir.mkdir(parents=True, exist_ok=True)
    seen: set[str] = set()
    for library in vanilla.get("libraries", []) + forge_json.get("libraries", []):
        if not rules_allow(library.get("rules")):
            continue
        downloads = library.get("downloads") or {}
        artifact = materialise(downloads.get("artifact") or {}, launcher_dir, gradle_caches)
        if artifact and str(artifact) not in seen:
            seen.add(str(artifact))
            classpath.append(str(artifact))
        native = materialise((downloads.get("classifiers") or {}).get("natives-linux") or {},
                             launcher_dir, gradle_caches)
        if native and native.is_file():
            with zipfile.ZipFile(native) as archive:
                for name in archive.namelist():
                    if not name.startswith("META-INF/"):
                        archive.extract(name, natives_dir)

    values = {
        "auth_player_name": username,
        "version_name": version_id,
        "game_directory": str(game_dir),
        "assets_root": str(assets_dir),
        "assets_index_name": str((vanilla.get("assetIndex") or {}).get("id", "5")),
        "auth_uuid": "00000000000000000000000000000000",
        "auth_access_token": "0",
        "clientid": "0",
        "auth_xuid": "0",
        "user_type": "legacy",
        "version_type": "release",
        "natives_directory": str(natives_dir),
        "launcher_name": "MultiForge-client-matrix",
        "launcher_version": "1.0",
        "classpath": os.pathsep.join(classpath),
        "classpath_separator": os.pathsep,
        "library_directory": str(launcher_dir / "libraries"),
    }
    jvm_args = _expand_args((vanilla.get("arguments") or {}).get("jvm", []), values)
    jvm_args += _expand_args((forge_json.get("arguments") or {}).get("jvm", []), values)
    game_args = _expand_args((vanilla.get("arguments") or {}).get("game", []), values)
    game_args += _expand_args((forge_json.get("arguments") or {}).get("game", []), values)

    if not game_args:
        game_args = (vanilla.get("minecraftArguments") or "").split()
    argv = [str(java), f"-Xmx{memory_mb}M", *(extra_jvm or []), *jvm_args,
            forge_json.get("mainClass") or vanilla["mainClass"], *game_args]
    return argv


def main(argv: list[str] | None = None) -> int:
    """Command line entry point.

    Args:
        argv: arguments without ``sys.argv[0]``.

    Returns:
        The client exit code for ``run``, 0 for ``install``, 2 on usage errors.
    """
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--launcher-dir", type=Path, default=Path(".runtime/launcher"))
    common.add_argument("--mc", default=DEFAULT_MC)
    common.add_argument("--forge", default=DEFAULT_FORGE)
    common.add_argument("--java", default=os.environ.get("JAVA_HOME", "") + "/bin/java"
                        if os.environ.get("JAVA_HOME") else "java")
    sub = parser.add_subparsers(dest="action", required=True)

    sub.add_parser("install", parents=[common],
                   help="download the version JSONs and run the Forge installer")

    run_cmd = sub.add_parser("run", parents=[common], help="launch the installed client")
    run_cmd.add_argument("--game-dir", type=Path, default=Path(".runtime/client/minecraft"))
    run_cmd.add_argument("--assets-dir", type=Path, default=None)
    run_cmd.add_argument("--gradle-caches", type=Path, default=Path.home() / ".gradle" / "caches")
    run_cmd.add_argument("--username", default="Dev")
    run_cmd.add_argument("--memory-mb", type=int, default=4096)
    run_cmd.add_argument("--jvm-arg", action="append", default=[])
    run_cmd.add_argument("--print-only", action="store_true")

    args = parser.parse_args(argv)
    java = Path(args.java)
    if not java.is_file():
        found = shutil.which(args.java)
        if not found:
            sys.exit(f"error: no java at {args.java}; pass --java")
        java = Path(found)

    if args.action == "install":
        version_dir = install(args.launcher_dir, args.mc, args.forge, java)
        print(f"installed {version_dir}")
        return 0

    args.game_dir.mkdir(parents=True, exist_ok=True)
    launcher_dir = args.launcher_dir.resolve()
    assets_dir = args.assets_dir.resolve() if args.assets_dir else None
    command = build_command(launcher_dir, args.mc, args.forge, args.game_dir.resolve(),
                            assets_dir, java.resolve(), args.username, args.memory_mb,
                            args.gradle_caches, args.jvm_arg)
    if args.print_only:
        print(" ".join(command))
        return 0
    sys.stdout.flush()
    os.chdir(args.game_dir)
    os.execv(command[0], command)
    return 0


if __name__ == "__main__":
    sys.exit(main())
