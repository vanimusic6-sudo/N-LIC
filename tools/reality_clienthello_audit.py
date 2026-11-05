#!/usr/bin/env python3
"""Read a *locally obtained, authorized* PCAP and summarize TLS first flights.

For N-LIC REALITY research: record length, how many TCP payload segments
were needed, whether the tail arrived, and time from first to last piece.

No payload, IP address, TLS SNI or cryptographic material is emitted.
TLS-looking ClientHello is not by itself proof of REALITY or of DPI rules.

Supports classic .pcap with Ethernet/IPv4, Ethernet/IPv6 (no extension
headers) or RAW IPv4/IPv6 packets. Does not parse pcapng; fail explicitly.
"""
from __future__ import annotations

import argparse
import json
import struct
import sys
from collections import defaultdict
from pathlib import Path

MAX_FILE = 64 * 1024 * 1024
MAX_PACKET = 128 * 1024
MAX_FLOWS = 96
MAX_TLS = 18432
MAX_SEGMENTS = 64


def tcp_packet(frame: bytes, linktype: int):
    if linktype == 1:
        if len(frame) < 14:
            return None
        offset = 14
        ethernet = struct.unpack_from("!H", frame, 12)[0]
        for _ in range(2):
            if ethernet not in (0x8100, 0x88A8):
                break
            if len(frame) < offset + 4:
                return None
            ethernet = struct.unpack_from("!H", frame, offset + 2)[0]
            offset += 4
        if ethernet not in (0x0800, 0x86DD):
            return None
    elif linktype == 101:
        offset = 0
    else:
        return None
    if offset >= len(frame):
        return None
    version = frame[offset] >> 4
    if version == 4:
        if len(frame) < offset + 20:
            return None
        ihl = (frame[offset] & 15) * 4
        if ihl < 20 or offset + ihl > len(frame):
            return None
        if frame[offset + 9] != 6:
            return None
        ip_size = struct.unpack_from("!H", frame, offset + 2)[0]
        if ip_size < ihl + 20 or len(frame) < offset + ip_size:
            return None
        frame = frame[:offset + ip_size]
        # IP fragmentation is NOT TCP segmentation. Deliberately skip
        # fragmented IPv4 datagrams rather than mis-reassemble them.
        flags_frag = struct.unpack_from("!H", frame, offset + 6)[0]
        if flags_frag & 0x3FFF:
            return None
        src = frame[offset + 12:offset + 16]
        dst = frame[offset + 16:offset + 20]
        tcp = offset + ihl
    elif version == 6:
        if len(frame) < offset + 40 or frame[offset + 6] != 6:
            return None
        ip_size = struct.unpack_from("!H", frame, offset + 4)[0] + 40
        if len(frame) < offset + ip_size:
            return None
        frame = frame[:offset + ip_size]
        src = frame[offset + 8:offset + 24]
        dst = frame[offset + 24:offset + 40]
        tcp = offset + 40
    else:
        return None
    if len(frame) < tcp + 20:
        return None
    port1, port2, seq = struct.unpack_from("!HHI", frame, tcp)
    header_length = ((frame[tcp + 12] >> 4) & 15) * 4
    if header_length < 20 or tcp + header_length > len(frame):
        return None
    payload = frame[tcp + header_length:]
    if not payload:
        return None
    # Addresses are kept only as opaque ephemeral flow keys in memory.
    return (src, port1, dst, port2), seq, payload


def is_start(payload: bytes):
    return (len(payload) >= 6 and payload[0] == 0x16
            and payload[1] == 0x03 and payload[2] in (0, 1, 2, 3)
            and payload[5] == 0x01)


def audit(path: Path):
    if path.stat().st_size > MAX_FILE:
        raise ValueError("Capture exceeds 64 MiB; use a smaller authorized sample")
    with path.open("rb") as f:
        magic = f.read(4)
        if magic in (b"\xd4\xc3\xb2\xa1", b"\x4d\x3c\xb2\xa1"):
            order = "<"
            time_divisor = 1_000_000 if magic[0] == 0xD4 else 1_000_000_000
        elif magic in (b"\xa1\xb2\xc3\xd4", b"\xa1\xb2\x3c\x4d"):
            order = ">"
            time_divisor = 1_000_000 if magic[0] == 0xA1 and magic[2] == 0xC3 else 1_000_000_000
        else:
            raise ValueError("Expected classic PCAP (not pcapng)")
        header = f.read(20)
        if len(header) != 20:
            raise ValueError("Truncated PCAP header")
        linktype = struct.unpack_from(order + "I", header, 16)[0]
        if linktype not in (1, 101):
            raise ValueError("Unsupported PCAP link type; use Ethernet or raw IP")
        flows = {}
        packets = 0
        while True:
            record = f.read(16)
            if not record:
                break
            if len(record) != 16:
                raise ValueError("Truncated PCAP packet header")
            sec, subsec, size, _original = struct.unpack(order + "IIII", record)
            if size > MAX_PACKET:
                raise ValueError("PCAP packet too large")
            frame = f.read(size)
            if len(frame) != size:
                raise ValueError("Truncated PCAP payload")
            packets += 1
            if packets > 250000:
                raise ValueError("Too many packets")
            parsed = tcp_packet(frame, linktype)
            if parsed is None:
                continue
            key, seq, payload = parsed
            if key not in flows:
                if not is_start(payload) or len(flows) >= MAX_FLOWS:
                    continue
                expected = (payload[3] << 8) + payload[4] + 5
                if expected <= 9 or expected > MAX_TLS:
                    continue
                flows[key] = {
                    "seq": seq, "expected": expected,
                    "parts": {}, "segment_lengths": [], "timestamps": [],
                    "duplicate": False,
                }
            flow = flows[key]
            if len(flow["segment_lengths"]) >= MAX_SEGMENTS:
                continue
            relative = (seq - flow["seq"]) & 0xFFFFFFFF
            if relative >= flow["expected"]:
                continue
            useful = payload[:min(len(payload), flow["expected"] - relative)]
            if not useful:
                continue
            overlapped = False
            for index, value in enumerate(useful):
                pos = relative + index
                if pos in flow["parts"]:
                    overlapped = True
                else:
                    flow["parts"][pos] = value
            if overlapped:
                flow["duplicate"] = True
            flow["segment_lengths"].append(len(useful))
            flow["timestamps"].append(sec + subsec / time_divisor)

    out = []
    for flow in flows.values():
        expected = flow["expected"]
        complete = len(flow["parts"]) == expected
        times = flow["timestamps"]
        out.append({
            "tls_record_bytes": expected,
            "captured_contiguous_bytes": len(flow["parts"]),
            "record_complete": complete,
            "tcp_payload_lengths": flow["segment_lengths"],
            "tcp_payload_segments_seen": len(flow["segment_lengths"]),
            "retransmission_or_overlap_seen": flow["duplicate"],
            "first_to_last_segment_ms": (round((max(times) - min(times)) * 1000, 2)
                                         if len(times) > 1 else 0),
        })
    return {
        "warning": "TCP/TLS first-flight evidence only; not proof of REALITY or DPI",
        "packets_read": packets, "client_hello_like_flows": len(out),
        "first_flights": out
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pcap", type=Path, help="Authorized classic PCAP file")
    args = parser.parse_args()
    try:
        result = audit(args.pcap)
    except (OSError, ValueError, struct.error) as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
