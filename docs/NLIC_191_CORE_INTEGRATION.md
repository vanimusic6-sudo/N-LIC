# N-LIC 1.9.1 — actual sing-box outbound contract, preflight prototype

Branch `feature/nlic-singbox-plan-20261103` is stacked onto `feature/nlic-subscription-core-20261103`.

## Changes implemented
- Strict outbound compiler supporting **plain TCP VLESS + TLS/REALITY** and
  **Trojan + TLS** only. It maps server, server_port, UUID/password, SNI,
  optional REALITY public_key / short_id and optional uTLS chrome fingerprint.
- Full original subscription URI stays in the Android Keystore encrypted vault.
  Generated JSON is not exported, shown, persisted or logged.
- Both TLS and REALITY are always enabled; unsupported security settings,
  custom transport types (WS/gRPC/H2/HTTPUpgrade), unknown query keys, absent
  REALITY keys, invalid UUIDs and insecure downgrade requests fail closed.
  Unsupported profiles remain visible in subscription manager but cannot
  pass **local configuration compatibility check**.
- LIC-Bay design now creates a concrete sing-box `socks` outbound tagged
  `lic-bay-local-socks` at `127.0.0.1:1080`, and sets `detour` on the
  selected VLESS/Trojan outbound. This is a JSON configuration-only feature:
  the existing ByeDPI local SOCKS can supply it once native libbox is pinned
  and integrated in the Android VPN core.
- LIC-Bay is never applied to QUIC/UDP-first Hysteria2 or TUIC by default.
- Added local, credential-redacted **Validate VPN profile readiness** control
  for selected node. It does not establish a tunnel or send network traffic.
- Unit tests cover REALITY+LIC-Bay JSON, standard Trojan TLS, rejection of
  non-TCP paths and insecure settings.
- Version 1.9.1 (1828), DEV Release build + Quality Gate + CodeQL.

## Why not deliver a fake VPN
A proper rootless client needs **one** Android VpnService with TUN ownership
and a pinned audited libbox (or a comparable native core). The pre-existing
N-LIC `HEV + ByeDPI` engine only implements a local SOCKS5 bypass, not
authenticated VLESS, Trojan or HY2. Merely generating sing-box JSON without
a libbox process would leave network traffic unprotected.

## Next engineering milestone
1. Pin an audited sing-box/libbox revision and build Android gomobile bindings
   from source for arm64; ship exact reproducible build hashes and license text.
2. Add a new VPN mode using that core's platform interface, TUN FD handoff
   and protected outbound sockets; only one Android VPN registration.
3. Open a REAL authenticated VLESS/REALITY/Trojan session from N-LIC and
   verify browser, DNS, IPv6, UDP and failure/kill-switch behavior.
4. Enable LIC-Bay detour only after tunnel success and compare direct vs
   GoodbyeDPI path under provider restrictions; never turn it on blindly.
5. Upgrade LIC-Auto from TCP-only to authenticated HTTPS probe **through each
   actual encrypted outbound**, with carrier-aware ranking, data caps and
   failover hysteresis.

References:
- https://sing-box.sagernet.org/configuration/outbound/vless/
- https://sing-box.sagernet.org/configuration/outbound/trojan/
- https://sing-box.sagernet.org/configuration/outbound/socks/
- https://sing-box.sagernet.org/configuration/shared/dial/
- https://sing-box.sagernet.org/clients/android/features/
