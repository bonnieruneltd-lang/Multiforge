#!/usr/bin/env python3
"""Prepare, launch and judge the MultiForge client compatibility matrix.

The 15 client targets in ``compat/required-mods.json`` cannot be verified with the
server-only harness (``cluster_check.py``) or the headless bot
(``player_state_check.py``): they need a real Minecraft client because most of them
hook rendering (Embeddium, Oculus, Entity Culling, ImmediatelyFast, ...).  This
script automates everything around that client so a run on an Ubuntu machine with a
GPU is mechanical:

* ``host``     - probe the machine (OS, Java 17, RAM, disk, X display, OpenGL
                 renderer/version, hardware vs software GL, Prism Launcher) and print
                 a PASS/WARN/FAIL table.  Software GL is labelled explicitly so a
                 result is never quoted as a hardware result.
* ``prepare``  - build a Prism/MultiMC instance from the pinned manifest: copy or
                 download every client artifact into ``mods/``, ``resourcepacks/``
                 and ``shaderpacks/``, verify the SHA-512 from the manifest, and write
                 ``mmc-pack.json``/``instance.cfg`` plus a provenance file.
* ``analyze``  - parse a ``latest.log`` and decide, per target, whether it loaded, and
                 globally whether the client reached the main menu, crashed, hit a
                 mixin error, a missing dependency or a shader compile error.
* ``run``      - launch the prepared instance (Prism CLI, or a command given with
                 ``--command``) under ``xvfb-run`` when there is no display, wait for
                 the main menu or a crash, then analyze the log.
* ``evidence`` - write ``compat/evidence/client-matrix-<utc>.md`` and ``.json`` in the
                 same shape as the other evidence files, which is what
                 ``docs/REQUIRED_MOD_COMPATIBILITY.md`` quotes.
* ``self-test``- fixture based test of ``prepare``/``analyze`` that needs no Minecraft,
                 no GPU and no network.

Usage::

    python3 tools/compat/client_matrix.py host
    python3 tools/compat/client_matrix.py prepare --instance .runtime/client \\
        --source .runtime/client-mods
    python3 tools/compat/client_matrix.py run --instance .runtime/client \\
        --out compat/evidence
    python3 tools/compat/client_matrix.py analyze --log .runtime/client/minecraft/logs/latest.log
    python3 tools/compat/client_matrix.py self-test

Exit codes: 0 every check passed, 1 a check failed, 2 usage/IO error.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

MANIFEST = Path("compat/required-mods.json")
EVIDENCE_DIR = Path("compat/evidence")
USER_AGENT = "MultiForge-client-matrix/1.0 (https://github.com/bonnieruneltd-lang/Multiforge)"

#: Manifest distribution kinds mapped to the instance directory they live in.
KIND_DIRS = {"mod": "mods", "resourcepack": "resourcepacks", "shader": "shaderpacks"}

#: Lines Minecraft 1.20.1 prints once the client is rendering with a live GL context.
#: Any one is enough.  The blocks atlas is the reliable one on headless machines: it is
#: emitted after resource reload and shader compilation, while ``Sound engine started``
#: and the narrator line are missing when the machine has no sound device or no
#: text-to-speech library (exactly the case under ``xvfb-run``).
MENU_MARKERS = (
    "minecraft:textures/atlas/blocks.png-atlas",
    "Sound engine started",
    "Narrator library for x64 successfully loaded",
    "OpenAL initialized",
)

#: Lines that mean the run cannot be counted as a successful client start.
CRASH_MARKERS = (
    "---- Minecraft Crash Report ----",
    "A fatal error has been detected by the Java Runtime Environment",
    "Failed to load mods",
    'Exception in thread "main"',
    "Fatal error in mixin",
)

#: Mixin failures are recorded separately: they usually do not stop the client but
#: they invalidate the mod they belong to.
MIXIN_MARKERS = (
    "Mixin apply failed",
    "MixinApplyError",
    "mixin.injection.throwables",
    "InvalidInjectionException",
)

#: Oculus/Iris shader compile failures.
#: Oculus prints this when no pack is selected; the opposite of compile evidence.
SHADER_DISABLED_MARKERS = (
    "Shaders are disabled because no valid shaderpack is selected",
    "Shaders are disabled",
)

#: Oculus/Iris lines that prove a pack was actually loaded and compiled.
SHADER_LOAD_MARKERS = (
    "Using shaderpack",
    "Shaders are enabled",
    "Loading shaderpack",
    "Shaderpack loaded",
    "Successfully reloaded shaderpack",
)

SHADER_ERROR_MARKERS = (
    "shader compilation failed",
    "Failed to compile shader",
    "GLSL compile error",
    "SHADER ERROR",
    "Failed to link program",
)

FOUND_MOD_PATTERN = re.compile(r"Found mod file (\S+)")
#: Mixin writes this at DEBUG for every mixin config it accepts; it is the only
#: per-mod load signal a production Forge client emits (``Found mod file`` is dev only).
MIXIN_REGISTER_PATTERN = re.compile(r"Registering mixin config: (\S+)")
#: ``ModFileInfo`` prints this at DEBUG for every accepted mod jar in production.
VALID_MOD_PATTERN = re.compile(r"Found valid mod file (\S+) with")
MIXIN_FAIL_PATTERN = re.compile(r"Mixin apply failed (\S+)")
#: ``mods.toml`` entries used to map a jar to its mod id and mixin configs.
MODS_TOML_MODID_PATTERN = re.compile(r'^\s*modId\s*=\s*"([^"]+)"', re.MULTILINE)
MODS_TOML_MIXIN_PATTERN = re.compile(r'^\s*config\s*=\s*"([^"]+)"', re.MULTILINE)
FORGE_VERSION_PATTERN = re.compile(r"Forge mod loading, version ([\w.\-]+), for MC ([\w.\-]+)")
MISSING_DEP_PATTERN = re.compile(
    r"Mod ID: '([^']+)', Requested by: '([^']+)', Actual version: '\[MISSING\]'"
)
JAVA_MAJOR_PATTERN = re.compile(r'version "(\d+)(?:\.(\d+))?')
GL_RENDERER_PATTERN = re.compile(r"OpenGL renderer string:\s*(.+)")
GL_VERSION_PATTERN = re.compile(r"OpenGL (?:core profile )?version string:\s*([\d.]+)")

#: Renderers that are software rasterisers.  A run on these proves nothing about the
#: GPU path, so the harness labels its own evidence as SOFTWARE.
SOFTWARE_RENDERERS = ("llvmpipe", "softpipe", "swrast", "swiftshader")


# --------------------------------------------------------------------------- model


@dataclass
class Check:
    """One host/environment probe result.

    Attributes:
        name: short stable identifier used in the evidence table.
        status: ``PASS``, ``WARN`` or ``FAIL``.
        detail: human readable measurement that justifies the status.
    """

    name: str
    status: str
    detail: str


@dataclass
class HostReport:
    """Result of :func:`probe_host`.

    Attributes:
        checks: ordered probe results.
        facts: machine readable values (java major, GL renderer, GL version, ...) copied
            into the JSON evidence so a later run can be compared.
    """

    checks: list[Check] = field(default_factory=list)
    facts: dict = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        """True when no probe failed (warnings are allowed)."""
        return all(check.status != "FAIL" for check in self.checks)


@dataclass
class TargetStatus:
    """Per-target outcome after preparation and log analysis.

    Attributes:
        key: manifest key (``embeddium``, ``oculus``, ...).
        name: display name from the manifest.
        kind: ``mod``, ``resourcepack`` or ``shader``.
        status: ``VERIFIED``, ``LOADED``, ``INSTALLED``, ``FAILED`` or ``MISSING``.
        detail: why the status was chosen; always names the evidence used.
    """

    key: str
    name: str
    kind: str
    status: str
    detail: str


# ------------------------------------------------------------------------ manifest


def read_manifest(path: Path) -> dict:
    """Load the pinned compatibility manifest.

    Args:
        path: location of ``compat/required-mods.json``.

    Returns:
        The parsed manifest.

    Raises:
        SystemExit: when the file is missing or not valid JSON, because every other
            action depends on the pinned versions.
    """
    try:
        return json.loads(path.read_text())
    except FileNotFoundError:
        sys.exit(f"error: manifest not found at {path}; run "
                 "tools/compat/resolve_required_mods.py --write")
    except json.JSONDecodeError as exc:
        sys.exit(f"error: {path} is not valid JSON: {exc}")


def client_targets(manifest: dict) -> list[dict]:
    """Return the manifest targets that must be installed on the client.

    Args:
        manifest: parsed ``required-mods.json``.

    Returns:
        Targets whose role is ``client`` (or ``both``), in declaration order.
    """
    return [t for t in manifest.get("targets", []) if t.get("role") in ("client", "both")]


def client_prerequisites(manifest: dict) -> list[dict]:
    """Return the resolved dependencies that belong in ``mods/``.

    Args:
        manifest: parsed ``required-mods.json``.

    Returns:
        Dependency artifacts (Collective, Resourceful Config, ...).  They are mods
        without their own compatibility row, but the client will not start without
        them, so they are installed as well.
    """
    return list(manifest.get("dependencies", {}).values())


# ---------------------------------------------------------------------- host probe


def _run(cmd: list[str], timeout: int = 60, env: dict | None = None) -> tuple[int, str]:
    """Run a command and return ``(returncode, combined output)``.

    Args:
        cmd: argv to execute.
        timeout: seconds before the probe is abandoned.
        env: optional environment overrides.

    Returns:
        ``(127, "")`` when the binary does not exist, ``(124, output)`` on timeout.
    """
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                              env={**os.environ, **(env or {})})
        return proc.returncode, (proc.stdout or "") + (proc.stderr or "")
    except FileNotFoundError:
        return 127, ""
    except subprocess.TimeoutExpired as exc:
        return 124, (exc.stdout or "") if isinstance(exc.stdout, str) else ""


def find_java(explicit: str | None = None) -> str | None:
    """Locate a Java executable the way a launcher would.

    Args:
        explicit: value of ``--java``; wins when given and executable.

    Returns:
        Absolute path to a usable ``java`` binary or ``None``.
    """
    candidates: list[str] = []
    if explicit:
        candidates.append(explicit)
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidates.append(str(Path(java_home) / "bin" / "java"))
    candidates.append("/workspace/.jdk/bin/java")
    on_path = shutil.which("java")
    if on_path:
        candidates.append(on_path)
    for candidate in candidates:
        if candidate and os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
    return None


def gl_probe(display: str | None) -> tuple[str, str, str]:
    """Query the OpenGL renderer and version through GLX.

    Args:
        display: ``DISPLAY`` value, or ``None`` to run under ``xvfb-run``.

    Returns:
        ``(renderer, version, note)``; empty strings when no GL context is available.
        The note explains *why* a probe failed so the evidence is actionable.
    """
    glxinfo = shutil.which("glxinfo")
    if not glxinfo:
        return "", "", "glxinfo missing (install mesa-utils)"
    prefix: list[str] = []
    if not display:
        if not shutil.which("xvfb-run"):
            return "", "", "no DISPLAY and no xvfb-run"
        prefix = ["xvfb-run", "-a"]
    code, out = _run(prefix + [glxinfo, "-B"], timeout=60)
    if code != 0:
        return "", "", f"glxinfo exited {code}"
    renderer = GL_RENDERER_PATTERN.search(out)
    version = ""
    for match in GL_VERSION_PATTERN.finditer(out):
        if match.group(1) > version:
            version = match.group(1)
    note = "ok" if renderer else "glxinfo printed no renderer string"
    return (renderer.group(1).strip() if renderer else ""), version, note


def _gl_version_tuple(version: str) -> tuple[int, ...]:
    """Parse ``"4.5.0"`` into ``(4, 5, 0)``; ``()`` when unparsable."""
    parts: list[int] = []
    for part in version.split("."):
        if not part.isdigit():
            return tuple(parts)
        parts.append(int(part))
    return tuple(parts)


def probe_host(manifest: dict, instance: Path, java: str | None = None) -> HostReport:
    """Collect every prerequisite the client matrix needs.

    Args:
        manifest: parsed manifest; supplies the expected Java major version.
        instance: instance root whose filesystem is checked for free space.
        java: optional explicit Java executable.

    Returns:
        A :class:`HostReport`.  The probe never raises: a missing tool becomes a
        ``FAIL`` or ``WARN`` row, so one report describes the whole machine.
    """
    report = HostReport()

    release: dict[str, str] = {}
    try:
        for line in Path("/etc/os-release").read_text().splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                release[key] = value.strip('"')
    except FileNotFoundError:
        pass
    distro = release.get("PRETTY_NAME", platform.platform())
    if release.get("ID") == "ubuntu":
        report.checks.append(Check("os", "PASS", distro))
    else:
        report.checks.append(Check("os", "WARN",
                                   f"{distro} (docs/CLIENT_MATRIX.md documents Ubuntu 22.04/24.04)"))
    report.facts["os"] = distro

    java_path = find_java(java)
    expected_java = int(manifest.get("java", 17))
    if not java_path:
        report.checks.append(Check("java", "FAIL", f"no java found (need {expected_java})"))
    else:
        _, out = _run([java_path, "-version"], timeout=30)
        match = JAVA_MAJOR_PATTERN.search(out)
        major = int(match.group(1)) if match else 0
        vendor = out.splitlines()[0].strip() if out else "unknown"
        if major == expected_java:
            report.checks.append(Check("java", "PASS", f"{java_path} ({vendor})"))
        else:
            report.checks.append(Check("java", "FAIL",
                                       f"{java_path} reports major {major}, need {expected_java}"))
        report.facts["java"] = {"path": java_path, "major": major, "vendor": vendor}

    mem_total_kib = 0
    try:
        for line in Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemTotal:"):
                mem_total_kib = int(line.split()[1])
    except FileNotFoundError:
        pass
    mem_gib = mem_total_kib / (1024 * 1024)
    status = "PASS" if mem_gib >= 8 else ("WARN" if mem_gib >= 4 else "FAIL")
    report.checks.append(Check("memory", status, f"{mem_gib:.1f} GiB total (8 GiB recommended)"))
    report.facts["memory_gib"] = round(mem_gib, 1)

    anchor = instance if instance.exists() else Path.cwd()
    free_gib = shutil.disk_usage(anchor).free / (1024 ** 3)
    status = "PASS" if free_gib >= 10 else ("WARN" if free_gib >= 5 else "FAIL")
    report.checks.append(Check("disk", status, f"{free_gib:.1f} GiB free at {anchor} (10 GiB recommended)"))
    report.facts["disk_free_gib"] = round(free_gib, 1)

    display = os.environ.get("DISPLAY") or None
    if display:
        code, _ = _run(["xdpyinfo"], timeout=30)
        if code == 0:
            report.checks.append(Check("display", "PASS", f"DISPLAY={display} reachable"))
        else:
            report.checks.append(Check("display", "WARN",
                                       f"DISPLAY={display} set but xdpyinfo failed; run under xvfb-run"))
            display = None
    else:
        status = "PASS" if shutil.which("xvfb-run") else "FAIL"
        report.checks.append(Check("display", status,
                                   "no DISPLAY; xvfb-run available" if status == "PASS"
                                   else "no DISPLAY and no xvfb-run"))
    report.facts["display"] = display or "xvfb"

    dri = sorted(p.name for p in Path("/dev/dri").glob("*")) if Path("/dev/dri").is_dir() else []
    if dri:
        report.checks.append(Check("gpu-device", "PASS", "/dev/dri: " + ", ".join(dri)))
    else:
        report.checks.append(Check("gpu-device", "FAIL",
                                   "no /dev/dri; no GPU device (shader compilation needs a real GL context)"))
    report.facts["dri"] = dri

    renderer, version, note = gl_probe(display)
    if not renderer:
        report.checks.append(Check("opengl", "FAIL", f"no usable GLX renderer: {note}"))
        report.facts["gl"] = {"renderer": "", "version": "", "class": "NONE"}
    else:
        lowered = renderer.lower()
        if any(software in lowered for software in SOFTWARE_RENDERERS):
            gl_class = "SOFTWARE"
            status = "WARN"
            detail = (f"{renderer} (gl {version}) is a software rasteriser: launch and log "
                      "checks are valid, shader packs are not")
        elif "virgl" in lowered or "virtio" in lowered or "vmware" in lowered:
            gl_class = "VIRTUAL"
            status = "WARN"
            detail = f"{renderer} (gl {version}) is a virtualised GPU"
        else:
            gl_class = "HARDWARE"
            status = "PASS"
            detail = f"{renderer} (gl {version})"
        if gl_class != "SOFTWARE" and _gl_version_tuple(version)[:2] < (3, 2):
            status = "FAIL"
            detail += "; Minecraft 1.20.1 needs OpenGL 3.2 core"
        report.checks.append(Check("opengl", status, detail))
        report.facts["gl"] = {"renderer": renderer, "version": version, "class": gl_class}
    return report


# ---------------------------------------------------------------- instance assembly


def sha512(path: Path) -> str:
    """Return the hex SHA-512 of ``path`` (streamed, 1 MiB chunks).

    Args:
        path: file to hash.

    Returns:
        Lowercase hexadecimal digest, matching the manifest encoding.
    """
    digest = hashlib.sha512()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _artifact_entries(manifest: dict) -> list[dict]:
    """Flatten the client targets and their dependencies into artifact entries.

    Args:
        manifest: parsed manifest.

    Returns:
        One dict per file with ``key``, ``name``, ``kind``, ``filename``, ``sha512``,
        ``url`` and ``dependency`` (True for prerequisite mods).
    """
    entries: list[dict] = []
    for target in client_targets(manifest):
        artifact = target.get("artifact") or {}
        if not artifact.get("filename"):
            continue
        entries.append({
            "key": target["key"],
            "name": target["name"],
            "kind": target["kind"],
            "filename": artifact["filename"],
            "sha512": artifact.get("sha512", ""),
            "url": artifact.get("url", ""),
            "dependency": False,
        })
    for key, artifact in manifest.get("dependencies", {}).items():
        entries.append({
            "key": key,
            "name": manifest.get("dependency_names", {}).get(key, key),
            "kind": "mod",
            "filename": artifact["filename"],
            "sha512": artifact.get("sha512", ""),
            "url": artifact.get("url", ""),
            "dependency": True,
        })
    return entries


def _download(url: str, destination: Path) -> None:
    """Download ``url`` to ``destination`` through a descriptive User-Agent.

    Args:
        url: Modrinth CDN URL from the manifest.
        destination: file to create; parents must exist.

    Raises:
        OSError: on HTTP or filesystem failure (the caller records it per target).
    """
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=120) as response, destination.open("wb") as out:
        shutil.copyfileobj(response, out)


def write_instance_metadata(instance: Path, manifest: dict, java: str | None,
                            memory_mb: int = 4096) -> None:
    """Write the Prism/MultiMC files that describe the pinned client.

    Args:
        instance: instance root.
        manifest: parsed manifest (Minecraft and Forge versions).
        java: Java executable to pin in ``instance.cfg``; omitted when ``None``.
        memory_mb: ``MaxMemAlloc`` for the instance.

    Side effects:
        Creates ``mmc-pack.json`` and ``instance.cfg`` at ``instance``.
    """
    forge = str(manifest.get("forge", "")).split("-")[-1]
    pack = {
        "formatVersion": 1,
        "components": [
            {"uid": "net.minecraft", "version": manifest.get("minecraft", "1.20.1"),
             "important": True},
            {"uid": "net.minecraftforge", "version": forge},
        ],
    }
    (instance / "mmc-pack.json").write_text(json.dumps(pack, indent=2) + "\n")
    lines = [
        "InstanceType=OneSix",
        "name=MultiForge client matrix",
        "OverrideMemory=true",
        f"MaxMemAlloc={memory_mb}",
        "MinMemAlloc=1024",
        "OverrideJavaArgs=true",
        "JvmArgs=-Dmixin.env.remapRefMap=true",
    ]
    if java:
        lines.append(f"JavaPath={java}")
    (instance / "instance.cfg").write_text("\n".join(lines) + "\n")


@dataclass
class PrepareResult:
    """Outcome of :func:`prepare_instance`.

    Attributes:
        statuses: one :class:`TargetStatus` per client target.
        files: every artifact that was placed, with its verified hash.
        game_dir: directory that holds ``mods/``, ``resourcepacks/`` and ``shaderpacks/``.
        errors: problems that made an artifact unusable.
    """

    statuses: list[TargetStatus] = field(default_factory=list)
    files: list[dict] = field(default_factory=list)
    game_dir: Path | None = None
    errors: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        """True when every client target was verified."""
        return not self.errors and all(s.status == "VERIFIED" for s in self.statuses)


def prepare_instance(instance: Path, source: Path | None, manifest: dict,
                     java: str | None = None, download: bool = True,
                     multiforge_jar: Path | None = None,
                     memory_mb: int = 4096) -> PrepareResult:
    """Assemble the pinned client instance next to the manifest.

    Args:
        instance: instance root; the game directory becomes ``instance/minecraft``.
        source: optional directory that already holds the artifacts (the output of
            ``resolve_required_mods.py --download-client``).
        manifest: parsed manifest.
        java: Java executable pinned in ``instance.cfg``.
        download: when ``True``, artifacts absent from ``source`` are fetched from the
            manifest URL; when ``False`` they are reported as ``MISSING``.
        multiforge_jar: the built MultiForge mod jar to add to ``mods/``.
        memory_mb: instance memory allocation.

    Returns:
        A :class:`PrepareResult`.  A hash mismatch fails the target and keeps the file
        out of the game directory, so a corrupted download is never launched.

    Side effects:
        Creates/modifies the instance tree and writes the provenance file
        ``instance/multiforge-client-matrix.txt``.
    """
    result = PrepareResult()
    game_dir = instance / "minecraft"
    for sub in ("mods", "resourcepacks", "shaderpacks", "config"):
        (game_dir / sub).mkdir(parents=True, exist_ok=True)
    result.game_dir = game_dir
    write_instance_metadata(instance, manifest, java, memory_mb)

    for entry in _artifact_entries(manifest):
        destination = game_dir / KIND_DIRS.get(entry["kind"], "mods") / entry["filename"]
        candidates = []
        if source:
            candidates.append(source / entry["filename"])
        candidates.append(destination)
        found = next((c for c in candidates if c.is_file()), None)
        try:
            if found is None and download and entry["url"]:
                _download(entry["url"], destination)
                found = destination
            if found is None:
                result.errors.append(f"{entry['key']}: {entry['filename']} is not available "
                                     "(no source copy, download disabled)")
                result.statuses.append(TargetStatus(entry["key"], entry["name"], entry["kind"],
                                                    "MISSING", f"{entry['filename']} absent"))
                continue
            if found != destination:
                shutil.copyfile(found, destination)
            actual = sha512(destination)
            if entry["sha512"] and actual != entry["sha512"]:
                destination.unlink(missing_ok=True)
                result.errors.append(f"{entry['key']}: SHA-512 mismatch for {entry['filename']} "
                                     f"(manifest {entry['sha512'][:16]}..., file {actual[:16]}...)")
                result.statuses.append(TargetStatus(entry["key"], entry["name"], entry["kind"],
                                                    "FAILED", "SHA-512 mismatch; file removed"))
                continue
            result.files.append({"key": entry["key"], "filename": entry["filename"],
                                 "kind": entry["kind"], "sha512": actual,
                                 "dependency": entry["dependency"]})
            result.statuses.append(TargetStatus(
                entry["key"], entry["name"], entry["kind"], "VERIFIED",
                f"{entry['filename']} sha512={actual[:16]}..."))
        except OSError as exc:
            result.errors.append(f"{entry['key']}: {exc}")
            result.statuses.append(TargetStatus(entry["key"], entry["name"], entry["kind"],
                                                "FAILED", f"transfer failed: {exc}"))

    if multiforge_jar and multiforge_jar.is_file():
        target = game_dir / "mods" / multiforge_jar.name
        shutil.copyfile(multiforge_jar, target)
        result.files.append({"key": "multiforge", "filename": target.name, "kind": "mod",
                             "sha512": sha512(target), "dependency": False})

    provenance = instance / "multiforge-client-matrix.txt"
    lines = [f"manifest: {manifest.get('generated_by', '?')}",
             f"minecraft: {manifest.get('minecraft')} forge: {manifest.get('forge')}",
             "generated: " + dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")]
    lines += [f"{item['kind']:13} {item['sha512']}  {item['filename']}" for item in result.files]
    provenance.write_text("\n".join(lines) + "\n")
    return result


# --------------------------------------------------------------------- log analysis


def active_shader(game_dir: Path) -> str:
    """Return the shader pack Oculus has selected.

    Args:
        game_dir: instance game directory.

    Returns:
        The pack file name (or ``OFF``/empty).  Oculus 1.8 keeps the selection in
        ``config/oculus.properties`` (``shaderPack=``); Iris-style launchers may keep
        ``shaderPack:`` in ``options.txt``, which is used as a fallback.
    """
    properties = game_dir / "config" / "oculus.properties"
    if properties.is_file():
        for line in properties.read_text(errors="replace").splitlines():
            if line.startswith("shaderPack="):
                return line.split("=", 1)[1].strip()
    options = game_dir / "options.txt"
    if options.is_file():
        for line in options.read_text(errors="replace").splitlines():
            if line.startswith("shaderPack:"):
                return line.split(":", 1)[1].strip()
    return ""


def set_active_shader(game_dir: Path, pack: str) -> None:
    """Select ``pack`` so the next launch actually uses it.

    Args:
        game_dir: instance game directory.
        pack: shader pack file name (``BSL_v10.1.8.zip``) or ``OFF``.

    Side effects:
        Upserts ``shaderPack`` and ``enableShaders`` in ``config/oculus.properties``
        (the file Oculus 1.8 writes itself) and keeps the ``options.txt`` key in sync
        for Iris-style installs.  This makes a shader run reproducible instead of
        depending on a GUI click, and it is the only way a headless run can select a
        pack at all.
    """
    properties = game_dir / "config" / "oculus.properties"
    properties.parent.mkdir(parents=True, exist_ok=True)
    lines = properties.read_text(errors="replace").splitlines() if properties.is_file() else []
    lines = [line for line in lines if not line.startswith(("shaderPack=", "enableShaders="))]
    lines += [f"shaderPack={pack}", "enableShaders=true"]
    properties.write_text("\n".join(lines) + "\n")

    options = game_dir / "options.txt"
    option_lines = options.read_text(errors="replace").splitlines() if options.is_file() else []
    option_lines = [line for line in option_lines if not line.startswith("shaderPack:")]
    option_lines.append(f"shaderPack:{pack}")
    options.write_text("\n".join(option_lines) + "\n")


def default_log(game_dir: Path) -> Path:
    """Pick the richest log a client run left behind.

    Args:
        game_dir: instance game directory.

    Returns:
        ``logs/debug.log`` when it exists, because a production Forge client logs the
        Mixin ``Registering mixin config`` lines only at DEBUG level; ``logs/latest.log``
        otherwise.  Without it every mod would look merely ``INSTALLED``.
    """
    debug = game_dir / "logs" / "debug.log"
    return debug if debug.is_file() else game_dir / "logs" / "latest.log"


def _manifest_attrs(manifest: str, key: str) -> list[str]:
    """Read one attribute out of a JAR manifest, unfolding continuation lines.

    Args:
        manifest: raw ``META-INF/MANIFEST.MF`` text.
        key: attribute name, matched case-insensitively (``MixinConfigs``).

    Returns:
        Every value of that attribute.  Manifests wrap long values at 72 bytes with a
        leading space, and several mods (Embeddium, Oculus, OBE, Entity Culling, ...)
        declare their mixin configs here instead of in ``mods.toml``.
    """
    unfolded: list[str] = []
    for line in manifest.splitlines():
        if line.startswith(" ") and unfolded:
            unfolded[-1] += line[1:]
        else:
            unfolded.append(line)
    values: list[str] = []
    for line in unfolded:
        name, _, value = line.partition(":")
        if name.strip().lower() == key.lower():
            values.append(value.strip())
    return values


def mod_metadata(jar: Path) -> dict:
    """Read the mod id and mixin configs a jar declares.

    Args:
        jar: a mod jar inside the instance's ``mods/`` directory.

    Returns:
        ``{"mod_id": str, "mixins": [str, ...]}``; empty values when the jar has no
        readable metadata.  Mixin configs come from both ``mods.toml`` (``[[mixins]]
        config=``) and the manifest (``MixinConfigs:``, comma separated).  This turns
        Mixin's debug log lines into a per-target verdict, because a production client
        never prints the dev-only ``Found mod file`` line.
    """
    metadata = {"mod_id": "", "mixins": []}
    try:
        archive = zipfile.ZipFile(jar)
    except (OSError, zipfile.BadZipFile):
        return metadata
    with archive:
        toml = ""
        try:
            toml = archive.read("META-INF/mods.toml").decode("utf-8", errors="replace")
        except KeyError:
            pass
        manifest = ""
        try:
            manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
        except KeyError:
            pass
    mod_id = MODS_TOML_MODID_PATTERN.search(toml)
    if mod_id:
        metadata["mod_id"] = mod_id.group(1)
    configs = MODS_TOML_MIXIN_PATTERN.findall(toml)
    for value in _manifest_attrs(manifest, "MixinConfigs"):
        configs += [part.strip() for part in value.split(",") if part.strip()]
    metadata["mixins"] = configs
    return metadata


def analyze_log(log_path: Path, manifest: dict, game_dir: Path | None = None,
                shader: str | None = None) -> dict:
    """Judge one client run from its log file.

    Args:
        log_path: ``latest.log`` or (preferred) ``debug.log`` of the run.
        manifest: parsed manifest; supplies the 15 client targets.
        game_dir: instance game directory, used to tell "installed but never loaded"
            from "missing" when the log has no ``Found mod file`` line (early crash).
        shader: key of the shader pack that was active, so its compile errors are
            attributed instead of failing the whole run.

    Returns:
        Dict with ``reached_menu``, ``crash``, ``mixin_errors``, ``missing_deps``,
        ``shader_errors``, ``forge``, ``renderer``, ``statuses`` (list of dicts),
        ``verdict`` (``STARTED``/``CRASHED``/``PARTIAL``) and ``highlights`` (the log
        lines that justify the verdicts).
    """
    text = ""
    if log_path.is_file():
        with log_path.open("r", errors="replace") as handle:
            text = handle.read(8 * 1024 * 1024)
    lines = text.splitlines()
    lowered = text.lower()

    found_mods = {m.group(1) for line in lines for m in [FOUND_MOD_PATTERN.search(line)] if m}
    valid_mods = {m.group(1) for line in lines
                  for m in [VALID_MOD_PATTERN.search(line)] if m}
    registered_configs = {m.group(1) for line in lines
                          for m in [MIXIN_REGISTER_PATTERN.search(line)] if m}
    failed_configs = {m.group(1).split(":")[0] for line in lines
                      for m in [MIXIN_FAIL_PATTERN.search(line)] if m}
    forge_match = FORGE_VERSION_PATTERN.search(text)
    reached_menu = any(marker in text for marker in MENU_MARKERS)
    crash_lines = [line.strip() for line in lines if any(m in line for m in CRASH_MARKERS)]
    mixin_lines = [line.strip() for line in lines if any(m in line for m in MIXIN_MARKERS)]
    shader_lines = [line.strip() for line in lines if any(m in line for m in SHADER_ERROR_MARKERS)]
    shader_enabled = any(marker in text for marker in SHADER_LOAD_MARKERS)
    shader_disabled = any(marker in text for marker in SHADER_DISABLED_MARKERS)
    missing_deps = [f"{m.group(1)} (requested by {m.group(2)})"
                    for line in lines for m in [MISSING_DEP_PATTERN.search(line)] if m]
    renderer_match = next((match for match in (GL_RENDERER_PATTERN.search(line) for line in lines)
                           if match), None)

    statuses: list[TargetStatus] = []
    for target in client_targets(manifest):
        artifact = target.get("artifact") or {}
        filename = artifact.get("filename", "")
        kind = target["kind"]
        on_disk = bool(game_dir and filename and
                       (game_dir / KIND_DIRS.get(kind, "mods") / filename).is_file())
        if kind == "mod":
            jar = (game_dir / "mods" / filename) if game_dir and filename else None
            info = mod_metadata(jar) if jar and jar.is_file()                 else {"mod_id": "", "mixins": []}
            configs = set(info["mixins"])
            if configs & failed_configs:
                status, detail = "FAILED", ("mixin config "
                                            f"{sorted(configs & failed_configs)[0]} failed to apply")
            elif filename in found_mods:
                status, detail = "LOADED", f"Found mod file {filename}"
            elif filename in valid_mods:
                status, detail = "LOADED", f"Found valid mod file {filename}"
            elif configs & registered_configs:
                status, detail = "LOADED", ("mixin config "
                                            f"{sorted(configs & registered_configs)[0]} registered")
            elif info["mod_id"] and re.search("\\[" + re.escape(info["mod_id"]) + r"[\./]", text):
                status, detail = "LOADED", f"mod id {info['mod_id']} appears in a log line"
            elif on_disk:
                status, detail = "INSTALLED", "present in mods/ but no load evidence in the log"
            else:
                status, detail = "MISSING", "not in mods/"
            if any(target["key"].lower() in line.lower() for line in mixin_lines):
                status, detail = "FAILED", "mixin failure names this mod"
        else:
            if not on_disk:
                status, detail = "MISSING", f"not in {KIND_DIRS.get(kind, 'mods')}/"
            elif kind == "shader":
                selected = active_shader(game_dir) if game_dir else ""
                if shader and shader != target["key"]:
                    status, detail = "INSTALLED", "installed; not the active pack for this run"
                elif selected:
                    if selected != filename:
                        status, detail = "INSTALLED", ("not the selected pack "
                                                       f"(options.txt shaderPack={selected})")
                    elif shader_lines:
                        status, detail = "FAILED", (f"selected pack failed to compile: "
                                                    f"{shader_lines[0][:160]}")
                    elif shader_disabled:
                        status, detail = "INSTALLED", ("Oculus reported that no valid shaderpack "
                                                       "was selected, so nothing was compiled")
                    elif shader_enabled or ("Using shaderpack" in text and filename in text):
                        status, detail = "VERIFIED", ("Oculus reports the pack in use and logged "
                                                      "no shader compile error")
                    else:
                        status, detail = "INSTALLED", ("selected in options.txt but the log has no "
                                                       "shader load or compile evidence")
                elif shader_lines:
                    status, detail = "FAILED", f"shader compile errors: {shader_lines[0][:160]}"
                else:
                    status, detail = "INSTALLED", ("installed; the client never saved options.txt, "
                                                   "so no pack is confirmed active")
            else:
                status, detail = "VERIFIED", (f"installed in {KIND_DIRS.get(kind, 'mods')}/ "
                                              "(packs are not listed in the log's mod table)")
        statuses.append(TargetStatus(target["key"], target["name"], kind, status, detail))

    if crash_lines:
        verdict = "CRASHED"
    elif reached_menu:
        verdict = "STARTED"
    else:
        verdict = "PARTIAL"

    highlights: list[str] = []
    highlights += crash_lines[:3]
    highlights += mixin_lines[:3]
    highlights += shader_lines[:3]
    highlights += missing_deps[:3]
    if renderer_match:
        highlights.append(renderer_match.group(0).strip())

    return {
        "log": str(log_path),
        "bytes": len(text),
        "reached_menu": reached_menu,
        "crash": bool(crash_lines),
        "mixin_errors": mixin_lines[:5],
        "shader_errors": shader_lines[:5],
        "shader_enabled": shader_enabled,
        "shader_disabled": shader_disabled,
        "missing_deps": missing_deps,
        "forge": {"version": forge_match.group(1), "minecraft": forge_match.group(2)}
                 if forge_match else {},
        "renderer": renderer_match.group(1).strip() if renderer_match else "",
        "mentions_llvmpipe": "llvmpipe" in lowered or "softpipe" in lowered,
        "registered_mixin_configs": len(registered_configs),
        "statuses": [status.__dict__ for status in statuses],
        "verdict": verdict,
        "highlights": highlights,
    }


# ---------------------------------------------------------------------- launching


def archive_logs(game_dir: Path, label: str | None = None) -> Path | None:
    """Move the previous run's logs aside before a launch.

    Args:
        game_dir: instance game directory.
        label: timestamp suffix for the archive directory.

    Returns:
        The archive directory, or ``None`` when there was nothing to move.

    Side effects:
        Renames ``logs/*.log`` and ``logs/*.log.gz`` into
        ``logs-previous-<label>/``.  Without this a crashed launch can leave a stale
        ``latest.log`` that the analyzer would happily attribute to the new run.
    """
    logs = game_dir / "logs"
    if not logs.is_dir():
        return None
    stale = [path for pattern in ("*.log", "*.log.gz") for path in logs.glob(pattern)]
    if not stale:
        return None
    label = label or dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    target = game_dir / f"logs-previous-{label}"
    target.mkdir(parents=True, exist_ok=True)
    for path in stale:
        shutil.move(str(path), str(target / path.name))
    return target


def launch_instance(instance: Path, game_dir: Path, log_path: Path, timeout: int,
                    command: str | None = None, extra_args: list[str] | None = None,
                    settle: int = 30) -> dict:
    """Start the client and wait for the main menu, a crash or the timeout.

    Args:
        instance: instance root (Prism's ``--launch`` target is its folder name).
        game_dir: game directory whose ``logs/latest.log`` is watched.
        log_path: copy of the console output written by the harness.
        timeout: seconds to wait before the client is stopped.
        command: explicit command line instead of Prism; ``{game_dir}`` and
            ``{instance}`` are substituted.
        extra_args: arguments appended to an explicit ``command``.
        settle: seconds to keep the client alive after the first menu marker, so
            Oculus has time to compile the selected shader pack and log the result.
            The client is stopped once the log has been quiet for 10 s and ``settle``
            has elapsed.

    Returns:
        Dict with ``command``, ``exit`` (``None`` on timeout), ``seconds``,
        ``timed_out`` and ``menu_seen``.  The client is always terminated, so a
        forgotten window cannot exhaust the machine.
    """
    env = dict(os.environ)
    launcher = command
    if not launcher:
        for candidate in ("prismlauncher", "prism-launcher", "multimc", "multimc5"):
            binary = shutil.which(candidate)
            if binary:
                launcher = f"{binary} --launch {instance.name}"
                break
    if not launcher:
        raise SystemExit("error: no Prism Launcher/MultiMC on PATH and no --command given; "
                         "see docs/CLIENT_MATRIX.md")

    argv = [part.format(game_dir=game_dir, instance=instance) for part in launcher.split()]
    if extra_args:
        argv += extra_args
    if not env.get("DISPLAY") and shutil.which("xvfb-run"):
        argv = ["xvfb-run", "-a", "-s", "-screen 0 1280x720x24"] + argv

    started = time.time()
    log_path.parent.mkdir(parents=True, exist_ok=True)
    exit_code: int | None = None
    menu_seen = False
    menu_at: float | None = None
    last_size = 0
    last_change = started
    with log_path.open("w") as sink:
        proc = subprocess.Popen(argv, stdout=sink, stderr=subprocess.STDOUT,
                                cwd=str(game_dir), env=env)
        while time.time() - started < timeout:
            if proc.poll() is not None:
                exit_code = proc.returncode
                break
            time.sleep(2)
            # The child's stdout is block-buffered when it is a pipe, so the console
            # copy lags behind; watch the game's own log files as well.
            text = ""
            for candidate in (log_path, game_dir / "logs" / "latest.log",
                              game_dir / "logs" / "debug.log"):
                try:
                    text += candidate.read_text(errors="replace")[-200_000:]
                except OSError:
                    continue
            if len(text) != last_size:
                last_size = len(text)
                last_change = time.time()
            marker_seen = any(marker in text for marker in MENU_MARKERS)
            quiet = time.time() - last_change >= 20
            if marker_seen and menu_at is None:
                menu_at = time.time()
            if marker_seen and quiet and time.time() - menu_at >= settle:
                menu_seen = True
                proc.terminate()
                break
            if not marker_seen and quiet and time.time() - started > 60:
                # No marker but nothing is being written any more: the client is either
                # at a screen that logs nothing (menu) or stuck.  Judge the log itself
                # instead of waiting for the whole timeout.
                proc.terminate()
                break
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=30)
    return {
        "command": " ".join(argv),
        "exit": exit_code,
        "seconds": round(time.time() - started, 1),
        "timed_out": exit_code is None and not menu_seen,
        "menu_seen": menu_seen,
    }


# ------------------------------------------------------------------------ evidence


def _markdown(report: HostReport, prepare: PrepareResult | None, analysis: dict | None,
              launch: dict | None, label: str) -> str:
    """Render the evidence Markdown quoted by ``REQUIRED_MOD_COMPATIBILITY.md``.

    Args:
        report: host probe.
        prepare: instance preparation, or ``None`` when only a log was analyzed.
        analysis: :func:`analyze_log` output, or ``None``.
        launch: :func:`launch_instance` output, or ``None``.
        label: UTC timestamp used in the title.

    Returns:
        The complete Markdown document.
    """
    lines = [f"# Client matrix evidence {label}", ""]
    lines += ["## Host", "", "| Check | Status | Detail |", "| --- | --- | --- |"]
    lines += [f"| {c.name} | {c.status} | {c.detail} |" for c in report.checks]
    lines += ["", "Facts:", "", "```json", json.dumps(report.facts, indent=2), "```", ""]

    if prepare:
        verified = sum(1 for s in prepare.statuses if s.status == "VERIFIED")
        lines += ["## Instance", "",
                  f"`{prepare.game_dir}` - {verified}/{len(prepare.statuses)} client targets "
                  f"verified, {len(prepare.files)} files placed.", ""]
        lines += ["| Target | Kind | Status | Detail |", "| --- | --- | --- | --- |"]
        lines += [f"| {s.name} | {s.kind} | {s.status} | {s.detail} |" for s in prepare.statuses]
        if prepare.errors:
            lines += ["", "Preparation errors:", ""] + [f"* {e}" for e in prepare.errors]
        lines.append("")

    if launch:
        lines += ["## Launch", "", "```", launch["command"], "```", "",
                  f"exit={launch['exit']} seconds={launch['seconds']} "
                  f"menu={launch['menu_seen']} timed_out={launch['timed_out']}", ""]

    if analysis:
        lines += ["## Log analysis", "",
                  f"Verdict: **{analysis['verdict']}** (reached menu={analysis['reached_menu']}, "
                  f"crash={analysis['crash']}, bytes={analysis['bytes']})", ""]
        if analysis["forge"]:
            lines += [f"Forge {analysis['forge'].get('version')} for MC "
                      f"{analysis['forge'].get('minecraft')}.", ""]
        if analysis.get("renderer"):
            lines += [f"Renderer from the log: `{analysis['renderer']}`", ""]
        if analysis.get("mentions_llvmpipe"):
            lines += ["**Software OpenGL (`llvmpipe`)**: the client ran on a CPU rasteriser. "
                      "Mod load, mixin application and client start are valid evidence; "
                      "shader-pack performance and driver compatibility are not.", ""]
        lines += ["| Target | Kind | Status | Detail |", "| --- | --- | --- | --- |"]
        lines += [f"| {s['name']} | {s['kind']} | {s['status']} | {s['detail']} |"
                  for s in analysis["statuses"]]
        if analysis["missing_deps"]:
            lines += ["", "Missing dependencies: " + ", ".join(analysis["missing_deps"])]
        if analysis["highlights"]:
            lines += ["", "Log excerpts:", "", "```"] + analysis["highlights"] + ["```"]
        lines.append("")
    return "\n".join(lines)


def write_evidence(out_dir: Path, report: HostReport, prepare: PrepareResult | None,
                   analysis: dict | None, launch: dict | None,
                   label: str | None = None) -> tuple[Path, Path]:
    """Write the Markdown and JSON evidence pair.

    Args:
        out_dir: ``compat/evidence``.
        report: host probe.
        prepare: preparation result or ``None``.
        analysis: log analysis or ``None``.
        launch: launch result or ``None``.
        label: timestamp; defaults to the current UTC time.

    Returns:
        ``(markdown_path, json_path)``.

    Side effects:
        Creates ``out_dir`` and overwrites files that share the label.
    """
    label = label or dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir.mkdir(parents=True, exist_ok=True)
    markdown = out_dir / f"client-matrix-{label}.md"
    payload = out_dir / f"client-matrix-{label}.json"
    markdown.write_text(_markdown(report, prepare, analysis, launch, label))
    payload.write_text(json.dumps({
        "label": label,
        "host": {"checks": [c.__dict__ for c in report.checks], "facts": report.facts},
        "prepare": None if prepare is None else {
            "game_dir": str(prepare.game_dir),
            "files": prepare.files,
            "statuses": [s.__dict__ for s in prepare.statuses],
            "errors": prepare.errors,
        },
        "launch": launch,
        "analysis": analysis,
    }, indent=2) + "\n")
    return markdown, payload


# ----------------------------------------------------------------------- self test


def self_test() -> int:
    """Exercise preparation and log analysis against fixtures.

    Returns:
        0 when every fixture produced the expected verdict, 1 otherwise.  Needs no
        network, no Java and no GPU, so it can run in CI and in this container.
    """
    failures: list[str] = []
    manifest = {
        "minecraft": "1.20.1", "forge": "1.20.1-47.4.29", "java": 17,
        "targets": [
            {"key": "embeddium", "name": "Embeddium", "role": "client", "kind": "mod",
             "artifact": {"filename": "embeddium.jar", "sha512": ""}},
            {"key": "oculus", "name": "Oculus", "role": "client", "kind": "mod",
             "artifact": {"filename": "oculus.jar", "sha512": ""}},
            {"key": "bsl", "name": "BSL Shaders", "role": "client", "kind": "shader",
             "artifact": {"filename": "BSL.zip", "sha512": ""}},
            {"key": "anti-cords", "name": "Anti Cords Leaker", "role": "client",
             "kind": "resourcepack", "artifact": {"filename": "AntiCords.zip", "sha512": ""}},
            {"key": "serveronly", "name": "Server Only", "role": "server", "kind": "mod",
             "artifact": {"filename": "server.jar", "sha512": ""}},
        ],
        "dependencies": {"collective": {"filename": "collective.jar", "sha512": "", "url": ""}},
        "dependency_names": {"collective": "Collective"},
    }
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        source = root / "source"
        source.mkdir()

        def fixture_jar(path: Path, mod_id: str, mixin: str = "") -> None:
            """Write a minimal mod jar so ``mod_metadata`` has something real to read."""
            toml = ('modLoader="javafml"\nloaderVersion="[47,)"\n[[mods]]\n'
                    f'modId="{mod_id}"\n')
            if mixin:
                toml += f'\n[[mixins]]\nconfig="{mixin}"\n'
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("META-INF/mods.toml", toml)

        for name in ("BSL.zip", "AntiCords.zip", "collective.jar"):
            (source / name).write_bytes(b"fixture-" + name.encode())
        fixture_jar(source / "embeddium.jar", "embeddium", "embeddium.mixins.json")
        fixture_jar(source / "oculus.jar", "oculus")
        good_bytes = (source / "embeddium.jar").read_bytes()
        manifest["targets"][0]["artifact"]["sha512"] = hashlib.sha512(good_bytes).hexdigest()
        manifest["targets"][1]["artifact"]["sha512"] = hashlib.sha512(b"something else").hexdigest()

        result = prepare_instance(root / "instance", source, manifest, download=False,
                                 java="/usr/bin/java")
        by_key = {s.key: s for s in result.statuses}
        if by_key["embeddium"].status != "VERIFIED":
            failures.append(f"embeddium: expected VERIFIED, got {by_key['embeddium'].status}")
        if by_key["oculus"].status != "FAILED":
            failures.append(f"oculus: expected FAILED on hash mismatch, got {by_key['oculus'].status}")
        if "serveronly" in by_key:
            failures.append("server-only target leaked into the client instance")
        if by_key["bsl"].status != "VERIFIED" or by_key["anti-cords"].status != "VERIFIED":
            failures.append(f"packs were not installed: bsl={by_key['bsl'].status} "
                            f"anti-cords={by_key['anti-cords'].status}")
        mods = root / "instance/minecraft/mods"
        if not (mods / "collective.jar").is_file():
            failures.append("dependency mod was not placed in mods/")
        if (mods / "oculus.jar").exists():
            failures.append("hash-mismatched file was left in mods/")
        pack_file = root / "instance/mmc-pack.json"
        if not pack_file.is_file():
            failures.append("mmc-pack.json missing")
        else:
            pack = json.loads(pack_file.read_text())
            if pack["components"][1]["uid"] != "net.minecraftforge":
                failures.append("mmc-pack.json has no Forge component")
            elif pack["components"][1]["version"] != "47.4.29":
                failures.append(f"forge version wrong: {pack['components'][1]['version']}")
        if "JavaPath=/usr/bin/java" not in (root / "instance/instance.cfg").read_text():
            failures.append("instance.cfg does not pin the Java path")

        game_dir = root / "instance/minecraft"
        (game_dir / "mods/oculus.jar").write_bytes(b"plain")
        good = game_dir / "good.log"
        good.write_text("\n".join([
            "[Render thread/DEBUG] [mixin/]: Registering mixin config: embeddium.mixins.json",
            "[Render thread/INFO] [net.minecraftforge.fml.ModList/]: Found mod file oculus.jar",
            "[Render thread/INFO] [net.minecraftforge.common.ForgeMod/FORGEMOD]: "
            "Forge mod loading, version 47.4.29, for MC 1.20.1 with MCP 20230612.114412",
            "[Render thread/INFO] [minecraft/TextureAtlas/]: Created: 1024x512x4 "
            "minecraft:textures/atlas/blocks.png-atlas",
        ]))
        analysis = analyze_log(good, manifest, game_dir, shader="bsl")
        statuses = {s["key"]: s["status"] for s in analysis["statuses"]}
        if analysis["verdict"] != "STARTED":
            failures.append(f"healthy log: expected STARTED, got {analysis['verdict']}")
        if statuses["embeddium"] != "LOADED":
            failures.append(f"healthy log: embeddium {statuses['embeddium']} != LOADED")
        if statuses["oculus"] != "LOADED":
            failures.append(f"healthy log: oculus {statuses['oculus']} != LOADED (Found mod file)")
        if analysis["forge"].get("version") != "47.4.29":
            failures.append("forge version was not parsed from the log")

        crashed = game_dir / "crash.log"
        crashed.write_text("\n".join([
            "---- Minecraft Crash Report ----",
            "Mixin apply failed embeddium.mixins.json:BlockRenderManagerMixin",
            "Mod ID: 'embeddium', Requested by: 'oculus', Actual version: '[MISSING]'",
        ]))
        analysis = analyze_log(crashed, manifest, game_dir, shader="bsl")
        statuses = {s["key"]: s["status"] for s in analysis["statuses"]}
        if analysis["verdict"] != "CRASHED":
            failures.append(f"crash log: expected CRASHED, got {analysis['verdict']}")
        if statuses["embeddium"] != "FAILED":
            failures.append(f"crash log: embeddium {statuses['embeddium']} != FAILED")
        if not analysis["missing_deps"]:
            failures.append("crash log: missing dependency was not detected")

        shader_log = game_dir / "shader.log"
        shader_log.write_text("\n".join(["Sound engine started",
                                          "SHADER ERROR: 0(1) : error C0000"]))
        analysis = analyze_log(shader_log, manifest, game_dir, shader="bsl")
        statuses = {s["key"]: s["status"] for s in analysis["statuses"]}
        if statuses["bsl"] != "FAILED":
            failures.append(f"shader log: bsl {statuses['bsl']} != FAILED")
        analysis = analyze_log(shader_log, manifest, game_dir, shader="other")
        statuses = {s["key"]: s["status"] for s in analysis["statuses"]}
        if statuses["bsl"] != "INSTALLED":
            failures.append(f"inactive shader: bsl {statuses['bsl']} != INSTALLED")

        (game_dir / "options.txt").write_text("shaderPack:BSL.zip\n")
        analysis = analyze_log(shader_log, manifest, game_dir, shader="bsl")
        statuses = {s["key"]: s["status"] for s in analysis["statuses"]}
        if statuses["bsl"] != "FAILED":
            failures.append(f"selected shader: bsl {statuses['bsl']} != FAILED")
        set_active_shader(game_dir, "OFF")
        analysis = analyze_log(shader_log, manifest, game_dir, shader="bsl")
        statuses = {s["key"]: s["status"] for s in analysis["statuses"]}
        if statuses["bsl"] != "INSTALLED":
            failures.append(f"deselected shader: bsl {statuses['bsl']} != INSTALLED "
                            "(errors belong to another pack)")
        set_active_shader(game_dir, "BSL.zip")
        clean = game_dir / "clean.log"
        clean.write_text("Sound engine started\n")
        analysis = analyze_log(clean, manifest, game_dir, shader="bsl")
        statuses = {s["key"]: s["status"] for s in analysis["statuses"]}
        if statuses["bsl"] != "INSTALLED":
            failures.append(f"no shader evidence: bsl {statuses['bsl']} != INSTALLED")
        clean.write_text("Sound engine started\nShaders are enabled\n")
        analysis = analyze_log(clean, manifest, game_dir, shader="bsl")
        statuses = {s["key"]: s["status"] for s in analysis["statuses"]}
        if statuses["bsl"] != "VERIFIED":
            failures.append(f"shader enabled: bsl {statuses['bsl']} != VERIFIED")
        (game_dir / "options.txt").unlink()
        (game_dir / "config" / "oculus.properties").unlink()

        md, payload = write_evidence(
            root / "evidence",
            HostReport(checks=[Check("opengl", "PASS", "fixture")],
                       facts={"gl": {"class": "HARDWARE"}}),
            result, analysis, None, label="fixture")
        if not md.is_file() or "Client matrix evidence fixture" not in md.read_text():
            failures.append("markdown evidence was not written")
        if json.loads(payload.read_text())["label"] != "fixture":
            failures.append("json evidence was not written")

    for failure in failures:
        print(f"FAIL {failure}")
    print(f"self-test: {'PASS' if not failures else 'FAIL'} ({len(failures)} failure(s))")
    return 0 if not failures else 1


# ---------------------------------------------------------------------------- CLI


def _print_report(report: HostReport) -> None:
    """Print a host report as an aligned table."""
    width = max((len(c.name) for c in report.checks), default=6)
    for check in report.checks:
        print(f"{check.name:<{width}}  {check.status:<4}  {check.detail}")
    gl_class = report.facts.get("gl", {}).get("class", "NONE")
    note = ("shader packs can be judged here" if gl_class == "HARDWARE"
            else "shader results are not hardware results")
    print(f"\nOpenGL class: {gl_class} ({note})")
    print(f"host checks: {'PASS' if report.ok else 'FAIL'}")


def main(argv: list[str] | None = None) -> int:
    """Command line entry point.

    Args:
        argv: arguments without ``sys.argv[0]``.

    Returns:
        The process exit code (0 success, 1 a failed check, 2 handled by argparse).
    """
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--manifest", type=Path, default=MANIFEST)
    common.add_argument("--instance", type=Path, default=Path(".runtime/client"))
    common.add_argument("--java", default=None)
    sub = parser.add_subparsers(dest="action", required=True)

    sub.add_parser("host", parents=[common],
                   help="probe the machine for client-matrix prerequisites")
    sub.add_parser("self-test", help="run the fixture based tests")

    prepare_cmd = sub.add_parser("prepare", parents=[common],
                                 help="assemble the pinned client instance")
    prepare_cmd.add_argument("--source", type=Path, default=None)
    prepare_cmd.add_argument("--no-download", action="store_true")
    prepare_cmd.add_argument("--multiforge-jar", type=Path, default=None)
    prepare_cmd.add_argument("--memory-mb", type=int, default=4096)

    analyze_cmd = sub.add_parser("analyze", parents=[common], help="judge a latest.log")
    analyze_cmd.add_argument("--log", type=Path, required=True)
    analyze_cmd.add_argument("--shader", default=None)
    analyze_cmd.add_argument("--out", type=Path, default=EVIDENCE_DIR)

    run_cmd = sub.add_parser("run", parents=[common], help="launch the instance, then analyze it")
    run_cmd.add_argument("--command", default=None)
    run_cmd.add_argument("--timeout", type=int, default=900)
    run_cmd.add_argument("--settle", type=int, default=30,
                         help="seconds to keep the client alive after the first menu marker "
                              "(use 120+ for shader packs)")
    run_cmd.add_argument("--shader", default=None)
    run_cmd.add_argument("--out", type=Path, default=EVIDENCE_DIR)
    run_cmd.add_argument("--log", type=Path, default=None)

    evidence_cmd = sub.add_parser("evidence", parents=[common],
                                  help="record an existing instance/log pair")
    evidence_cmd.add_argument("--log", type=Path, default=None)
    evidence_cmd.add_argument("--shader", default=None)
    evidence_cmd.add_argument("--out", type=Path, default=EVIDENCE_DIR)

    args = parser.parse_args(argv)
    if args.action == "self-test":
        return self_test()

    # Absolute paths: the child is started with cwd=<game_dir>, so a relative instance
    # path would be resolved twice and the client would log to a nested directory.
    args.instance = args.instance.resolve()
    args.manifest = args.manifest.resolve()
    manifest = read_manifest(args.manifest)
    report = probe_host(manifest, args.instance, args.java)
    if args.action == "host":
        _print_report(report)
        return 0 if report.ok else 1

    game_dir = (args.instance / "minecraft"
                if (args.instance / "minecraft").is_dir() else args.instance)
    if args.action in ("run", "evidence") and not game_dir.is_dir():
        sys.exit(f"error: {game_dir} does not exist; run `prepare` first")

    prepare: PrepareResult | None = None
    fresh = True
    if args.action == "prepare":
        prepare = prepare_instance(args.instance, args.source, manifest, find_java(args.java),
                                   download=not args.no_download,
                                   multiforge_jar=args.multiforge_jar,
                                   memory_mb=args.memory_mb)
        _print_report(report)
        print()
        for status in prepare.statuses:
            print(f"{status.key:<24} {status.status:<10} {status.detail}")
        for error in prepare.errors:
            print(f"error: {error}")
        print(f"\nprepared {len(prepare.files)} file(s) in {prepare.game_dir}")
        return 0 if prepare.ok else 1

    log_path = args.log or default_log(game_dir)
    launch: dict | None = None
    if args.action == "run":
        if args.shader:
            filename = next((t["artifact"]["filename"] for t in client_targets(manifest)
                             if t["key"] == args.shader and t.get("artifact")), "")
            if not filename:
                sys.exit(f"error: --shader {args.shader} is not a shader target in {args.manifest}")
            set_active_shader(game_dir, filename)
            print(f"shader: selected {filename} in config/oculus.properties")
        console_log = args.instance / "client-matrix-console.log"
        archived = archive_logs(game_dir)
        if archived:
            print(f"archived previous logs to {archived}")
        started = time.time()
        launch = launch_instance(args.instance, game_dir, console_log, args.timeout,
                                 command=args.command, settle=args.settle)
        log_path = default_log(game_dir)
        print(f"launch: exit={launch['exit']} seconds={launch['seconds']} "
              f"menu={launch['menu_seen']} timeout={launch['timed_out']}")
        fresh = any(path.is_file() and path.stat().st_mtime >= started - 5
                    for path in (game_dir / "logs" / "latest.log",
                                 game_dir / "logs" / "debug.log"))
        if not fresh:
            print("error: the client wrote no log file; the analysis below cannot be "
                  "attributed to this run")

    analysis = analyze_log(log_path, manifest, game_dir, args.shader)
    if args.action == "run" and not fresh:
        analysis["verdict"] = "PARTIAL"
        analysis["highlights"].insert(0, "no log file was written by this run")
    md, payload = write_evidence(args.out, report, prepare, analysis, launch)
    print(f"verdict: {analysis['verdict']}")
    for status in analysis["statuses"]:
        print(f"{status['key']:<24} {status['status']:<10} {status['detail']}")
    print(f"evidence: {md} and {payload}")
    return 0 if analysis["verdict"] == "STARTED" and not analysis["missing_deps"] else 1


if __name__ == "__main__":
    sys.exit(main())
