# REALITY stream and ClientHello research · 2026-11-05

Status: experimental, not evidence of DPI circumvention.

## User evidence

An earlier user test completed 18/18 authenticated HTTPS requests on the same
REALITY server (original 6/6 825 ms, Firefox 6/6 757 ms, Chrome 6/6 780 ms).
No failures were observed. Differences in tiny samples cannot prove a
performance improvement.

## Android opt-in streaming check

Choose "Поток · 3 × 512 КиБ" from "Сравнить с LIC-Assist" while disconnected
from VPN with LIC-Auto and LIC-Bay off.

- Serial, same selected REALITY server, credentials, SNI, shortId and VLESS flow.
- Original -> Firefox -> Chrome. No auto-selection and no settings mutation.
- Each Xray session authenticates a localhost SOCKS5 listener with a random
  password, performs certificate-verified HTTPS to Cloudflare's official
  public speed test download endpoint, then consumes at most 512 KiB in memory.
- The test validates HTTP framing, full byte count and bounded timeouts.
  A successful sample proves full delivery of a modest authenticated HTTPS
  response, not continuous video stability or a particular DPI failure.
- Maximum successful body transfer 1.5 MiB + protocol overhead for all samples.
- Opt-in only. Active network socket closes on Stop or Activity departure.
- No response payload, subscription URI, server IP or REALITY keys are logged.

Cloudflare's source documents the download endpoint:
https://github.com/cloudflare/speedtest

## Accurate REALITY first-flight observation

The pinned 2dust/AndroidLibXrayLite v26.10.31 AAR does not expose raw
ClientHello bytes, TCP segments or REALITY's internal handshake timestamps
through the Java controller used here. SOCKS/JSSE timestamps are NOT the
REALITY handshake.

Use an authorized packet capture from your *own* network or server:
Wireshark / tcpdump may save classic .pcap, filtered to the selected
VPN endpoint. Never capture third-party traffic without permission.

Then run locally:

    python3 tools/reality_clienthello_audit.py own-capture.pcap

The offline analyzer summarizes the first TLS-like ClientHello record
length, observed TCP payload segment sizes, missing tail, overlaps and
inter-segment timing. Its JSON output includes no IP addresses, SNI
values or payload bytes. A TLS-like ClientHello is not proof of REALITY.

THE RAW PCAP IS PRIVATE: even though the analyzer discards these fields,
the original capture may contain sensitive traffic, session IDs and
other metadata. Do not upload raw PCAPs to public issues or chats.

Reports of large ClientHello and partial TCP delivery in some Xray
versions are specific observations, not proven rules of this TSPU:
https://github.com/XTLS/Xray-core/issues/6256
https://github.com/XTLS/Xray-core/issues/7306

## Follow-up interpretation

- TCP server port unreachable: investigate reachability and DNS; no DPI verdict.
- TCP reachable, HTTPS fails: investigate Xray credentials, target TLS and
  intermediary behavior; report stage only.
- Small HTTPS succeeds, 512 KiB body fails: transfer instability hypothesis,
  not sufficient evidence of DPI cause.
- All tests pass: keep original profile; do not switch based on small noise.
- PCAP shows a missing follow-on TCP segment: repeat controlled authorized
  captures and compare both endpoints before modifying packetization.

No root, packet injection, server change, TLS downgrade or background
capture was introduced by this branch.
