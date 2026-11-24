#!/usr/bin/env python3
"""Verify that cross-server player chat keeps its sender decoration.

A message typed on server A is forwarded to server B and re-broadcast there as a
system chat line. Before the fix in ``PlayerSyncManager.broadcastChat`` the
forwarded component was the raw, undecorated text, so server B showed bare text
with no sender (and lost any formatting a server-side chat mod such as Beautified
Chat applied). ``broadcastChat`` now rebuilds the vanilla ``chat.type.text`` line
(``<name> text``), so the received system chat must carry the sender's name and the
``chat.type.text`` translation key.

This check drives the two bots that ``tools/ci/live_cluster.sh`` already starts:

* bot A's control socket sends one chat line containing a random token;
* bot B's log is polled for the forwarded line;
* the received component must contain both the token and the sender name.

Run standalone (both servers and bots already up)::

    python3 tools/compat/chat_format_check.py \
        --a-control 25599 --b-log .runtime/live-cluster/bot-b.log \
        --sender SyncBotA --out compat/evidence

``--self-test`` exercises the parsing/verdict logic against recorded sample lines
without any server, so the checker itself is testable in isolation.

Exit code 0 when the decoration is present, 1 otherwise, 2 on usage error.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import socket
import sys
import time
from pathlib import Path

SYSTEM_LINE = re.compile(r"\[bot\] SYSTEM (.*)$", re.MULTILINE)
CHATIN_LINE = re.compile(r"\[bot\] CHATIN (.*)$", re.MULTILINE)


def send_control(port: int, command: str, host: str = "127.0.0.1", timeout: float = 5.0) -> None:
    """Send one newline-terminated control command to a running mcbot."""
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.sendall((command.rstrip("\n") + "\n").encode("utf-8"))


def read_lines(path: Path) -> list[str]:
    """Return every received-chat line (system or player) in a bot log."""
    if not path.exists():
        return []
    text = path.read_text(errors="replace")
    return SYSTEM_LINE.findall(text) + CHATIN_LINE.findall(text)


def line_has_decoration(line: str, token: str, sender: str) -> bool:
    """True when a forwarded chat line carries both the token and the sender.

    The sender must appear, which is exactly what the pre-fix bare-text forward
    lacked.
    """
    return token in line and sender in line


def find_token(lines: list[str], token: str) -> str | None:
    """Return the first line that contains the token, if any."""
    for line in lines:
        if token in line:
            return line
    return None



def run_self_test() -> int:
    """Validate the verdict logic against known good/bad sample lines."""
    token = "tok-abc123"
    cases = [
        # (line, sender, expected_pass, description)
        ('{"translate":"chat.type.text","with":[{"text":"SyncBotA"},{"text":"tok-abc123"}]}',
         "SyncBotA", True, "decorated translatable line keeps sender + token"),
        ('{"text":"<SyncBotA> tok-abc123"}', "SyncBotA", True, "pre-rendered decorated line keeps sender"),
        ('tok-abc123', "SyncBotA", False, "pre-fix bare text loses the sender"),
        ('{"text":"<Someone> tok-abc123"}', "Someone", True, "any present name counts"),
    ]
    failures = 0
    for line, sender, expected, desc in cases:
        got = line_has_decoration(line, token, sender)
        ok = got == expected
        print(f"[{'PASS' if ok else 'FAIL'}] self-test: {desc} (got {got}, expected {expected})")
        if not ok:
            failures += 1
    return 1 if failures else 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--a-control", type=int, default=25599,
                        help="bot A control socket port (sends the chat line)")
    parser.add_argument("--b-log", type=Path, default=None,
                        help="path to bot B's log that receives the forwarded line")
    parser.add_argument("--sender", default="SyncBotA",
                        help="name of the sending bot (must appear in the forwarded line)")
    parser.add_argument("--out", type=Path, default=Path("compat/evidence"))
    parser.add_argument("--wait-seconds", type=float, default=6.0)
    parser.add_argument("--self-test", action="store_true",
                        help="run the parsing/verdict logic against sample lines and exit")
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_test()

    if args.b_log is None:
        print("error: --b-log is required (or use --self-test)", file=sys.stderr)
        return 2

    args.out.mkdir(parents=True, exist_ok=True)
    token = f"chatfmt-{int(time.time())}"
    transcript: list[dict] = []

    print(f"== sending '{token}' from bot A (control {args.a_control})")
    send_control(args.a_control, f"chat {token}")
    transcript.append({"step": "send", "token": token, "controlPort": args.a_control})

    deadline = time.time() + args.wait_seconds
    received = None
    while time.time() < deadline:
        received = find_token(read_lines(args.b_log), token)
        if received is not None:
            break
        time.sleep(0.5)

    passed = received is not None and line_has_decoration(received, token, args.sender)
    detail = (received.strip()[:200] if received
              else f"no forwarded line containing {token!r} in {args.b_log}")
    transcript.append({"step": "receive", "line": received, "sender": args.sender, "passed": passed})

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    md = args.out / f"chat-format-{stamp}.md"
    js = args.out / f"chat-format-{stamp}.json"
    md.write_text(
        f"# Cross-server chat formatting evidence {stamp}\n\n"
        f"Sentence: bot A sent `{token}`; bot B must receive a decorated line that keeps "
        f"the sender `{args.sender}` (the pre-fix behavior forwarded bare text).\n\n"
        f"## Result\n\n"
        f"- Verdict: **{'PASS' if passed else 'FAIL'}**\n"
        f"- Received on B: `{received}`\n"
        f"- Detail: {detail}\n\n"
        f"## Transcript\n\n```json\n{json.dumps(transcript, indent=2)}\n```\n"
    )
    js.write_text(json.dumps({"passed": passed, "token": token, "received": received,
                              "sender": args.sender, "transcript": transcript}, indent=2))
    print(f"[{'PASS' if passed else 'FAIL'}] cross-server-chat-format: {detail}")
    print(f"== evidence written to {md}")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
