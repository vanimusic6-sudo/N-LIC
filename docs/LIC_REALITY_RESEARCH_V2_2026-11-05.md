# LIC-Assist / REALITY TCP — Stage diagnostics v2
Date: 2026-11-05
Status: first app implementation; Android CI != confirmed censorship bypass.

## Observed starting point

The same selected VLESS + REALITY node passed two out of two authenticated
HTTPS connections with original, Firefox and Chrome. Measured 819 / 802 /
837 ms. A 17 ms difference between two observations is not actionable.
There is no measured failure for LIC-Assist to improve on this path.

## What REALITY exposes to a network observer

Per the Xray/REALITY protocol documentation and Xray-core source:

- A TLS-like ClientHello with the selected uTLS fingerprint. The client
  browser fingerprint *does not* turn the entire tunnel into Firefox/Chrome.
- The destination IP/port, TCP establishment behavior, timing, packet sizes
  and directionality are still on the wire. REALITY does not remove traffic
  analysis opportunities.
- A visible SNI may exist when the configured serverName is a DNS hostname.
  The authenticated REALITY server must accept that serverName, shortId,
  client public key and matching VLESS flow. Client-only arbitrary changes
  to these fields can break authentication.
- Seeing HTTP 200 or 204 *through* the authenticated Xray outbound proves
  encrypted egress for the tested request, not long-term streaming stability
  or that DPI failed to recognize the connection.
- A closed TCP port, a blocked network route, a TLS/REALITY auth mismatch,
  an incompatible server fingerprint and an offline health-check endpoint
  are *different* root causes. No shortcut should label them all DPI.

Relevant modern upstream reports (anecdotes, not confirmed universal rules):

- Xray-core #7259 reports server-version-dependent rejection of the Edge
  ClientHello on REALITY while Chrome/Firefox succeed. A false positive
  "DPI blocked" could be ordinary server compatibility.
- Xray-core #6256 describes a REALITY ClientHello size increase to ~1.9 KB,
  sometimes spanning more than one TCP segment and failing on a reported
  middlebox. This is a report, not verified for our pinned 26.10.31 AAR.
- The Scientific Reports 2026 fingerprinting paper discusses inferring
  encrypted traffic classes from packet timing, directionality and bursts.
  It does not demonstrate a specific TSPU rule for this user's network.

## What this code now measures

The home screen REALITY comparison chooses one of two explicit modes:

| Mode | HTTPS attempts | Order |
|---|---|---|
| Quick | 6 (2 each) | baseline / Firefox / Chrome / Chrome / Firefox / baseline |
| Extended | 18 (6 each) | three mirrored blocks, starting with baseline, Firefox, Chrome |

- The **same selected server, keys, REALITY SNI, VLESS flow, and shortId**
  are used for every sample.
- A single direct TCP reachability check to the configured server precedes
  the experiment. It checks only socket establishment, not REALITY auth.
  DNS resolution is included in that time when the endpoint is a domain.
- Each candidate is started in a separate, bound-only Xray process with an
  authenticated localhost SOCKS listener; sequential attempts stop the
  previous runtime before starting the next.
- Actual HTTPS GET is sent through the encrypted Xray outbound to a fixed
  trusted endpoint, with certificate verification enabled. A second fixed
  endpoint may be tried if the first fails. The app records success, total
  request time, and limited stage codes, never server addresses or keys.
- **SOCKS CONNECT** covers the SOCKS roundtrip, potentially including
  remote dialing, but Xray may acknowledge before the REALITY handshake.
  It is not a measured REALITY handshake timestamp.
- **TLS to check site** measures the end-to-end TLS handshake of the public
  HTTPS site, not the REALITY handshake to the VPN server.
- **HTTP first status** measures time until a public HTTPS status line
  arrives. This is not an unconstrained packet capture.
- All times are monotonic milliseconds. Duration to first check site
  includes its TCP/TLS work and a second site if fallback was necessary.
  Medians provide only small-sample descriptive comparisons.
- The UI shows a short, single-line summary. Full details live in a
  constrained, scrollable report dialog; the summary can be tapped later.
- Tests cannot run while the app has a VPN session, a different Android VPN
  is active, or LIC-Auto / LIC-Bay is enabled. Exiting the screen cancels
  the background test. No selected-node preference is changed.

## What cannot yet be measured or claimed

- Exact TCP SYN/ACK or REALITY ClientHello-to-authentication timing is
  not exposed by the pinned Xray Android AAR. The SOCKS stage is a **proxy**.
  Obtaining exact flight sizes, JA4/JA3 and TCP segmentation would require
  controlled PCAP capture on an authorized access point, instrumented Xray
  transport or an independently verified local packet interception path.
- We cannot infer the active DPI vendor or its private filtering rules.
- We cannot improve a working baseline merely by choosing the fastest of
  2-6 measurements. The existing Smart fallback remains opt-in and changes
  no working profile.

## Next experimentally falsifiable hypotheses

1. ClientHello versions may differ by uTLS fingerprint; compare **actual
   first-flight sizes** with authorized packet captures before implementing
   any fragmentation changes.
2. Check if failures occur *after* a successful direct TCP connect but
   before authenticated HTTPS; repeat against the same authorized server
   and check server-side authentication logs if available.
3. Compare physical network paths (Wi-Fi versus mobile) when user is
   explicitly aware and chooses them. Keep the server constant.
4. If the first endpoint alone fails, check the independent fixed HTTPS
   destination before treating a whole VPN as offline.
5. Measure post-connect streaming separately; the 6/18 brief HTTPS probes
   do not diagnose throughput, jitter or thermals.

## Sources

- Official REALITY config and constraints:
  https://xtls.github.io/en/config/transports/reality.html
- XTLS REALITY project:
  https://github.com/XTLS/REALITY/blob/main/README.en.md
- Browser fingerprint compatibility report:
  https://github.com/XTLS/Xray-core/issues/7259
- Larger ClientHello / segmented-flight report:
  https://github.com/XTLS/Xray-core/issues/6256
- Scientific Reports 2026 fingerprinting study:
  https://doi.org/10.1038/s41598-026-41976-4
