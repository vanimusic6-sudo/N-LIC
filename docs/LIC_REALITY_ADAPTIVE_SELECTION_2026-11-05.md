# LIC-Assist · Adaptive REALITY 2026-11-05

**Scope:** VLESS + TCP (RAW) + REALITY. Manual single-node VPN mode only.
**Status:** first production-path implementation. Passing CI is not proof of DPI bypass.

## What changes in the actual VPN path

This feature is not another diagnostics button: when **LIC-Assist · REALITY**
is ON, the *native Xray engine that will carry Android VPN traffic* uses the
browser uTLS profile selected by this algorithm.

- A newly encountered node/coarse physical network gets a one-time,
  sequential comparison of the actual imported fingerprint and compatible
  modern Xray uTLS ClientHello profiles: **Chrome, Firefox, Safari**.
- The same server, UUID, REALITY public key, shortId, SNI and Vision flow
  are used. Only Xray's ClientHello fingerprint changes.
- Each candidate is checked through the real encrypted outbound using
  Xray's certificate-validating HTTPS latency mechanism. Merely opening TCP
  or localhost SOCKS does not count as success.
- The candidate with the lowest validated HTTPS latency can win only if it
  improves on a *working* imported profile by at least 90 ms **and** 15%.
  This avoids changing a stable profile over a few milliseconds of noise.
  If the imported profile cannot perform HTTPS, a validated alternate is
  preferred instead.
- The selected candidate is launched **again** and must pass authenticated
  HTTPS, followed by successful Android TUN/HEV startup. Only then is its
  fingerprint stored as a short-lived preference.
- When this profile is used again on the same coarse network, the cached
  confirmed fingerprint is attempted first without rescanning.
- If cached preference fails, the same server is retried with other
  curated profiles sequentially, and a working fallback replaces the cache
  only **after** verification. If none works, fail closed. No direct route or
  TLS verification bypass.
- The cache lasts 12 hours, is separated by network type (Wi-Fi, cellular,
  Ethernet/other) and LIC-Bay mode, and is scoped to the current node.
  Node IDs are hashed with an installation-local random salt before saving;
  no server IP, SNI, link, credentials, client request, SSID or visited URLs
  are stored. Existing provider profiles are never modified.
- Under Advanced Settings, **Повторить подбор REALITY** clears the local
  preference for this node and network for the *next* connection.
- With LIC-Auto server selection enabled, this separate same-node adaptive
  logic is intentionally inactive. With LIC-Assist OFF, default Xray
  config is unmodified.

### Why only Chrome, Firefox and Safari?

Xray documents additional uTLS fingerprints including edge/ios/qq/360,
randomized and custom hellos. However, REALITY behavior is not identical
to ordinary TLS. Reports on Xray v26.9.8+ indicate that several older
profiles no longer authenticate against modern servers, potentially due
to absent post-quantum key shares. Blindly adding them would be an unsafe
'trick': it could silently break a working VPN.

Curated profiles have recent client implementations compatible with modern
REALITY in the publicly reported tests. This is a compatibility heuristic,
NOT a guarantee or claim about a particular TSPU filter.

### What this does NOT do

- No packet injection, TCP overlap, TTL decoys or fake HTTP headers.
- No claim of emulating an entire browser (uTLS emulates ClientHello only).
- No autonomous server changes or multiple active VPN tunnels.
- No exact measurement of REALITY's TLS flight/segmentation; the pinned
  Xray AAR does not expose that instrumentation to Android.
- No network-wide DPI verdict. An observed HTTPS timeout could be a
  server, DNS, compatibility, certificate or censorship failure.
- No continuous throughput optimization on an already working tunnel.
  Choices are made only at connect time, avoiding playback interruption.
- No random background probes or hidden battery-intensive monitoring.
- A first Smart connection may take noticeably longer due to sequential
  calibration. Cached follow-up connections should avoid this cost.

## Field acceptance checks

1. With a manually selected VLESS TCP/REALITY profile and LIC-Auto OFF,
   enable LIC-Assist and connect. The status should show calibrated
   original/Chrome/Firefox/Safari candidates, then the selected variant.
2. Disconnect and reconnect on the same network; status should skip
   calibration and use the remembered profile.
3. Try another network type; calibration should run independently.
4. Disable LIC-Assist and confirm default provider fingerprint is used.
5. Change node: cache for old node must not leak into the new one.
6. Simulate a failing cached fingerprint and confirm same-node authenticated
   fallback, never direct/VLESS key downgrade.
7. Repeat after 12h expiry or use the reset control, check power consumption
   and startup duration; for streaming test use existing opt-in Stream Audit.
8. Inspect privacy controls and Android Always-on/lockdown behavior.

Sources:
- Official REALITY: https://xtls.github.io/en/config/transports/reality.html
- Official uTLS lists and limits: https://xtls.github.io/en/config/transports/tls.html
- REALITY browser compatibility report: https://github.com/XTLS/Xray-core/issues/7259
- Amnezia Android user report: https://github.com/amnezia-vpn/amnezia-client/issues/2704
