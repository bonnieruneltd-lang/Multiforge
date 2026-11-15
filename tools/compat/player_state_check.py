#!/usr/bin/env python3
"""Two-server live player-state check for MultiForge.

Runs commands on the server that owns a player (A) and reads the mirrored
player's state on the other server (B), so every assertion covers a live peer
packet and not just local state. The player is a headless `minecraft-protocol`
bot (see `tools/compat/mcbot.js`); the mirror is created by the normal
subscription path when B loads the player's chunk.

Usage::

    # One master, two Forge servers with RCON on 25575/25576, bot on server A:
    node tools/compat/mcbot.js 127.0.0.1 25565 MirrorBot 25599 &
    python3 tools/compat/player_state_check.py --password <rcon-password>

Exits 1 when a check fails. The transcript is the evidence for
`docs/SYNCHRONIZATION.md` and the player-state row in `README.md`.
"""

from __future__ import annotations

import argparse
import re
import socket
import subprocess
import sys
import time
from pathlib import Path

RCON = Path(__file__).with_name("rcon.py")


class Checker:
    """Runs RCON commands on both servers and records pass/fail lines."""

    def __init__(self, args: argparse.Namespace) -> None:
        self.a_port = args.a_port
        self.b_port = args.b_port
        self.password = args.password
        self.player = args.player
        self.control_port = args.control_port
        self.passed = 0
        self.failed = 0

    def rcon(self, port: int, command: str) -> str:
        result = subprocess.run(
            [sys.executable, str(RCON), "--port", str(port),
             "--password", self.password, command],
            capture_output=True, text=True, timeout=30)
        return (result.stdout + result.stderr).strip()

    def value(self, output: str) -> str:
        marker = "entity data: "
        index = output.find(marker)
        return output[index + len(marker):].strip() if index >= 0 else output.strip()

    def bot(self, line: str) -> None:
        with socket.create_connection(("127.0.0.1", self.control_port), timeout=5) as sock:
            sock.sendall((line + "\n").encode())

    def _record(self, name: str, ok: bool, query: str, owner_command: str,
                owner: str, mirror: str, extra: str | None) -> None:
        self.passed += 1 if ok else 0
        self.failed += 0 if ok else 1
        print(f"=== {name}: {'PASS' if ok else 'FAIL'}")
        if owner_command:
            print(f"A> {owner_command}")
        print(f"A> {query}\n   {owner}")
        print(f"B> {query}\n   {mirror}")
        if extra and not ok:
            print(f"   {extra}")
        print()

    def record(self, name: str, ok: bool, detail: str) -> None:
        self.passed += 1 if ok else 0
        self.failed += 0 if ok else 1
        print(f"=== {name}: {'PASS' if ok else 'FAIL'}\n{detail}\n")

    def compare(self, name: str, query: str, owner_command: str | None = None,
                settle: float = 0.8) -> str:
        """Asserts that the owner and the mirror answer a query identically."""
        owner_out = ""
        if owner_command:
            owner_out = self.rcon(self.a_port, owner_command)
            time.sleep(settle)
        owner = self.value(self.rcon(self.a_port, query))
        mirror = self.value(self.rcon(self.b_port, query))
        self._record(name, owner == mirror, query, owner_out, owner, mirror, None)
        return owner

    def changed(self, name: str, query: str, owner_command: str, settle: float = 0.8) -> str:
        """Asserts that the value changed on the owner and arrived on the mirror."""
        before = self.value(self.rcon(self.a_port, query))
        owner_out = self.rcon(self.a_port, owner_command)
        time.sleep(settle)
        owner = self.value(self.rcon(self.a_port, query))
        mirror = self.value(self.rcon(self.b_port, query))
        ok = owner == mirror and owner != before
        self._record(name, ok, query, owner_out, owner, mirror, f"before {before!r}")
        return owner


def effect_pairs(text: str) -> dict[int, int]:
    """Extracts {effect id: amplifier} from an ActiveEffects list."""
    return {int(effect): int(amplifier) for effect, amplifier in
            re.findall(r"Id: (\d+), Amplifier: (\d+)b", text)}



