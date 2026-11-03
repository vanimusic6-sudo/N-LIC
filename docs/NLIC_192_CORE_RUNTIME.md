# N-LIC 1.9.2 core integration

Build from `feature/nlic-vpn-runtime-20261103`, continuing the 1.9.1 libbox branch.
The Core Preview workflow includes the pinned 1.14.3 libbox AAR; a plain
ByeDPI build does not contain the subscription engine.

## Implemented

- One Android VpnService owns the TUN. HEV forwards app TCP/UDP to libbox;
  the N-LIC UID stays outside its own routes. Empty or stale app whitelists
  fail instead of capturing the core recursively.
- Import subscriptions, select a server, and connect/apply or disconnect
  directly from the subscription screen, including Android VPN permission.
- LIC-Auto uses native URLTest across up to 12 compatible outbounds, preserving
  established connections. Incompatible entries no longer consume the limit.
- LIC-Bay detours TCP upstream connections through local ByeDPI at 127.0.0.1.
  Hysteria2/TUIC cannot use this detour. An incompatible saved Bay choice
  can always be switched off.
- Readiness uses SOCKS5 ATYP=domain, verified TLS hostname and HTTP 204.
  The health target is not resolved through the device resolver. Bootstrap
  resolution of the VPN server hostname still uses the underlying resolver.
- Core startup has a 50-second overall ceiling and a 60-second client wait;
  the former 15-second client timeout could kill valid slow starts.
- Event-driven default-interface updates and physical interface enumeration
  replace the static empty platform adapter. No periodic Android polling.
- Explicit VPN DNS is routed into HEV even when legacy mode inherited DNS.
- Pinned native API setup uses the correct long type for logMaxLines, a
  random command-server secret and immediate plaintext snapshot removal.
- APK verification reads ZIP entries and ELF headers for libbox, ByeDPI and
  HEV. The old shell expression was over-escaped and rejected a built APK.

## Validation and remaining limits

CI assembles, minifies, lints and runs Release unit tests with libbox included.
Tests cover real loopback SOCKS handshakes, remote domain forwarding,
protocol configuration, URLTest filtering and incompatible Vision settings.
Actual authentication, mobile/Wi-Fi handover, IPv6/DNS/UDP traffic and LIC-Bay
compatibility require a physical Android device with the user's server.
There is no built-in provider, billing, throughput benchmark, automatic
ByeDPI strategy search, or guaranteed kill-switch. Play Protect status has
not been established by these code changes.
