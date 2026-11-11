#!/usr/bin/env python3
"""Minimal Minecraft RCON client used by the MultiForge compatibility harness.

Only the three packet types the harness needs are implemented (login, command,
command response). The protocol is documented at
<https://wiki.vg/RCON>; it is little endian with a 10 byte header:

    length(int32) requestId(int32) type(int32) payload(ascii, NUL) terminator(NUL)

Usage::

    python3 tools/compat/rcon.py --port 25575 --password mfcompat "multiforge status"

Exits 1 when authentication fails or the server closes the connection.
"""

from __future__ import annotations

import argparse
import socket
import struct
import sys

LOGIN = 3
COMMAND = 2
RESPONSE = 0


class RconError(RuntimeError):
    """Raised when the server rejects or terminates an RCON session."""


def _send(sock: socket.socket, request_id: int, packet_type: int, payload: str) -> None:
    body = struct.pack("<ii", request_id, packet_type) + payload.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(body)) + body)


def _receive(sock: socket.socket) -> tuple[int, int, str]:
    header = _read_exact(sock, 4)
    length = struct.unpack("<i", header)[0]
    if length < 10 or length > 4 * 1024 * 1024:
        raise RconError(f"invalid RCON frame length {length}")
    body = _read_exact(sock, length)
    request_id, packet_type = struct.unpack("<ii", body[:8])
    payload = body[8:-2].decode("utf-8", errors="replace")
    return request_id, packet_type, payload


def _read_exact(sock: socket.socket, count: int) -> bytes:
    chunks = []
    remaining = count
    while remaining:
        chunk = sock.recv(remaining)
        if not chunk:
            raise RconError("server closed the connection")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def command(host: str, port: int, password: str, text: str, timeout: float = 10.0) -> str:
    """Authenticate, run one command and return the response text.

    A new TCP connection is opened per call; the harness only issues a handful
    of commands per run, so connection reuse is not worth the extra state.
    """
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        _send(sock, 1, LOGIN, password)
        request_id, _, _ = _receive(sock)
        if request_id == -1:
            raise RconError("authentication failed")
        _send(sock, 2, COMMAND, text)
        _, _, payload = _receive(sock)
        return payload


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", required=True)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("command", help="command to execute, without a leading slash")
    args = parser.parse_args(argv)
    try:
        print(command(args.host, args.port, args.password, args.command, args.timeout))
    except (RconError, OSError) as error:
        print(f"RCON error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
