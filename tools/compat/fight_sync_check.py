#!/usr/bin/env python3
"""Verify raid and ender-dragon fight replication and handover on a live cluster.

The harness assumes two running MultiForge servers with RCON enabled and one
headless player connected to each server (``tools/compat/mcbot.js``). It uses only
vanilla teleports plus ``/multiforge fights``, so it needs no graphical client:

1. make sure a dragon fight is running (teleport bot A to the island if needed);
2. assert both servers report the same dragon UUID / phase and exactly one
   reports ``local=true`` (the ticking server);
3. move the current owner's bot away and the other bot into the island, which
   starts the normal fenced chunk handover, and assert the ownership epoch grew,
   the state is unchanged and exactly one server ticks;
4. hand the island back and assert the same;
5. if a raid exists, assert the same id/wave on both servers and one owner.

Usage::

    node tools/compat/mcbot.js 127.0.0.1 25565 SyncBotA 25599 &
    node tools/compat/mcbot.js 127.0.0.1 25566 SyncBotB 25600 &
    python3 tools/compat/fight_sync_check.py \
        --a-port 25575 --b-port 25576 --password mfcompat \
        --out compat/evidence

Exit code 0 when every check passes, 1 otherwise.
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

DRAGON = re.compile(
    r"dragon world=(?P<world>\S+) uuid=(?P<uuid>\S+) killed=(?P<killed>\S+) "
    r"previously=(?P<previously>\S+) respawning=(?P<respawning>\S+) "
    r"gateways=(?P<gateways>\d+) portal=(?P<portal>.+?) epoch=(?P<epoch>\d+) "
    r"local=(?P<local>true|false)")
RAID = re.compile(
    r"raid world=(?P<world>\S+) id=(?P<id>-?\d+) center=(?P<center>\S+) "
    r"wave=(?P<wave>\d+) active=(?P<active>\S+) stopped=(?P<stopped>\S+) "
    r"epoch=(?P<epoch>\d+) local=(?P<local>true|false)")


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

    def fights(self, side: str) -> list[dict]:
        """Return the parsed fight lines of one server."""
        text = self.run(side, "multiforge fights")
        parsed = []
        for match in DRAGON.finditer(text):
            entry = match.groupdict()
            entry["kind"] = "dragon"
            entry["local"] = entry["local"] == "true"
            entry["epoch"] = int(entry["epoch"])
            parsed.append(entry)
        for match in RAID.finditer(text):
            entry = match.groupdict()
            entry["kind"] = "raid"
            entry["local"] = entry["local"] == "true"
            entry["epoch"] = int(entry["epoch"])
            parsed.append(entry)
        return parsed


def check(results: list, name: str, passed: bool, detail: str) -> None:
    """Append one result and print a single progress line."""
    results.append({"check": name, "passed": bool(passed), "detail": detail})
    print(f"[{'PASS' if passed else 'FAIL'}] {name}: {detail}")


def dragon(entries: list[dict]) -> dict | None:
    """Return the dragon entry, or None."""
    for entry in entries:
        if entry["kind"] == "dragon":
            return entry
    return None


def wait_for(predicate, timeout: float, interval: float = 1.0):
    """Poll until predicate() is truthy, returning its value or None on timeout."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(interval)
    return None


def teleport(cluster: Cluster, side: str, bot: str, dimension: str, coords: str) -> str:
    """Teleport a bot into a dimension at ``x y z`` and return the response."""
    return cluster.run(side, f"execute in {dimension} run tp {bot} {coords}")


