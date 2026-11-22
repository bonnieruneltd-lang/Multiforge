#!/usr/bin/env python3
"""Force packet loss on the live block stream and prove that it converges.

The sender exposes ``/multiforge drop <percent>``: that percentage of the *first*
transmissions of block and block-entity updates is discarded per peer. The updates
are already in the per-peer retransmit history, so the receiver detects a sequence
gap, acknowledges the highest contiguous sequence, and the sender resends the
missing entries (retransmissions are never dropped). This harness drives that path
on a real two-server cluster.

Usage::

    python3 tools/compat/packet_loss_check.py \
        --a-port 25575 --b-port 25576 --password mfcompat \
        --out compat/evidence

Exit code 0 when gaps were observed and every block/block entity arrived.
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

STATUS = re.compile(r"seq gaps=(?P<gaps>\d+) dup=(?P<dup>\d+).*?drop=(?P<drop>\d+)")


class Cluster:
    """Two RCON endpoints plus the transcript shared by every check."""

    def __init__(self, a_port: int, b_port: int, password: str, host: str = "127.0.0.1"):
        self.ports = {"A": a_port, "B": b_port}
        self.password = password
        self.host = host
        self.transcript: list[dict] = []

    def run(self, side: str, text: str, timeout: float = 30.0) -> str:
        try:
            response = command(self.host, self.ports[side], self.password, text, timeout)
        except (RconError, OSError) as error:
            response = f"<RCON ERROR: {error}>"
        self.transcript.append({"server": side, "command": text, "response": response})
        return response


def check(results: list, name: str, passed: bool, detail: str) -> None:
    results.append({"check": name, "passed": bool(passed), "detail": detail})
    print(f"[{'PASS' if passed else 'FAIL'}] {name}: {detail}")


def finish(cluster: Cluster, results: list[dict], out: Path) -> int:
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out.mkdir(parents=True, exist_ok=True)
    passed = sum(1 for result in results if result["passed"])
    markdown = [
        f"# MultiForge forced packet-loss evidence {stamp}",
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
    md_path = out / f"packet-loss-{stamp}.md"
    json_path = out / f"packet-loss-{stamp}.json"
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
    parser.add_argument("--percent", type=int, default=50, help="drop percentage on server A")
    parser.add_argument("--count", type=int, default=40, help="block changes to send")
    parser.add_argument("--wait-seconds", type=float, default=4.0)
    parser.add_argument("--timeout-seconds", type=float, default=30.0,
                        help="maximum time for retransmits to converge after the loss stops")
    args = parser.parse_args(argv)

    cluster = Cluster(args.a_port, args.b_port, args.password, args.host)
    results: list[dict] = []

    status_a = cluster.run("A", "multiforge status")
    name_a = status_a.split("|", 1)[0].strip()

    def owned_loaded_chunk():
        """A chunk this server owns and the mirror has loaded (no chat truncation)."""
        text = cluster.run("A", "multiforge chunks")
        owned = []
        in_owned = False
        for line in text.splitlines():
            if line.startswith("Chunks owned by"):
                in_owned = True
                continue
            if line.startswith("Loaded chunks"):
                break
            match = re.search(r"world,(-?\d+),(-?\d+)", line)
            if in_owned and match:
                owned.append((int(match.group(1)), int(match.group(2))))
        for chunk_x, chunk_z in owned:
            info = cluster.run("B", f"multiforge chunk {chunk_x} {chunk_z}")
            if "loaded=true" in info:
                return chunk_x, chunk_z
        return None

    chosen = owned_loaded_chunk()
    if chosen is None:
        check(results, "shared-chunk", False,
              f"no chunk owned by {name_a} is loaded on B; connect the bot to {name_a} first")
        return finish(cluster, results, Path(args.out))
    chunk_x, chunk_z = chosen
    base_x = chunk_x * 16
    base_z = chunk_z * 16
    y = 64

    positions = [(base_x + 1 + (index % 14), y, base_z + 1 + (index // 14))
                 for index in range(args.count)]

    cluster.run("A", f"multiforge drop {args.percent}")
    for x, py, z in positions:
        cluster.run("A", f"setblock {x} {py} {z} minecraft:redstone_block")
    chest = f"{base_x + 15} {y} {base_z + 15}"
    cluster.run("A", f"setblock {chest} minecraft:chest")
    cluster.run("A", f"item replace block {chest} container.0 with minecraft:diamond 5")
    time.sleep(args.wait_seconds)
    cluster.run("A", "multiforge drop 0")

    status_b = cluster.run("B", "multiforge status")
    match = STATUS.search(status_b)
    gaps = int(match.group("gaps")) if match else -1
    duplicates = int(match.group("dup")) if match else -1
    check(results, "loss-injected", gaps > 0,
          f"receiver observed {gaps} sequence gaps (duplicates dropped: {duplicates})")

    # Retransmission happens on subsequent ticks; poll instead of assuming a fixed
    # settle time. Acks are not dropped, so the stream must converge.
    deadline = time.time() + args.timeout_seconds
    arrived = 0
    missing = []
    chest_nbt = ""
    while time.time() < deadline:
        arrived = 0
        missing = []
        for x, py, z in positions:
            response = cluster.run("B", f"execute if block {x} {py} {z} minecraft:redstone_block")
            if "Test passed" in response:
                arrived += 1
            else:
                missing.append(f"{x},{py},{z}")
        chest_nbt = cluster.run("B", f"data get block {chest} Items[0].id")
        if arrived == len(positions) and "minecraft:diamond" in chest_nbt:
            break
        time.sleep(2.0)

    check(results, "blocks-converged", arrived == len(positions),
          f"{arrived}/{len(positions)} blocks present on B"
          + (f"; missing {missing[:3]}" if missing else ""))
    check(results, "block-entity-converged", "minecraft:diamond" in chest_nbt,
          chest_nbt.strip())

    return finish(cluster, results, Path(args.out))


if __name__ == "__main__":
    sys.exit(main())
