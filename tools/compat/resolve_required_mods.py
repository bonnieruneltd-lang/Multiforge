#!/usr/bin/env python3
"""Resolve, pin and optionally download the required MultiForge compatibility targets.

MultiForge must be tested against an exact, reproducible set of client and server
software.  This script is the single source of truth for that set:

* every entry of the required compatibility list is declared below with the exact
  Modrinth slug, its role (client/server/both) and its distribution kind
  (mod, resource pack, shader pack or data pack);
* the script asks the Modrinth API for the newest release that supports
  Minecraft 1.20.1 and, for mods, the Forge loader;
* required dependencies are resolved and pinned to the same Minecraft version;
* the result is written to ``compat/required-mods.json`` with file names,
  download URLs and SHA-512 hashes so a later run can verify the exact bytes;
* ``--download DIR`` downloads only the artifacts needed on a dedicated server
  (``role`` server/both, kind mod or data pack) and verifies every hash.
  Client-side artifacts are downloaded with ``--download-client``.

The script needs no API key; it only uses public Modrinth endpoints.

Usage::

    python3 tools/compat/resolve_required_mods.py --write
    python3 tools/compat/resolve_required_mods.py --write --download .runtime/server-mods
    python3 tools/compat/resolve_required_mods.py --write --download-client .runtime/client-mods

Exit codes: 0 success, 1 a target could not be resolved, 2 download/hash error.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

API = "https://api.modrinth.com/v2"
GAME_VERSION = "1.20.1"
FORGE = "forge"
USER_AGENT = "MultiForge-compat-resolver/1.0 (https://github.com/bonnieruneltd-lang/Multiforge)"


@dataclass(frozen=True)
class Target:
    """One entry of the required compatibility list."""

    key: str
    name: str
    role: str          # client | server | both
    kind: str          # mod | resourcepack | shader | datapack
    slug: str
    note: str = ""
    file_contains: str = ""
    dependencies: tuple[str, ...] = field(default_factory=tuple)


# Declaration order matches docs/REQUIRED_MOD_COMPATIBILITY.md.
CLIENT_TARGETS = [
    Target("anti-cords-leaker", "Anti Cords Leaker", "client", "resourcepack", "anti-cords-leaker"),
    Target("badoptimizations", "BadOptimizations", "client", "mod", "badoptimizations"),
    Target("bsl-shaders", "BSL Shaders", "client", "shader", "bsl-shaders"),
    Target("complementary-reimagined", "Complementary Shaders - Reimagined", "client", "shader",
           "complementary-reimagined"),
    Target("cull-leaves", "Cull Leaves", "client", "mod", "cull-leaves"),
    Target("dynamic-fps", "Dynamic FPS", "client", "mod", "dynamic-fps"),
    Target("embeddium", "Embeddium", "client", "mod", "embeddium"),
    Target("entityculling", "Entity Culling", "client", "mod", "entityculling"),
    Target("immediatelyfast", "ImmediatelyFast", "client", "mod", "immediatelyfast"),
    Target("kerria", "Kerria", "client", "mod", "kerria-opt"),
    Target("obe", "Optimized Block Entities [OBE]", "client", "mod", "obe"),
    Target("oculus", "Oculus", "client", "mod", "oculus"),
    Target("rrls", "Remove Reloading Screen", "client", "mod", "rrls"),
    Target("embeddium-extra", "Embeddium (Rubidium) Extra", "client", "mod", "rubidium-extra"),
    Target("sildurs-vibrant-lite", "Sildur's Vibrant Shaders (Lite)", "client", "shader",
           "sildurs-vibrant-shaders", file_contains="Lite"),
]

SERVER_TARGETS = [
    Target("darcenos-minecarts", "Darceno's Minecarts", "server", "mod", "darcenos-minecarts",
           file_contains="darcenos-minecarts-1.3"),
    Target("just-player-heads", "Just Player Heads", "server", "mod", "just-player-heads",
           dependencies=("collective",)),
    Target("timber-strike", "Timber Strike", "server", "mod", "timber-strike",
           file_contains="timber-strike-1.3"),
    Target("lithostitched", "Lithostitched", "server", "mod", "lithostitched"),
    Target("tectonic", "Tectonic", "server", "mod", "tectonic", dependencies=("lithostitched",)),
    Target("fast-leaf-decay", "Fast Leaf Decay", "server", "mod", "fastleafdecay"),
    Target("ai-improvements", "AI Improvements", "server", "mod", "ai-improvements"),
    Target("async-locator", "Async Locator", "server", "mod", "async-locator"),
    Target("get-it-together-drops", "Get It Together, Drops!", "server", "mod", "get-it-together-drops"),
    Target("ksyxis", "Ksyxis", "server", "mod", "ksyxis"),
    Target("noisium", "Noisium", "server", "mod", "noisium"),
    Target("radium", "Radium", "server", "mod", "radium"),
    Target("servercore", "ServerCore", "server", "mod", "servercore"),
    Target("shinoyuki-betterautosave", "Shinoyuki-BetterAutoSave", "server", "mod",
           "shinoyuki-betterautosave"),
    Target("structure-layout-optimizer", "Structure Layout Optimizer", "server", "mod",
           "structure-layout-optimizer", dependencies=("resourceful-config",)),
    Target("antixray", "AntiXray", "server", "mod", "anti-xray"),
    Target("beautified-chat-server", "Beautified Chat [Server]", "server", "mod", "beautified-chat-server"),
    Target("combatlog", "Combatlog", "server", "mod", "combatlog"),
    Target("tpa-teleport", "TPA Teleport", "server", "mod", "tpa-teleport"),
    Target("ultimateantiseedcracker", "UltimateAntiSeedCracker", "server", "datapack",
           "ultimateantiseedcracker"),
]

DEPENDENCY_NAMES = {
    "collective": "Collective (required by Just Player Heads)",
    "resourceful-config": "Resourceful Config (required by Structure Layout Optimizer)",
}


def _get(path: str):
    request = urllib.request.Request(API + path, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=45) as response:
        return json.load(response)


def resolve_version(slug: str, kind: str, file_contains: str = "") -> dict:
    """Return the newest 1.20.1 Forge (or pack) release of ``slug``.

    Raises ``RuntimeError`` when no compatible release exists, so the matrix can
    never silently pin a Fabric or newer-Minecraft artifact.
    """
    query = urllib.parse.urlencode({"game_versions": json.dumps([GAME_VERSION])})
    versions = _get(f"/project/{slug}/version?{query}")
    candidates = []
    for version in versions:
        loaders = set(version["loaders"])
        if GAME_VERSION not in version["game_versions"]:
            continue
        if kind == "mod":
            if FORGE not in loaders:
                continue
        elif kind in ("resourcepack", "datapack"):
            if "minecraft" not in loaders and "datapack" not in loaders:
                continue
        elif kind == "shader":
            if not ({"iris", "optifine"} & loaders):
                continue
        if file_contains and not any(file_contains.lower() in f["filename"].lower()
                                     for f in version["files"]):
            continue
        candidates.append(version)
    if not candidates:
        raise RuntimeError(f"no {GAME_VERSION} {FORGE}/{kind} release for {slug}")
    candidates.sort(key=lambda v: (v["date_published"], v.get("version_number", "")), reverse=True)
    return candidates[0]


def pick_file(version: dict, file_contains: str) -> dict:
    files = version["files"]
    if file_contains:
        for file in files:
            if file_contains.lower() in file["filename"].lower():
                return file
    for file in files:
        if file.get("primary"):
            return file
    return files[0]


def artifact(slug: str, kind: str, file_contains: str = "") -> dict:
    version = resolve_version(slug, kind, file_contains)
    file = pick_file(version, file_contains)
    return {
        "project": slug,
        "version": version["version_number"],
        "name": version["name"],
        "game_versions": version["game_versions"],
        "loaders": version["loaders"],
        "environment": version["environment"],
        "filename": file["filename"],
        "url": file["url"],
        "size": file["size"],
        "sha512": file["hashes"]["sha512"],
        "dependencies": version.get("dependencies", []),
        "date_published": version["date_published"],
    }


def build_manifest() -> dict:
    """Resolve every target and its dependencies into a serialisable manifest."""
    entries = []
    dependencies = {}
    for target in CLIENT_TARGETS + SERVER_TARGETS:
        try:
            pinned = artifact(target.slug, target.kind, target.file_contains)
        except (RuntimeError, urllib.error.URLError) as error:
            entries.append({
                "key": target.key,
                "name": target.name,
                "role": target.role,
                "kind": target.kind,
                "slug": target.slug,
                "status": "UNRESOLVED",
                "error": str(error),
            })
            continue
        entries.append({
            "key": target.key,
            "name": target.name,
            "role": target.role,
            "kind": target.kind,
            "slug": target.slug,
            "status": "PINNED",
            "note": target.note,
            "artifact": pinned,
        })
        for dependency in target.dependencies:
            if dependency in dependencies:
                continue
            try:
                dependencies[dependency] = artifact(dependency, "mod")
            except (RuntimeError, urllib.error.URLError) as error:
                dependencies[dependency] = {"project": dependency, "error": str(error)}
    return {
        "generated_by": "tools/compat/resolve_required_mods.py",
        "minecraft": GAME_VERSION,
        "loader": "forge",
        "forge": "47.3.0",
        "java": 17,
        "targets": entries,
        "dependencies": dependencies,
        "dependency_names": DEPENDENCY_NAMES,
    }


def download(artifact_entry: dict, directory: Path) -> None:
    """Download one artifact and verify its pinned SHA-512 before publishing it."""
    directory.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(artifact_entry["url"], headers={"User-Agent": USER_AGENT})
    destination = directory / artifact_entry["filename"]
    if destination.exists():
        digest = hashlib.sha512(destination.read_bytes()).hexdigest()
        if digest == artifact_entry["sha512"]:
            print(f"present  {destination} (sha512 verified)")
            return
    with urllib.request.urlopen(request, timeout=300) as response:
        payload = response.read()
    digest = hashlib.sha512(payload).hexdigest()
    if digest != artifact_entry["sha512"]:
        raise RuntimeError(f"hash mismatch for {destination}: {digest}")
    tmp = destination.with_suffix(destination.suffix + ".part")
    tmp.write_bytes(payload)
    tmp.replace(destination)
    print(f"downloaded {destination} ({len(payload)} bytes, sha512 verified)")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--write", metavar="PATH", nargs="?", const="compat/required-mods.json",
                        help="write the pinned manifest (default: compat/required-mods.json)")
    parser.add_argument("--download", metavar="DIR",
                        help="download server-side mods and data packs into DIR")
    parser.add_argument("--download-client", metavar="DIR",
                        help="download client-side mods and packs into DIR")
    args = parser.parse_args(argv)

    manifest = build_manifest()
    unresolved = [t for t in manifest["targets"] if t["status"] != "PINNED"]
    if args.write:
        path = Path(args.write)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {path} ({len(manifest['targets'])} targets, {len(unresolved)} unresolved)")

    failures = 0
    if args.download:
        directory = Path(args.download)
        for target in manifest["targets"]:
            if target["status"] != "PINNED":
                continue
            if target["role"] not in ("server", "both"):
                continue
            if target["kind"] not in ("mod", "datapack"):
                continue
            try:
                download(target["artifact"], directory)
            except Exception as error:  # noqa: BLE001 - report and continue
                failures += 1
                print(f"ERROR {target['name']}: {error}", file=sys.stderr)
        for slug, dependency in manifest["dependencies"].items():
            if "url" not in dependency:
                failures += 1
                print(f"ERROR dependency {slug}: {dependency.get('error')}", file=sys.stderr)
                continue
            try:
                download(dependency, directory)
            except Exception as error:  # noqa: BLE001
                failures += 1
                print(f"ERROR dependency {slug}: {error}", file=sys.stderr)

    if args.download_client:
        directory = Path(args.download_client)
        for target in manifest["targets"]:
            if target["status"] != "PINNED" or target["role"] != "client":
                continue
            try:
                download(target["artifact"], directory)
            except Exception as error:  # noqa: BLE001
                failures += 1
                print(f"ERROR {target['name']}: {error}", file=sys.stderr)

    if unresolved:
        for target in unresolved:
            print(f"UNRESOLVED {target['name']} ({target['slug']}): {target['error']}",
                  file=sys.stderr)
        return 1
    return 2 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
