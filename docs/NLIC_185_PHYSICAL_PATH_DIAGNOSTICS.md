# N-LIC 1.8.5: carrier-grounded diagnostics + link transition handling

Date: 2026-11-03. Stacked branch: `feature/nlic-relay-network-diagnostics-20261103`.

## Verified user observation

A DPI strategy works on Wi-Fi but none of the 16 tested profiles restores
mobile connectivity. A new TLS split formula by itself cannot explain this.

## External research (reviewed before implementation)

- **Pinned upstream ByeDPI 0.17.3:** `hufrea/byedpi` commit
  `ba532298de7b28cfe854aea83d061369d13ca290`.
  Real support is SOCKS5 with TCP TLS split/OOB/record splitting and UDP fake,
  plus `-A` fallbacks. It is not an NFQUEUE/eBPF packet rewriting engine.
  https://github.com/hufrea/byedpi
- **Zapret2:** native multi-host/per-protocol TLS and QUIC desync; notably
  testers warn that a returned HTTP status is insufficient without receiving
  a full response body. Its Linux NFQUEUE-based packet strategies cannot be
  copied verbatim to rootless ByeDPI Android.
  https://github.com/bol-van/zapret2
- **sing-box for Android:** VpnService/TUN supports IPv4/IPv6, configurable MTU
  and app scoping, and separates physical network routes and transport types.
  https://sing-box.sagernet.org/clients/android/features/
- **heiher hev-socks5-tunnel:** Android TUN to SOCKS5, TCP+UDP, UDP-in-UDP and
  optional UDP-in-TCP. UDP-in-TCP requires a compatible SOCKS5 upstream;
  N-LIC's pinned ByeDPI server is not assumed compatible without validation.
  https://github.com/heiher/hev-socks5-tunnel
- **Telegram DC transport:** Telegram mobile apps contact DC *IP addresses*,
  not only telegram.org web domains. Two documented sample DCs are
  `149.154.167.51` and `91.108.56.130`.
  https://telegramplayground.github.io/pyrogram/faq/what-are-the-ip-addresses-of-telegram-data-centers.html

## Applied changes

1. Fixed a serious radio selection bug: Android may leave both Wi-Fi and
   cellular capabilities marked VALIDATED. The original code incorrectly
   preferred Wi-Fi even when cellular was the default active transport.
   Now the active non-VPN Network is chosen; if neither active radio can be
   identified unambiguously, the answer is UNKNOWN (no wrong strategy guess).
2. **New direct physical-network checks, started only by explicit tap:**
   YouTube static image and API TCP 443; two Telegram production DC TCP 443;
   Cloudflare fixed-size 16 KiB and 256 KiB bodies. Uses Android
   `Network.getAllByName`, `getSocketFactory` and `openConnection` to
   force tests onto the selected physical network rather than the VPN.
   Per-test bounded timeouts and byte caps; no response bodies kept.
3. If the local N-LIC SOCKS service is active, check Telegram DC2 and the
   same 256 KiB download *again through the active local proxy*. Comparisons
   distinguish an ISP route issue, a local DPI bypass effect, and a profile
   which hurts throughput. None of the comparisons are asserted to prove
   censorship.
4. **Optional restart-on-radio-change**: while the VPN is running, register
   a low-overhead Android network callback. Debounce change events and
   restart only after a CONFIRMED Wi-Fi/cellular transition when explicitly
   enabled. This permits the already-implemented per-link saved profile and
   MTU/IPv6 policy to be applied on a new network. It can interrupt playback,
   so it defaults to OFF. Proxy mode is not restarted.
5. Tests cover active Wi-Fi/mobile precedence, ambiguous validated networks,
   Telegram IP vs YouTube TCP symptom grouping, 16 KiB vs 256 KiB differences,
   and opt-in reconnect policy.
6. `1.8.5`/`1823` optimized Release TEST / unit tests / CodeQL workflows.
   Experimental changes remain out of `master` until physical testing.

## Future / not included

- An **optional encrypted remote relay** is needed for genuinely blocked
  destination IP routes. No server, subscription or credentials are bundled,
  and N-LIC does not silently act as a VPN to a remote third party.
  SOCKS5 without encryption should not be treated as a secure VPN.
- Native UDP/QUIC port-specific fallback requires changes to the packet
  processing path; `--no-udp` blanket disabling is not a viable default.
- Use transport-specific media speed tests and two-carrier field logs to
  select proven strategies, not oversized default flag combinations.
- No evidence that field performance improves until tested on the device.
- APK CI success and Dev signing do not resolve Play Protect warnings.

## User test path

1. Install the dev-signed Release APK, preserving last known working Wi-Fi
   settings. Switch OFF Wi-Fi before measuring cellular.
2. Open Settings → Network diagnostics. Share the report text; it contains
   only target labels, timing/status, byte counts and coarse radio features.
3. Optional: enable separate profiles and restart-on-network-change; note
   existing connections will briefly disconnect during radio handover.
4. Test playback and Telegram separately. HTTP 200/204 or 256 KiB from
   Cloudflare does not prove actual YouTube video or Telegram MTProto works.
