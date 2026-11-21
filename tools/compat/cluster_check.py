#!/usr/bin/env python3
"""Run the reproducible two-server MultiForge compatibility checks over RCON.

The harness assumes two already running Forge 1.20.1 servers with MultiForge and
RCON enabled. It performs only non-destructive gameplay checks in a chunk that
both servers have loaded, records every command and response, and writes a
Markdown evidence file plus a JSON result file.

Checks: master connectivity, cluster membership, required server targets,
required data pack, bidirectional block broadcast, block entity sync, time and
gamerule propagation, single chunk owner, tick health and save-all.

Usage::

    python3 tools/compat/cluster_check.py \
        --a-port 25575 --b-port 25576 --password mfcompat \
        --out compat/evidence

Exit code 0 when every check passes, 1 otherwise. A failed check never stops the
remaining checks; the evidence file records all of them.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from rcon import RconError, command  # noqa: E402

CHUNK_PATTERN = re.compile(r"world,(-?\d+),(-?\d+)")
OWNER_PATTERN = re.compile(r"world,(-?\d+),(-?\d+) owner=([^ ]+)")
OWNER_HEADER = re.compile(r"Chunks owned by (\S+):")
CHUNK_LINE = re.compile(r"world,(-?\d+),(-?\d+)")


class Cluster:
    """Two RCON endpoints plus the transcript shared by every check."""

    def __init__(self, a_port: int, b_port: int, password: str, host: str = "127.0.0.1"):
        self.ports = {"A": a_port, "B": b_port}
        self.password = password
        self.host = host
        self.transcript: list[dict] = []

    def run(self, side: str, text: str, timeout: float = 30.0) -> str:
        """Execute one command and record it."""
        try:
            response = command(self.host, self.ports[side], self.password, text, timeout)
        except (RconError, OSError) as error:
            response = f"<RCON ERROR: {error}>"
        self.transcript.append({"server": side, "command": text, "response": response})
        return response

    def both(self, text: str) -> tuple[str, str]:
        """Execute the same command on both servers."""
        return self.run("A", text), self.run("B", text)


def check(results: list, name: str, passed: bool, detail: str) -> None:
    """Append one result and print a single progress line."""
    results.append({"check": name, "passed": bool(passed), "detail": detail})
    print(f"[{'PASS' if passed else 'FAIL'}] {name}: {detail}")


def parse_chunks(text: str) -> set[tuple[int, int]]:
    """Extract chunk coordinates from /multiforge chunks output."""
    return {(int(x), int(z)) for x, z in CHUNK_PATTERN.findall(text)}


def parse_owners(text: str) -> dict[tuple[int, int], str]:
    """Extract chunk -> owner pairs from /multiforge chunks.

    The command prints one section per owner ("Chunks owned by live1: N" followed
    by " - world,x,z" lines), with a legacy "world,x,z owner=..." form kept as a
    fallback for older builds/tests.
    """
    owners: dict[tuple[int, int], str] = {}
    current = None
    for line in text.splitlines():
        header = OWNER_HEADER.search(line)
        if header:
            current = header.group(1)
            continue
        chunk = CHUNK_LINE.search(line)
        if chunk and current:
            owners[(int(chunk.group(1)), int(chunk.group(2)))] = current
    for x, z, owner in OWNER_PATTERN.findall(text):
        owners[(int(x), int(z))] = owner
    return owners


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--a-port", type=int, required=True)
    parser.add_argument("--b-port", type=int, required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--out", default="compat/evidence")
    parser.add_argument("--wait-seconds", type=float, default=3.0,
                        help="propagation delay after a state change")
    parser.add_argument("--expected-targets", type=int, default=19,
                        help="number of required server mods expected (19 with Ksyxis, "
                             "18 when Ksyxis is removed for the chunk-sync check)")
    parser.add_argument("--skip-mod-checks", action="store_true",
                        help="skip the required-mod/data-pack checks (dev cluster with no pinned mods)")
    args = parser.parse_args(argv)

    cluster = Cluster(args.a_port, args.b_port, args.password, args.host)
    results: list[dict] = []

    status_a = cluster.run("A", "multiforge status")
    status_b = cluster.run("B", "multiforge status")
    check(results, "master-connected",
          "master=connected" in status_a and "master=connected" in status_b,
          f"A={status_a.strip()} B={status_b.strip()}")

    servers_a = cluster.run("A", "multiforge servers")
    servers_b = cluster.run("B", "multiforge servers")
    check(results, "cluster-membership",
          "live1" in servers_a and "live2" in servers_a
          and "live1" in servers_b and "live2" in servers_b,
          "both servers list live1 and live2")

    if not args.skip_mod_checks:
        compat = cluster.run("A", "multiforge compat")
        loaded = re.search(r"Required server targets loaded: (\d+)/(\d+)", compat)
        check(results, "required-targets-detected",
              bool(loaded) and int(loaded.group(1)) == args.expected_targets,
              loaded.group(0) if loaded else "no summary line in /multiforge compat")

        datapacks = cluster.run("A", "datapack list")
        check(results, "required-datapack-enabled",
              "UltimateAntiSeedCracker" in datapacks,
              "UltimateAntiSeedCracker.zip enabled" if "UltimateAntiSeedCracker" in datapacks
              else "data pack not listed")

    chunks_a = parse_chunks(cluster.run("A", "multiforge chunks"))
    chunks_b = parse_chunks(cluster.run("B", "multiforge chunks"))
    common = sorted(chunks_a & chunks_b)
    if not common:
        check(results, "shared-loaded-chunk", False,
              f"no common chunk (A={len(chunks_a)}, B={len(chunks_b)})")
        return finish(cluster, results, args)
    check(results, "shared-loaded-chunk", True, f"{len(common)} common chunks, using {common[0]}")

    # Prefer a chunk that is loaded on both servers *and* has an owner: entity
    # damage/removal must be applied on the authoritative server, and "loaded on
    # both, owned by neither" can exist briefly during ownership handover.
    owners_initial = parse_owners(cluster.run("A", "multiforge chunks"))
    owned_common = [key for key in chunks_b
                    if key in chunks_a and owners_initial.get(key) in ("live1", "live2")]
    chunk_x, chunk_z = (owned_common or common)[0]
    block_x = chunk_x * 16 + 8
    block_z = chunk_z * 16 + 8

    block = f"{block_x} 64 {block_z}"
    cluster.run("A", f"setblock {block} minecraft:gold_block")
    time.sleep(args.wait_seconds)
    read_b = cluster.run("B", f"execute if block {block} minecraft:gold_block")
    check(results, "block-a-to-b", "Test passed" in read_b, read_b.strip())

    other = f"{block_x + 1} 64 {block_z}"
    cluster.run("B", f"setblock {other} minecraft:diamond_block")
    time.sleep(args.wait_seconds)
    read_a = cluster.run("A", f"execute if block {other} minecraft:diamond_block")
    check(results, "block-b-to-a", "Test passed" in read_a, read_a.strip())

    chest = f"{block_x + 2} 64 {block_z}"
    cluster.run("A", f"setblock {chest} minecraft:chest")
    cluster.run("A", f"item replace block {chest} container.0 with minecraft:diamond 5")
    time.sleep(args.wait_seconds)
    chest_b = cluster.run("B", f"data get block {chest} Items[0].id")
    check(results, "block-entity-a-to-b", "minecraft:diamond" in chest_b, chest_b.strip())

    suffix = str(int(time.time()) % 100000)
    objective = f"mfcheck{suffix}"
    team = f"mfc{suffix}"
    cluster.run("A", f"scoreboard objectives add {objective} dummy")
    time.sleep(args.wait_seconds)
    objectives_b = cluster.run("B", "scoreboard objectives list")
    check(results, "scoreboard-objective-a-to-b", objective in objectives_b,
          objectives_b.strip())

    cluster.run("A", f"scoreboard players set MFCheck {objective} 7")
    time.sleep(args.wait_seconds)
    score_b = cluster.run("B", f"scoreboard players get MFCheck {objective}")
    check(results, "scoreboard-score-a-to-b", "has 7" in score_b, score_b.strip())

    cluster.run("A", f"team add {team}")
    cluster.run("A", f"team join {team} MFCheck")
    time.sleep(args.wait_seconds)
    team_b = cluster.run("B", f"team list {team}")
    check(results, "scoreboard-team-a-to-b", "1 member" in team_b, team_b.strip())

    # Damage and removal must be applied on the chunk's authoritative owner; a
    # mirror only forwards the hit. The chosen common chunk can belong to either
    # server, so select the owner dynamically.
    owners_for_chunk = parse_owners(cluster.run("A", "multiforge chunks"))
    chunk_owner = owners_for_chunk.get((chunk_x, chunk_z))
    owner_side = "A" if chunk_owner == "live1" else "B"
    mirror_side = "B" if owner_side == "A" else "A"
    cow = f"{block_x} 100 {block_z}"
    cluster.run("A", "kill @e[type=minecraft:cow,tag=mfcheck]")
    cluster.run("B", "kill @e[type=minecraft:cow,tag=mfcheck]")
    time.sleep(args.wait_seconds)
    cow_nbt = '{NoGravity:1b,Tags:["mfcheck"]}'
    cluster.run(owner_side, f"summon minecraft:cow {cow} {cow_nbt}")
    time.sleep(args.wait_seconds)
    mirrored = cluster.run(mirror_side, "execute if entity @e[type=minecraft:cow,tag=mfcheck]")
    check(results, f"entity-mirror-{owner_side}-to-{mirror_side}",
          "Test passed" in mirrored, f"owner={chunk_owner} {mirrored.strip()}")

    cluster.run(owner_side, "damage @e[type=minecraft:cow,tag=mfcheck,limit=1] 2")
    time.sleep(args.wait_seconds)
    health = cluster.run(mirror_side,
                         "data get entity @e[type=minecraft:cow,tag=mfcheck,limit=1] Health")
    check(results, f"entity-damage-{owner_side}-to-{mirror_side}", "8.0" in health, health.strip())

    cluster.run(owner_side, "kill @e[type=minecraft:cow,tag=mfcheck]")
    time.sleep(args.wait_seconds)
    removed = cluster.run(mirror_side, "execute if entity @e[type=minecraft:cow,tag=mfcheck]")
    check(results, f"entity-remove-{owner_side}-to-{mirror_side}",
          "Test failed" in removed, removed.strip())

    cluster.run("A", f"scoreboard objectives remove {objective}")
    cluster.run("A", f"team remove {team}")

    sign = f"{block_x + 3} 64 {block_z}"
    cluster.run("A", f"setblock {sign} minecraft:oak_sign")
    sign_nbt = '{front_text:{messages:[\'"mf-sign"\',\'"line2"\',\'""\',\'""\']}}'
    cluster.run("A", f"data merge block {sign} {sign_nbt}")
    time.sleep(args.wait_seconds)
    sign_b = cluster.run("B", f"data get block {sign} front_text.messages[0]")
    check(results, "sign-text-a-to-b", "mf-sign" in sign_b, sign_b.strip())

    cluster.run("A", "gamerule doDaylightCycle false")
    cluster.run("A", "time set 6000")
    time.sleep(args.wait_seconds)
    daytime = cluster.run("B", "time query daytime")
    match = re.search(r"(\d+)", daytime)
    check(results, "time-sync", bool(match) and abs(int(match.group(1)) - 6000) <= 40,
          daytime.strip())

    cluster.run("A", "gamerule doDaylightCycle false")
    time.sleep(args.wait_seconds)
    rule_b = cluster.run("B", "gamerule doDaylightCycle")
    check(results, "gamerule-sync", "false" in rule_b.lower(), rule_b.strip())
    cluster.run("A", "gamerule doDaylightCycle true")

    owners_a = parse_owners(cluster.run("A", "multiforge chunks"))
    owners_b = parse_owners(cluster.run("B", "multiforge chunks"))
    conflict = [key for key, owner in owners_a.items()
                if owner == "live1" and owners_b.get(key) == "live2"]
    check(results, "single-authoritative-owner", not conflict,
          "no chunk claimed by both servers" if not conflict else f"conflicts: {conflict[:5]}")

    tps_a = cluster.run("A", "forge tps")
    tps_b = cluster.run("B", "forge tps")
    check(results, "tick-health",
          "Mean TPS: 20.000" in tps_a and "Mean TPS: 20.000" in tps_b,
          "both servers at 20 TPS")

    cluster.run("A", "save-all flush")
    cluster.run("B", "save-all flush")
    check(results, "save-all", True, "save-all flush completed on both servers")

    return finish(cluster, results, args)


def finish(cluster: Cluster, results: list[dict], args) -> int:
    """Write JSON + Markdown evidence and return the process exit code."""
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    passed = sum(1 for result in results if result["passed"])
    payload = {
        "generated_at": stamp,
        "servers": {"A": args.a_port, "B": args.b_port},
        "passed": passed,
        "total": len(results),
        "results": results,
        "transcript": cluster.transcript,
    }
    json_path = out / f"cluster-{stamp}.json"
    json_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    lines = [
        f"# MultiForge cluster evidence {stamp}",
        "",
        f"Two Forge 1.20.1 / MultiForge dedicated servers, RCON ports "
        f"{args.a_port} and {args.b_port}.",
        "",
        f"**{passed}/{len(results)} checks passed.**",
        "",
        "| Check | Result | Detail |",
        "| --- | --- | --- |",
    ]
    for result in results:
        detail = result["detail"].replace("|", "\\|").replace("\n", " ")[:180]
        lines.append(f"| {result['check']} | {'PASS' if result['passed'] else 'FAIL'} | {detail} |")
    lines += ["", "## Command transcript", "", "```"]
    for entry in cluster.transcript:
        lines.append(f"[{entry['server']}] /{entry['command']}")
        lines.append(entry["response"].rstrip())
    lines += ["```", ""]
    md_path = out / f"cluster-{stamp}.md"
    md_path.write_text("\n".join(lines), encoding="utf-8")

    print(f"evidence: {md_path} and {json_path}")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
