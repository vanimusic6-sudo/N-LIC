#!/usr/bin/env python3
"""Synthetic packet-only test for privacy-preserving TLS/REALITY PCAP audit."""
import importlib.util
import struct
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    "reality_audit", ROOT / "tools" / "reality_clienthello_audit.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def packet(payload, seq):
    ethernet = b"\x11" * 12 + b"\x08\x00"
    length = 20 + 20 + len(payload)
    ip = (b"\x45\x00" + struct.pack("!H", length)
          + b"\x00\x00\x40\x00\x40\x06\x00\x00"
          + b"\x0a\x00\x00\x01" + b"\x0a\x00\x00\x02")
    tcp = (struct.pack("!HHI", 12345, 443, seq)
           + b"\x00\x00\x00\x00\x50\x18\x20\x00\x00\x00\x00\x00")
    return ethernet + ip + tcp + payload


def pcap(records):
    header = b"\xd4\xc3\xb2\xa1" + struct.pack(
        "<HHIIII", 2, 4, 0, 0, 65535, 1)
    for usec, seq, data in records:
        frame = packet(data, seq)
        header += struct.pack("<IIII", 1, usec, len(frame), len(frame))
        header += frame
    return header


record = b"\x16\x03\x01" + struct.pack("!H", 1743) + b"\x01" + bytes(1742)
assert len(record) == 1748
with tempfile.TemporaryDirectory() as tmp:
    path = Path(tmp) / "test.pcap"
    path.write_bytes(pcap([(0, 1000, record[:1452]),
                           (3800, 2452, record[1452:])]))
    data = mod.audit(path)
    assert data["client_hello_like_flows"] == 1, data
    hello = data["first_flights"][0]
    assert hello["tls_record_bytes"] == 1748, hello
    assert hello["tcp_payload_lengths"] == [1452, 296], hello
    assert hello["record_complete"] is True, hello
    assert hello["tcp_payload_segments_seen"] == 2, hello
    assert hello["first_to_last_segment_ms"] == 3.8, hello
    assert "10.0.0.1" not in str(data)
    assert "10.0.0.2" not in str(data)

    path.write_bytes(pcap([(0, 1000, record[:1452])]))
    hello = mod.audit(path)["first_flights"][0]
    assert not hello["record_complete"]
    assert hello["captured_contiguous_bytes"] == 1452

    path.write_bytes(b"\x0a\x0d\x0d\x0a" + bytes(40))
    try:
        mod.audit(path)
    except ValueError as e:
        assert "pcapng" in str(e)
    else:
        raise AssertionError("pcapng should explicitly fail")

print("REALITY PCAP audit: synthetic segmentation, loss and privacy OK")
