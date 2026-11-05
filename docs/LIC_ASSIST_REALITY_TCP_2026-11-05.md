# N-LIC V3 research — LIC-Assist for VLESS/TCP/REALITY
Date: 2026-11-05
Status: experimental; NOT yet verified on a physical Android network.

## What this branch actually changes

This is the first **client-only**, single-node experiment. It does not switch VPN
protocols or servers, create a second tunnel, invent a new cipher, or alter
authenticated REALITY content.

Xray already supports real uTLS ClientHello fingerprints on REALITY TCP/RAW.
In N-LIC settings, under **VPN + LIC · VLESS TCP + REALITY**, the user can select:

- **As in subscription** (default): byte-for-byte the previously selected uTLS
  fingerprint and REALITY configuration.
- **Firefox**: use Xray's supported `firefox` ClientHello fingerprint.
- **Chrome**: use Xray's supported `chrome` ClientHello fingerprint.

The option only changes `outbounds[0].streamSettings.realitySettings.fingerprint`
for an actual VLESS + REALITY + RAW (TCP) outbound. All other outbounds retain
their original settings. The user must disconnect before changing the option.

This is **only** ClientHello emulation, not an embedded Firefox networking stack
or a genuine HTTP/2 browser tunnel. Do not claim otherwise.

### Why this is worth measuring

- A protocol may be identified by observable TLS handshake characteristics,
  but a browser TLS fingerprint alone does **not** make a VLESS stream identical
  to arbitrary browser traffic.
- Official Xray documentation requires REALITY's uTLS fingerprint and lists
  browser fingerprint options.
- In 3x-ui issue #4980 (June 2026), some users *reported* that switching
  `chrome` → `firefox` affected reachability on their Russian networks.
  This is an anecdote, not independent verification or a guarantee.
- REALITY is supported by Xray on RAW, XHTTP and gRPC. This compiler does not
  currently convert XHTTP; it rejects REALITY+WS/HTTPUpgrade rather than
  silently generating an invalid or insecure configuration.
- REALITY short IDs are constrained to at most 16 hex characters and must have
  an even length, matching the server-configurable format.

## Experimental field test protocol

Use the **same authorized** VLESS+REALITY server, selected node, Android phone,
Wi-Fi/cellular network and test URLs. No VPN profile auto-selection.

1. With N-LIC disconnected, set `As in subscription`, turn **LIC-Bay OFF**
   so it cannot influence this fingerprint experiment, connect, and check
   end-to-end HTTPS through the Xray outbound. Record connection success,
   startup time and any error stage.
2. Disconnect fully. Set `Firefox`. Repeat the same connection and HTTPS
   validation. Prefer 5-10 alternating trials (A/B/A/B) at similar times.
3. If neither works, check actual server availability, key, shortId, SNI,
   authentication, DNS, IPv4/IPv6, and certificate validation. Do **not** infer
   DPI from an `XRAY_START` error or a simple TCP timeout.
4. Monitor battery/heat, video/audio stability, IPv6 leaks, DNS leaks and
   reconnection on Wi-Fi↔cellular. Use Android Always-on/lockdown when testing
   privacy-sensitive scenarios.
5. Save only aggregate pass counts and non-sensitive timings. Never log
   subscription URI, public/private key, UUID, SNI, target hostname, or
   client traffic.

Acceptance criteria: Firefox is better only if the same node repeatedly
fails in control trials and completes authenticated end-to-end HTTPS via the
Xray core in intervention trials, with no privacy/security regressions.

## Non-goals / limitations

- Not an injection/evasion patch; no packet spoofing or unverified fragmentation.
- No protocol selection, no server selection, no silent weakening of TLS/REALITY.
- Not applicable to WireGuard, AmneziaWG, OpenVPN, plain VLESS, or VLESS+TLS.
- Does not bypass IP-based blocking or prove that TLS fingerprint is the
  discriminating feature of the real DPI.
- Tests verify compiler behavior; an actual signed APK and physical network
  verification remain required before claiming a working bypass.

## References

- https://xtls.github.io/en/config/transports/reality.html
- https://xtls.github.io/en/config/transports/tls.html
- https://xtls.github.io/en/config/outbounds/vless.html
- https://github.com/MHSanaei/3x-ui/issues/4980
