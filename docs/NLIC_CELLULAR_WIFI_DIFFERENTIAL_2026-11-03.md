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
3. On mobile links with IPv6, capture IPv6 packets in the N-LIC VPN instead of
   leaving IPv6 traffic outside the configured VPN routes (which Android can
   block entirely depending on VPN family configuration).
   This is configurable and can be disabled if a particular carrier breaks.
4. Mark cellular VPN connections as metered. Preserve the Wi-Fi behavior.
5. When a mobile carrier is IPv6-only or advertises NAT64, allow Android to
   inherit the carrier DNS64 resolver instead of overriding it with the
   bundled default 1.1.1.1. Do not override a customized DNS choice.
   This is a reversible option with a guarded API-30 NAT64 check.
6. Separate saved strategy results by connection type, preventing a
   Wi-Fi winner from being treated as a mobile-data winner.
7. Use a mobile-first 16-profile catalog with conservative TLS-only profiles,
   staged fallback, and rare TTL-dependent profiles last. Never silently
   change a user's active command or reconnect ongoing streams.
8. If transport switches during a scan, stop early rather than blend Wi-Fi
   success and mobile failures into a misleading ranking.
9. Offer a **default-off** separate profile per transport. Explicitly
   applying a command or editing it saves the slot for the current link.
   Only a newly started VPN uses the saved slot; no live flow is restarted.
   Unknown links fall back to the ordinary user command.

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
