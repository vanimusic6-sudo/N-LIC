# N-LIC · REALITY A/B comparison

**Date:** 2026-11-05  
**Status:** experimental, no claim of successful DPI bypass.

## Purpose

Use the same manually selected VLESS + TCP (RAW) + REALITY server and
the same authentication parameters to test whether uTLS ClientHello
fingerprints correlate with actual HTTPS reachability.

Button: **«Сравнить с LIC-Assist»** in the VPN home screen.

## Test implementation

- Separate non-exported Android `:realitycompare` process, bound by the
  visible Activity only. Leaving the screen unbinds and cancels the test.
  A native Go panic in this process does not crash the main UI.
- Does **not** establish VpnService/TUN, change the selected node, modify
  the provider's credentials, or persist an override.
- Does **not** require Smart mode to be enabled.
- Requires VPN disconnected; LIC-Auto and LIC-Bay off; manually chosen
  VLESS+REALITY+RAW (TCP).
- Xray core starts with a private password-authenticated SOCKS5 listener
  on loopback. Each trial uses a fresh, sequential runtime and closes it.
- Six trials use a mirrored order to reduce time bias:
  `original, Firefox, Chrome, Chrome, Firefox, original`.
- Each sample runs a real certificate-verified HTTPS GET **through the
  encrypted Xray outbound**, not a bare TCP reachability or SOCKS-open check.
  Fixed endpoints are example.com followed by cp.cloudflare.com.
- Records only `profile / success / duration`; raw subscriptions, UUIDs,
  REALITY keys, server/IP and visited content are never sent to UI messages
  or stored in a report.
- Displays per-variant success counts out of 2 and sample mean HTTPS-check
  elapsed time. The timing includes encrypted connection setup and may
  include fallback to the second HTTPS endpoint; it is **not** a pure
  transport RTT or a statistically significant speed benchmark.
- The result never automatically changes the selected fingerprint. It
  presents a candidate for repeat testing if an alternate works more often.

## Interpretation

A successful initial REALITY with a failing alternate does not mean
the alternate is bad in all networks. Conversely, a successful Firefox
trial with a failing original may be caused by a TLS fingerprint
difference, a server-version mismatch, destination endpoint issue or
temporary network instability — not necessarily DPI.

Two passes per fingerprint are preliminary only. Real attribution needs
repeated measurements on the same physical network, ideally an
independent control and network-path observations.

## Security

- Never disable REALITY or HTTPS certificate validation.
- No direct HTTP fallback if encrypted outbound is unavailable.
- No unexpected alternate VPN server.
- No tests if another Android VPN is active.
- If no valid Xray or compatible selected profile, fail with a clear
  message. The normal VPN path remains unchanged.