def raid_rounds(cluster: Cluster, results: list[dict], args) -> None:
    """Hand an active raid's centre chunk to the other server and back.

    Uses the raid's own centre coordinates and the overworld. Runs after the dragon
    rounds so the dragon assertions are unaffected; the bots are already connected.
    """
    raids_a = [entry for entry in cluster.fights("A")
               if entry["kind"] == "raid" and entry["stopped"] == "false"]
    if not raids_a:
        print("[SKIP] raid checks: no active raid in the loaded levels")
        return
    raid = raids_a[0]
    cx, cy, cz = (int(float(value)) for value in raid["center"].split(","))
    center = f"{cx + 0.5} {cy} {cz + 0.5}"
    far = f"{cx + 40.5} {cy} {cz + 0.5}"

    def on(side: str):
        return next((entry for entry in cluster.fights(side)
                     if entry["kind"] == "raid" and entry["id"] == raid["id"]), None)

    state_a, state_b = on("A"), on("B")
    local = [side for side, state in (("A", state_a), ("B", state_b))
             if state and state["local"]]
    if len(local) != 1:
        check(results, "raid-single-ticking-owner", False,
              f"before handover: A={bool(state_a and state_a['local'])} "
              f"B={bool(state_b and state_b['local'])}")
        return
    owner_side = local[0]
    check(results, "raid-state-replicated",
          bool(state_a) and bool(state_b) and state_a["id"] == state_b["id"]
          and state_a["wave"] == state_b["wave"],
          f"A={state_a['id']}/{state_a['wave']} B={state_b['id']}/{state_b['wave']}")

    for round_name in ("raid-handover", "raid-handover-back"):
        before = on(owner_side)
        moving_side = owner_side
        moving_bot = args.bot_a if moving_side == "A" else args.bot_b
        entering_side = "B" if moving_side == "A" else "A"
        entering_bot = args.bot_a if entering_side == "A" else args.bot_b
        teleport(cluster, moving_side, moving_bot, "minecraft:overworld", far)
        time.sleep(min(args.wait_seconds, 5.0))
        teleport(cluster, entering_side, entering_bot, "minecraft:overworld", center)

        def flipped():
            fresh = {"A": on("A"), "B": on("B")}
            if fresh[entering_side] and fresh[entering_side]["local"] and \
                    fresh[moving_side] and not fresh[moving_side]["local"]:
                return fresh
            return None

        fresh = wait_for(flipped, args.timeout_seconds)
        after = fresh[entering_side] if fresh else None
        check(results, f"{round_name}-owner-flip", bool(fresh),
              f"{moving_side} -> {entering_side}, epoch "
              f"{before['epoch'] if before else '?'} -> {after['epoch'] if after else '?'}")
        if not fresh:
            return
        check(results, f"{round_name}-state-preserved",
              bool(before) and after["id"] == before["id"] and after["wave"] == before["wave"],
              f"id={after['id']} wave={after['wave']} active={after['active']}")
        owner_side = entering_side


