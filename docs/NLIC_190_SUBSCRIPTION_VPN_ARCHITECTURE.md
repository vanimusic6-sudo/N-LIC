# N-LIC 1.9 — changing the product from ByeDPI fork to subscription VPN client

Date: 2026-11-03. Branch: `feature/nlic-subscription-core-20261103`.
**This branch is an experimental subscription manager, NOT a working remote VPN client.**

## Product direction

N-LIC becomes an independent Android subscription VPN client inspired by
v2RayTun, with a first-class anti-DPI feature rather than a collection of
command-line strings.

### Modes and connection chain

1. **LIC-Core (main VPN engine)**

   `Android app → one Android VpnService/TUN → sing-box/libbox core → encrypted remote VPN/proxy → internet`

   Planned protocols: VLESS/REALITY, Trojan, Shadowsocks, VMess, Hysteria2,
   and TUIC when the selected audited core supports them. Importing a URI
   does not prove the selected binary supports its transport/query features.

2. **LIC-Auto (best working server)**

   - Stage 0 (implemented now): user-initiated bounded TCP handshake testing,
     2 attempts, max 24 nodes and concurrency 3, **no secret transmission**.
     HY2/TUIC are not ranked by TCP because they are UDP-first. An endpoint
     that accepts TCP is not necessarily a working authenticated VPN server.
   - Stage 1 (requires native engine): open an actual authenticated proxy
     stream and request a small fixed public HTTPS response through each
     outbound; measure failure rate and latency, avoid fake HTTP-204-only
     success. Reference design: sing-box `urltest` outbound with interval,
     tolerance, and `interrupt_exist_connections=false`.
   - Stage 2: a bounded post-connect throughput test (only with explicit
     consent, traffic cap). On Wi-Fi and cellular maintain separate verified
     rankings. Do not rotate active connections without consent. Never mark
     all nodes “blocked” when there is NO_NETWORK_ROUTE.
   - Stage 3: optionally pick the best reachable node, subject to user policy,
     data quota and a stable-switch hysteresis to avoid battery-draining loops.

3. **LIC-Bay (protect only the carrier-facing VPN connection)**

   For TCP-based encrypted outbounds with supported dial hooks:

   `apps → Android VPN/TUN (once) → sing-box VLESS/Trojan outbound`
   `                                      └ upstream dial/detour → local ByeDPI SOCKS5 → remote VPN endpoint`

   The actual VPN encryption (VLESS/REALITY/Trojan) is end-to-end between the
   VPN core and remote endpoint. ByeDPI manipulates only its outer TCP traffic.
   ByeDPI itself is **not encryption**, not an IP-block workaround, and may
   break certificate/REALITY handshakes if a profile is too aggressive.

   sing-box documents a dial field `detour` with an upstream outbound tag.
   A local SOCKS5 outbound can be referenced from a VLESS/Trojan dialer.
   This requires validated native integration, correct DNS resolution,
   proper loopback socket protection and compatibility testing; it is not
   enough to put two Android VPNs on the phone.

   The user can opt for:
   - Off: fastest direct encrypted tunnel, preserve existing stable connection.
   - Auto: *only after tests* decide to enable outbound detour.
   - On: per-node manual profile, with a clear no-connect/fallback policy.

   UDP-first outbounds (Hysteria2/TUIC) need a separate QUIC-aware path;
   SOCKS/TCP ByeDPI flags must not be blindly applied. An actual blocked IP
   cannot be made routable without an accessible alternative transport/relay.

### One VPN service, not two

Android normally supports only one active VPN per profile. N-LIC must own the
VpnService and compose ByeDPI in-process (local SOCKS5 TCP path) with a VPN
core *before* bringing up its TUN. The existing VpnService + HEV/TUN remains
active only in legacy ByeDPI mode until the native adapter is fully tested.

Do not route the app's own process through the TUN; use VpnService.protect()
or an explicitly bound underlying network for the core's outbound sockets.
Never connect via a hidden fail-open direct fallback while a user-selected
kill-switch is on.

### Imported subscription format (1.9 foundation)

Implemented:
- explicit HTTPS subscriptions with bounded <=512 KiB fetch, redirects disabled,
  userinfo/fragment disallowed on URL, local/private-address DNS resolution
  blocked when fetching. No background polling and no HTTP downloads.
- manual multi-line import and bounded base64 subscription decoding.
- parse VLESS, VMess JSON/Base64, Trojan, Shadowsocks/SIP002, HY2 and TUIC
  into non-connecting candidate entries, with deduplication and descriptive
  labels. Some exotic transports/plugins not supported by parser yet.
- store original URLs and node credentials in a **single Android Keystore
  AES-256-GCM app-private vault** (API 23+, no unencrypted fallback).
  Explicit Delete also removes the crypto key. Backup is disabled.
- user-triggered TCP-only LIC-Auto shortlist; not an authenticated VPN test.
- LIC-Bay compatibility explanation in UI, strictly not enabled yet.
- old ByeDPI core, strategy presets and existing Wi-Fi behavior untouched.
- added localized strings and automated tests for parsing and policy.

Not implemented: native sing-box/libbox binary; actual new encrypted VPN;
functional LIC-Bay detour; secure auth against subscription provider API
besides HTTPS; push updates and auto-polling; commercial subscriptions/billing;
support for sing-box JSON/Clash YAML profile import, subscription-userinfo
quota headers; reliably protected battery-efficient background checks.

### Safety and licensing

- Subscription URLs and configuration URIs contain secrets. Never log or
  report them and never upload them to N-LIC servers. N-LIC itself is not
  selling access or providing hosted servers. Provider subscription is separate.
- “Subscriptions” here means *importing paid/free third-party service
  configuration*, NOT charging for the N-LIC app.
- sing-box is GPL-3.0-or-later and includes a special naming/association
  restriction. Verify the full license implications, distribution obligations
  and compatibility of the full app before adding core artifacts.
- Play Protect concerns for previous N-LIC builds remain unresolved; code
  compilation is not a device security certification.
- Existing subscriptions are local secrets, not synchronized.
- Research references:
  - https://github.com/SagerNet/sing-box
  - https://sing-box.sagernet.org/configuration/shared/dial/
  - https://sing-box.sagernet.org/configuration/outbound/urltest/
  - https://sing-box.sagernet.org/clients/android/features/
  - https://github.com/hufrea/byedpi
  - https://github.com/LXST-CODE/v2RayTun

### Production integration milestones

M1. Pin audited Android-compatible libbox core with SBOM, license review,
    reproducible source builds and release signature validation.
M2. Add real VPN mode and TUN handoff using a single Android VpnService;
    first connect one VLESS/REALITY endpoint, then one Trojan, then other
    authenticated protocols; verify UDP and IPv6 per protocol.
M3. Implement LIC-Auto over **actual encrypted outbound**, bound to correct
    Wi-Fi/cellular network. Add stable failover only with explicit opt-in.
M4. Enable LIC-Bay on supported TCP outbounds, with per-profile on/off/auto,
    integration tests for packet leaks and loop prevention.
M5. Security audit with threat model, DNS leak checks, private DNS,
    kill-switch, process lifecycle, log redaction, battery and heat profiles.
M6. Polish UI into Capsule-inspired cards, QR code import, local Favorites,
    subscription updater with ETag/If-Modified-Since and manual override.
