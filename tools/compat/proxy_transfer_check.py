#!/usr/bin/env python3
"""Verify the built-in proxy and cross-server player-data transfer.

The script logs a headless client in through the master's TCP proxy, checks which
backend accepted it, puts an item into the inventory, disconnects, reconnects
directly to the *other* backend with the same name, and asserts that the item
followed the player. It also proves that the proxy forwards the connection and
that duplicate-login prevention releases the claim on logout.

Usage::

    python3 tools/compat/proxy_transfer_check.py \
        --proxy-port 35555 --a-port 25565 --b-port 25566 \
        --a-rcon 25575 --b-rcon 25576 --password mf

Exit code 0 when every assertion passes. The client is a minimal implementation
of the 1.20.1 login handshake (protocol 763); no game client is required.
"""

from __future__ import annotations

import argparse
import socket
import struct
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from rcon import RconError, command  # noqa: E402


def enc_varint(value: int) -> bytes:
    value &= 0xFFFFFFFF
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def enc_string(text: str) -> bytes:
    raw = text.encode("utf-8")
    return enc_varint(len(raw)) + raw


def read_varint(sock: socket.socket) -> int:
    result = 0
    shift = 0
    while True:
        chunk = sock.recv(1)
        if not chunk:
            raise EOFError("connection closed")
        byte = chunk[0]
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result
        shift += 7


def read_exact(sock: socket.socket, count: int) -> bytes:
    data = b""
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise EOFError("connection closed")
        data += chunk
    return data


def read_packet(sock: socket.socket) -> tuple[int, bytes]:
    length = read_varint(sock)
    body = read_exact(sock, length)
    return body[0], body[1:]


def login(port: int, name: str, host: str = "127.0.0.1") -> socket.socket:
    """Logs in and waits for LoginSuccess; raises when the server disconnects."""
    handshake = (enc_varint(0) + enc_varint(763) + enc_string("localhost")
                 + struct.pack(">H", port) + enc_varint(2))
    login_start = enc_varint(0) + enc_string(name) + b"\x00"
    sock = socket.create_connection((host, port), timeout=10)
    sock.sendall(enc_varint(len(handshake)) + handshake)
    sock.sendall(enc_varint(len(login_start)) + login_start)
    for _ in range(30):
        packet_id, payload = read_packet(sock)
        if packet_id == 0x02:
            return sock
        if packet_id == 0x00:
            length = payload[0] if payload else 0
            raise SystemExit(f"{host}:{port} disconnected during login: "
                             + payload[1:1 + length].decode("utf-8", "replace"))
    sock.close()
    raise SystemExit(f"{host}:{port} did not send LoginSuccess")



def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--proxy-port", type=int, required=True)
    parser.add_argument("--a-port", type=int, required=True)
    parser.add_argument("--b-port", type=int, required=True)
    parser.add_argument("--a-rcon", type=int, required=True)
    parser.add_argument("--b-rcon", type=int, required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--a-name", default="live1")
    parser.add_argument("--b-name", default="live2")
    parser.add_argument("--name", default="ProxyBot")
    args = parser.parse_args(argv)

    name = args.name
    results: list[tuple[str, bool, str]] = []

    def check(label: str, passed: bool, detail: str) -> None:
        results.append((label, passed, detail))
        print(("[PASS] " if passed else "[FAIL] ") + f"{label}: {detail}")

    print(f"--- login through proxy 127.0.0.1:{args.proxy_port} as {name}")
    proxy_socket = login(args.proxy_port, name)
    wait_for(lambda: owner_server(args.a_rcon, args.password, name) is not None)
    owner = owner_server(args.a_rcon, args.password, name)
    check("proxy-login", owner in (args.a_name, args.b_name),
          f"cluster owner={owner}")

    first_side = "A" if owner == args.a_name else "B"
    first_rcon = args.a_rcon if first_side == "A" else args.b_rcon
    other_port = args.b_port if first_side == "A" else args.a_port
    other_rcon = args.b_rcon if first_side == "A" else args.a_rcon
    first_port = args.a_port if first_side == "A" else args.b_port

    rcon(first_rcon, args.password, f"give {name} minecraft:diamond 3")
    time.sleep(1.0)
    inventory = rcon(first_rcon, args.password, f"data get entity {name} Inventory")
    check("inventory-on-first-server", "minecraft:diamond" in inventory,
          f"side={first_side} {inventory[:400]}")

    print(f"--- logout from {first_side} (:{first_port}) and reconnect to the other server")
    proxy_socket.close()
    wait_for(lambda: not online_player(first_rcon, args.password, name), 20.0)
    check("logout-releases-claim", owner_server(first_rcon, args.password, name) is None,
          "cluster registry no longer lists the player")

    # Give the logout save a moment to reach the master, then log in directly on
    # the other server (the same name has the same offline UUID on every server).
    time.sleep(3.0)
    direct = login(other_port, name)
    wait_for(lambda: owner_server(other_rcon, args.password, name) ==
             (args.b_name if first_side == "A" else args.a_name), 20.0)
    new_owner = owner_server(other_rcon, args.password, name)
    check("reconnect-other-server", new_owner == (args.b_name if first_side == "A" else args.a_name),
          f"cluster owner={new_owner} (port {other_port})")

    followed = ""
    for _ in range(10):
        followed = rcon(other_rcon, args.password, f"data get entity {name} Inventory")
        if "minecraft:diamond" in followed:
            break
        time.sleep(1.0)
    check("player-data-follows-transfer", "minecraft:diamond" in followed, followed[:400])

    rcon(other_rcon, args.password, f"clear {name}")
    direct.close()
    time.sleep(1.0)

    passed = sum(1 for _, ok, _ in results if ok)
    print(f"RESULT {passed}/{len(results)} checks passed")
    return 0 if passed == len(results) else 1



def rcon(port: int, password: str, text: str) -> str:
    try:
        return command("127.0.0.1", port, password, text, 10.0)
    except (RconError, OSError) as error:
        return f"<RCON ERROR: {error}>"


def online_player(rcon_port: int, password: str, name: str) -> bool:
    listing = rcon(rcon_port, password, "list")
    return name in listing


def owner_server(rcon_port: int, password: str, name: str) -> str | None:
    """Returns the server that owns the player according to /multiforge players."""
    listing = rcon(rcon_port, password, "multiforge players")
    for line in listing.splitlines():
        if f" {name} @ " in line:
            return line.split(" @ ", 1)[1].split(" ", 1)[0].strip()
    return None


def wait_for(predicate, timeout: float = 15.0, interval: float = 0.5) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return False


if __name__ == "__main__":
    sys.exit(main())
