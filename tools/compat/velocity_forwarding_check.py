#!/usr/bin/env python3
"""Verify Velocity modern forwarding with a simulated proxy login.

The backend asks for `velocity:player_info` during login. This client answers like
Velocity: it builds the version-1 payload (address, UUID, name, properties), signs it
with HMAC-SHA256 and the configured `forwarding.secret`, sends the login plugin answer
and checks that LoginSuccess carries the forwarded UUID.

Usage::

    python3 tools/compat/velocity_forwarding_check.py \\
        --port 25565 --secret testsecret [--name VelocityBot]

Exit code 0 when the forwarded UUID is accepted.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import socket
import struct
import sys
import uuid

CHANNEL = "velocity:player_info"


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
    packet_id = body[0]
    return packet_id, body[1:]


def read_varint_from(data: bytes, offset: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, offset
        shift += 7


def read_string_from(data: bytes, offset: int) -> tuple[str, int]:
    length, offset = read_varint_from(data, offset)
    return data[offset:offset + length].decode("utf-8"), offset + length


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25565)
    parser.add_argument("--secret", required=True)
    parser.add_argument("--name", default="VelocityBot")
    parser.add_argument("--address", default="127.0.0.9")
    args = parser.parse_args(argv)

    forwarded_uuid = uuid.uuid4()
    handshake = (enc_varint(0) + enc_varint(763) + enc_string("localhost")
                 + struct.pack(">H", args.port) + enc_varint(2))
    login_start = enc_varint(0) + enc_string(args.name) + b"\x00"

    sock = socket.create_connection((args.host, args.port), timeout=10)
    sock.sendall(enc_varint(len(handshake)) + handshake)
    sock.sendall(enc_varint(len(login_start)) + login_start)

    for _ in range(30):
        packet_id, payload = read_packet(sock)
        if packet_id == 0x04:
            transaction_id, offset = read_varint_from(payload, 0)
            channel, offset = read_string_from(payload, offset)
            print(f"login plugin request channel={channel} transaction={transaction_id}")
            if channel != CHANNEL:
                raise SystemExit(f"unexpected channel {channel}")
            requested = payload[offset] if offset < len(payload) else 1
            print(f"requested forwarding version={requested}")

            properties = enc_varint(0)
            data = (enc_varint(1) + enc_string(args.address)
                    + forwarded_uuid.bytes + enc_string(args.name) + properties)
            signature = hmac.new(args.secret.encode("utf-8"), data, hashlib.sha256).digest()
            answer = signature + data
            # ServerboundCustomQueryPacket carries the answer as all remaining bytes
            # after the presence flag; it does not use a length prefix.
            response = enc_varint(0x02) + enc_varint(transaction_id) + b"\x01" + answer
            sock.sendall(enc_varint(len(response)) + response)
            print(f"sent signed answer ({len(answer)} bytes, uuid {forwarded_uuid})")
        elif packet_id == 0x02:
            actual = uuid.UUID(bytes=payload[:16])
            name = read_string_from(payload, 16)[0]
            print(f"LOGIN_SUCCESS uuid={actual} name={name}")
            sock.close()
            if actual != forwarded_uuid:
                print(f"FAIL forwarded UUID was not applied: {actual}")
                return 1
            print(f"PASS Velocity modern forwarding applied ({actual})")
            return 0
        elif packet_id == 0x00:
            length = payload[0] if payload else 0
            print("DISCONNECT " + payload[1:1 + length].decode("utf-8", "replace"))
            sock.close()
            return 1

    sock.close()
    print("FAIL no login result")
    return 1


if __name__ == "__main__":
    sys.exit(main())
