# N-LIC: Wi-Fi working, mobile data not working

2026-11-03 · `feature/nlic-cellular-profiles-20261103`

## Ground truth

The user has verified that a staged TLS strategy works on Wi-Fi but not on mobile data.
This **does not prove** the same censoring mechanism is used on both connections.

Potential independent causes:
- Mobile carrier routing/IP blocking and CGNAT, IPv6-only / NAT64 / 464XLAT.
- Radio path MTU and oversized TUN packets.
- QUIC/HTTP3 vs TCP TLS, CDN edge differences, retransmission failures.
- Different censorship policy by carrier and time; tunnel/VPN coexistence.
- DNS/Private DNS and IPv4/IPv6 choices.

Neither an aggressive fake TTL nor more split flags can solve all of these.

## Implementation

1. Snapshot non-identifying, underlying link capabilities with Android ConnectivityManager:
   Wi-Fi/mobile/ethernet, IPv4/IPv6 availability, and physical MTU. No SSID,
   carrier name, IP address, IMSI or network identity is retained.
2. Make only **cellular** TUN frames bounded to ~1280–1400 bytes, keeping
   known working Wi-Fi virtual tunnel MTU unchanged. HEV and Android TUN
   must agree. This is an experimental performance measure, not a reliable
   speed optimization across every carrier.
3. Do not falsely advertise unmetered connectivity on cellular.
4. Separate saved strategy results by connection type, preventing a
   Wi-Fi winner from being treated as a mobile-data winner.
5. Use a mobile-first 16-profile catalog with conservative TLS-only profiles,
   staged fallback, and rare TTL-dependent profiles last. Never silently
   change a user's active command or reconnect ongoing streams.
6. If transport switches during a scan, stop early rather than blend Wi-Fi
   success and mobile failures into a misleading ranking.

## Technical boundaries

- Existing N-LIC/ByeDPI mainly changes initial TLS/TCP bytes. It is **not**
  a full zapret2/NFQUEUE engine and has no per-host QUIC/HTTP3 manipulation.
  Zapret2 native strategy strings cannot be passed directly as ByeDPI arguments.
- Telegram client MTProto may connect directly to IP destinations. If a
  destination IP/route is blocked, local TLS desync cannot fix it. A trusted
  external relay (MTProxy, SOCKS, VPN) is a separate Helping subsystem.
- A 256KiB successful proxy test cannot certify the native YouTube app's QUIC
  or CDN behavior. Compare cellular vs Wi-Fi using the same test setup.
- Mobile MTU is not a proven cause; verify with controlled device testing.
- Mobile profile changes may be better or worse for different carriers. Do
  not turn all UDP off merely to make TCP GoogleVideo tests green.
- A successful CI build is **not** a mobile field test or malware safety audit.