def run(checker: Checker) -> int:
    player = checker.player
    a = checker.a_port
    b = checker.b_port

    # Baseline: the mirror must already exist and its fields must match.
    for field in ("Pos", "Health", "foodLevel", "XpLevel", "Inventory", "EnderItems"):
        checker.compare(f"baseline {field}", f"data get entity {player} {field}")

    # Inventory: main (hotbar), armor and off hand.
    checker.rcon(a, f"clear {player}")
    time.sleep(0.5)
    checker.compare("main inventory after /give", f"data get entity {player} Inventory",
                    f"give {player} minecraft:diamond 5")
    checker.compare("hotbar slot 8", f"data get entity {player} Inventory",
                    f"item replace entity {player} hotbar.8 with minecraft:golden_sword")
    checker.compare("armor head", f"data get entity {player} Inventory",
                    f"item replace entity {player} armor.head with minecraft:golden_helmet")
    checker.compare("off hand", f"data get entity {player} Inventory",
                    f"item replace entity {player} weapon.offhand with minecraft:shield")

    # Hotbar selection is changed by the bot itself (serverbound packet), the
    # same path a real client uses. /data merge refuses to modify player data.
    checker.bot("slot 8")
    time.sleep(1.0)
    checker.compare("selected hotbar slot", f"data get entity {player} SelectedItemSlot")

    # Ender chest.
    checker.compare("ender chest slot 3", f"data get entity {player} EnderItems",
                    f"item replace entity {player} enderchest.3 with minecraft:emerald 3")
    checker.compare("ender chest full list", f"data get entity {player} EnderItems")

    # Experience. The absolute values are not assumed because the player file
    # keeps experience from earlier runs; the check is "changed and mirrored".
    checker.changed("experience level", f"data get entity {player} XpLevel",
                    f"xp add {player} 3 levels")
    checker.changed("experience total", f"data get entity {player} XpTotal",
                    f"xp add {player} 13 points")
    checker.compare("experience progress", f"data get entity {player} XpP")

    # Hunger: the hunger effect drains saturation and then food over time. Food
    # only decreases when the difficulty is not peaceful (vanilla FoodData.tick),
    # so the check switches to normal and restores peaceful afterwards. No vanilla
    # command can write foodLevel on a player directly.
    checker.rcon(a, "difficulty normal")
    time.sleep(1.0)
    checker.rcon(a, f"effect give {player} minecraft:hunger 60 20")
    time.sleep(7.0)
    checker.rcon(a, f"effect clear {player} minecraft:hunger")
    time.sleep(1.0)
    food = checker.compare("hunger after the hunger effect",
                           f"data get entity {player} foodLevel")
    checker.compare("saturation after the hunger effect",
                    f"data get entity {player} foodSaturationLevel")
    checker.record("hunger actually changed on the owner", int(food) < 20,
                   f"foodLevel={food}")
    checker.rcon(a, "difficulty peaceful")

    # Effects: add, update and remove.
    checker.rcon(a, f"effect give {player} minecraft:speed 120 2")
    checker.rcon(a, f"effect give {player} minecraft:jump_boost 120 4")
    time.sleep(0.8)
    owner_effects = effect_pairs(checker.value(
        checker.rcon(a, f"data get entity {player} ActiveEffects")))
    mirror_effects = effect_pairs(checker.value(
        checker.rcon(b, f"data get entity {player} ActiveEffects")))
    ok = owner_effects.get(1) == 2 and mirror_effects.get(1) == 2 \
        and owner_effects.get(8) == 4 and mirror_effects.get(8) == 4
    checker.record("effects added with amplifier", ok,
                   f"A> {owner_effects}\nB> {mirror_effects}")

    checker.rcon(a, f"effect clear {player} minecraft:speed")
    time.sleep(0.8)
    owner_effects = effect_pairs(checker.value(
        checker.rcon(a, f"data get entity {player} ActiveEffects")))
    mirror_effects = effect_pairs(checker.value(
        checker.rcon(b, f"data get entity {player} ActiveEffects")))
    ok = 1 not in owner_effects and 1 not in mirror_effects and 8 in mirror_effects
    checker.record("effect removed", ok, f"A> {owner_effects}\nB> {mirror_effects}")

    print(f"RESULT {checker.passed}/{checker.passed + checker.failed} checks passed")
    return 0 if checker.failed == 0 else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--a-port", type=int, default=25575,
                        help="RCON port of the owning server")
    parser.add_argument("--b-port", type=int, default=25576,
                        help="RCON port of the mirror server")
    parser.add_argument("--password", required=True, help="RCON password (both servers)")
    parser.add_argument("--player", default="MirrorBot", help="bot/player name")
    parser.add_argument("--control-port", type=int, default=25599,
                        help="mcbot.js control port")
    return run(Checker(parser.parse_args()))


if __name__ == "__main__":
    sys.exit(main())