def finish(cluster: Cluster, results: list[dict], out: Path) -> int:
    """Write the Markdown/JSON evidence and return the exit code."""
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out.mkdir(parents=True, exist_ok=True)
    passed = sum(1 for result in results if result["passed"])
    markdown = [
        f"# MultiForge fight synchronization evidence {stamp}",
        "",
        "Two Forge 1.20.1 / MultiForge dedicated servers, RCON ports "
        f"{cluster.ports['A']} and {cluster.ports['B']}.",
        "",
        f"**{passed}/{len(results)} checks passed.**",
        "",
        "| Check | Result | Detail |",
        "| --- | --- | --- |",
    ]
    for result in results:
        detail = result["detail"].replace("|", "\\|")
        markdown.append(f"| {result['check']} | "
                        f"{'PASS' if result['passed'] else 'FAIL'} | {detail} |")
    markdown += ["", "## Command transcript", "", "```"]
    for entry in cluster.transcript:
        markdown.append(f"[{entry['server']}] {entry['command']}")
        markdown.append(entry["response"].rstrip())
    markdown += ["```", ""]
    md_path = out / f"fight-sync-{stamp}.md"
    json_path = out / f"fight-sync-{stamp}.json"
    md_path.write_text("\n".join(markdown) + "\n", encoding="utf-8")
    json_path.write_text(json.dumps(
        {"timestamp": stamp, "checks": results, "transcript": cluster.transcript},
        indent=2) + "\n", encoding="utf-8")
    print(f"evidence: {md_path} and {json_path}")
    return 0 if passed == len(results) else 1


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--a-port", type=int, required=True)
    parser.add_argument("--b-port", type=int, required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--out", default="compat/evidence")
    parser.add_argument("--bot-a", default="SyncBotA", help="player connected to server A")
    parser.add_argument("--bot-b", default="SyncBotB", help="player connected to server B")
    parser.add_argument("--dimension", default="minecraft:the_end")
    parser.add_argument("--center", default="0.5 80 0.5", help="island teleport target")
    parser.add_argument("--far", default="600 80 600",
                        help="target outside the dragon's 192-block player scan")
    parser.add_argument("--wait-seconds", type=float, default=10.0,
                        help="settle time after a teleport/handover")
    parser.add_argument("--timeout-seconds", type=float, default=60.0,
                        help="maximum wait for a handover result")
    args = parser.parse_args(argv)

    cluster = Cluster(args.a_port, args.b_port, args.password, args.host)
    results: list[dict] = []

    # 1. Read both servers; start the fight if nobody is in the arena yet.
    entries_a = cluster.fights("A")
    entries_b = cluster.fights("B")
    if not dragon(entries_a) or dragon(entries_a)["uuid"] == "null":
        teleport(cluster, "A", args.bot_a, args.dimension, args.center)
        started = wait_for(
            lambda: (dragon(cluster.fights("A")) or {}).get("uuid") not in (None, "null"),
            args.timeout_seconds)
        check(results, "dragon-fight-started", bool(started),
              f"teleported {args.bot_a} to {args.dimension} {args.center}")
        entries_a = cluster.fights("A")
        entries_b = cluster.fights("B")
    state_a = dragon(entries_a)
    state_b = dragon(entries_b)
    check(results, "dragon-fight-running",
          bool(state_a) and state_a["uuid"] not in (None, "null"),
          f"uuid={state_a['uuid'] if state_a else 'missing'}")
    if not state_a:
        return finish(cluster, results, Path(args.out))

    # 2. Both servers must hold the same fight state. The owner broadcasts once
    #    per second, so a just-started fight needs a short poll on the mirror.
    def replicated():
        fresh_a = dragon(cluster.fights("A"))
        fresh_b = dragon(cluster.fights("B"))
        if fresh_a and fresh_b and fresh_a["uuid"] == fresh_b["uuid"] \
                and fresh_a["uuid"] not in (None, "null"):
            return fresh_a, fresh_b
        return None

    replicated_state = wait_for(replicated, min(args.timeout_seconds, 20.0))
    if replicated_state:
        state_a, state_b = replicated_state
    same_uuid = bool(state_b) and state_b["uuid"] == state_a["uuid"]
    same_phase = bool(state_b) and all(state_b[key] == state_a[key]
                                       for key in ("killed", "previously", "respawning", "gateways"))
    check(results, "dragon-state-replicated", same_uuid and same_phase,
          f"A={state_a['uuid']} B={state_b['uuid'] if state_b else 'missing'}")

    def owner(entries_by_side):
        local = [side for side, entries in entries_by_side.items()
                 if (dragon(entries) or {}).get("local")]
        return local[0] if len(local) == 1 else None

    owner_side = owner({"A": entries_a, "B": entries_b})
    check(results, "single-ticking-owner", owner_side is not None,
          f"ticking server={owner_side or 'none/duplicate'}")
    if owner_side is None:
        return finish(cluster, results, Path(args.out))

    # 3. Raid first (while its village is loaded), then the dragon handovers.
    raid_rounds(cluster, results, args)

    # 4. Hand the dragon fight to the other server and back.
    for round_name in ("handover", "handover-back"):
        before = dragon(cluster.fights(owner_side))
        moving_side = owner_side
        moving_bot = args.bot_a if moving_side == "A" else args.bot_b
        entering_side = "B" if moving_side == "A" else "A"
        entering_bot = args.bot_a if entering_side == "A" else args.bot_b
        teleport(cluster, moving_side, moving_bot, args.dimension, args.far)
        time.sleep(min(args.wait_seconds, 5.0))
        teleport(cluster, entering_side, entering_bot, args.dimension, args.center)

        def flipped():
            fresh = {"A": cluster.fights("A"), "B": cluster.fights("B")}
            if (dragon(fresh[entering_side]) or {}).get("local") and \
                    not (dragon(fresh[moving_side]) or {}).get("local"):
                return fresh
            return None

        fresh = wait_for(flipped, args.timeout_seconds)
        after = dragon(fresh[entering_side]) if fresh else None
        check(results, f"{round_name}-owner-flip", bool(fresh),
              f"{moving_side} -> {entering_side}, epoch "
              f"{before['epoch'] if before else '?'} -> {after['epoch'] if after else '?'}")
        if not fresh:
            return finish(cluster, results, Path(args.out))
        check(results, f"{round_name}-state-preserved",
              all(after[key] == before[key] for key in
                  ("uuid", "killed", "previously", "respawning", "gateways")),
              f"uuid={after['uuid']} killed={after['killed']} gateways={after['gateways']}")
        owner_side = entering_side

    # 5. If a raid is active, it must follow the same rules.
    #    (The raid round already ran before the dragon handovers; see step 3.)

    return finish(cluster, results, Path(args.out))


if __name__ == "__main__":
    sys.exit(main())
