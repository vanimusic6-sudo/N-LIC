# N-LIC · LIC Camouflage Engine — corrected direction (2026-11-05)

Status: **research/design**, no claims of tested bypass. This branch forks from `feature/nlic-v2-ui-core`, **not** from the rejected LIC-Fork auto-protocol/dual-hop PR #20.

## Goal

Preserve the user's selected, working VPN protocol and configuration. Add a *client-controlled, on-path outer transport / desynchronization layer* that may make the **observable connection** less distinguishable from legitimate browser traffic or help with DPI parsing inconsistencies. This is **not** a protocol switcher, not choosing a different subscription node, and not encryption invented by the client without a peer.

## Key discovery: Firefox's built-in VPN (different from Mozilla VPN)

Firefox 149's built-in browser VPN connects to a remote Fastly-operated proxy using **real HTTPS HTTP/2 CONNECT**, with the infrastructure designed to support MASQUE over HTTP/3. In its June 15, 2026 technical article Fastly explicitly says the *initial rollout uses HTTP/2 CONNECT*, and that HTTP/3 MASQUE is a planned evolution, not proof that the browser already uses it in every release.

- Firefox source architecture: https://firefox-source-docs.mozilla.org/toolkit/ipprotection/index.html
- Fastly technical article: https://fastly.quic.co/blog/we-built-proxy-behind-firefoxs-new-built-in-vpn-heres-how-it-works
- Mozilla FAQ: https://support.mozilla.org/en-US/kb/built-in-vpn

The key is **real, valid browser-oriented proxy traffic and functional forwarding infrastructure**, not launching Gecko as a disguise or sending a fake ClientHello followed by unrelated raw VPN bytes. H2 frames, SETTINGS, response semantics, stream behavior and server support all matter. A full Gecko embed is not necessary to implement RFC-compatible transport, but packet measurements are required to verify realism.

## Two separate engineering planes

### A. Client-only, no server changes (lower ceiling)

A protocol-aware local egress adapter may study/experiment with:
- TCP first-flight handling where an actual TLS handshake exists;
- alternative records or parsing-disagreement tests only if bytes delivered to the server remain bit-for-bit equivalent and TLS validation remains enabled;
- bounded cover/probe messages explicitly isolated from authenticated VPN packets.

Constraints:
- Fake/corrupted UDP packets **do not** transform WireGuard into HTTPS.
- A TCP-only segmentation trick does nothing for an ordinary WireGuard UDP handshake.
- Android VpnService TUN does not automatically capture the app's own excluded/protected Xray sockets; the adapter must be installed at the outbound dialer/socket path, or it will never see the VPN handshake.
- Non-root raw TCP packet spoofing and crafted TTL/checksum methods cannot be assumed available on Android. Do not ship an implementation that silently fails or destabilizes the phone.
- All experiments off by default, bounded in packets/CPU/time, and fail-closed for privacy.

### B. Real browser-like encrypted *outer* channel (requires remote support)

Target: client -> **real TLS + HTTP/2 CONNECT (or later H3/MASQUE)** -> remote *compatible* proxy -> user-selected VPN endpoint. The original VPN payload is carried inside a valid outer channel, with its own certificate and authenticated remote peer.

- H2 CONNECT: usable for TCP VPN outbounds when a compatible proxy endpoint exists.
- CONNECT-UDP / CONNECT-IP (RFC 9298 / RFC 9484): possible for UDP/WireGuard on a compatible remote proxy; availability and middlebox behavior must be measured.
- An ordinary VPN server cannot decrypt/unwrap the new outer channel. We must have a provider-supported HTTP proxy/relay endpoint, not pretend local extra encryption makes WireGuard look like Firefox.
- Outer proxy adds a second trust boundary and overhead. Avoid using the public Firefox/Fastly service without authorization and credentials.
- The original inner VPN encryption and authentication remain unchanged. Do **not** disable TLS certificate verification or silently route direct on failure.

### C. Fingerprint and traffic-behavior consistency

- `uTLS firefox` alone changes ClientHello, NOT the whole observable session.
- Real H2 settings, ALPN negotiation, certificate chain, stream behavior, packets/timing, and server response need consistent semantics.
- Random noise, dummy retransmissions and variable padding should be measured against a real failure mechanism, not added blindly.
- Severe per-packet random jitter wastes battery, harms video/voice and can create an even more distinctive fingerprint.

## Observed N-LIC limitations from baseline code audit

- `app/src/main/cpp/nlic-v2/nlic_protocol.c`: QUIC metadata recognition, TLS ClientHello/SNI parsing and **TLS record splitting**. No browser stack, real H2 CONNECT, or outer authenticated tunnel.
- `app/src/main/cpp/nlic-v2/nlic_socks.c`: authenticated local SOCKS relay; replays original stream (or split TLS first record); never transports raw arbitrary VPN inside a new HTTPS channel.
- `app/src/main/cpp/nlic-v2/nlic_udp.c`: SOCKS5 UDP relay sends native datagrams; cannot magically alter QUIC/WireGuard handshake signatures.
- `XrayConfigCompiler.kt`: contains local ByeDPI dialer proxy mechanism but no browser proxy wrapping.
- So “VPN up” can look successful while neither camouflage nor bypass is effective. Do not claim any effectiveness before field tests.

## API boundary for implementation

```
Android TUN (one owner) -> VPN engine (user-selected unchanged)
  -> Outbound Egress Adapter (protocol-aware)
       ├── NativeDirect (validated baseline)
       ├── ClientOnlyDpiExperiment (explicit opt-in; never corrupt inner payload)
       └── BrowserOuterTransport (H2 CONNECT; optional MASQUE when supported)
            -> remote authenticated compatible gateway -> same VPN server
```

No automatic node/protocol switching. The egress adapter is not a second VPN TUN.

## Work packages / objective gates

1. **Instrument baseline**: source IP/transport from actual outbound sockets; bounded non-sensitive counters for handshake/retransmits/UDP reachability, plus verified VPN HTTPS egress. No personal network identifiers, subscription URI or keys in logs.
2. **Native outbounds integration**: route only the *VPN engine's outbound socket* through adapter; keep loopback authentication, Android `VpnService.protect`, IPv4/IPv6 and DNS safe; verify with packet trace that adapter sees real handshake.
3. **Client-only lab experiments**: reproduce DPI parsing mismatch in a controlled testbed first, reject no-op or destabilizing tricks, compare to baseline on identical endpoints. Discard manipulations that cannot operate without raw sockets on Android.
4. **Browser outer transport**: implement genuine TLS+HTTP/2 CONNECT to a user-authorized compatible proxy, certificate verification/pinning policy and remote control plane; later research H3 MASQUE. Preserve inner VPN credentials and cryptographic integrity.
5. **Field matrix & release gates**: same server+network, A/B on Wi-Fi and cellular with IPv4, IPv6/NAT64, DNS/QUIC/WebRTC leaks, Always-on+lockdown, 30min audio/video, p50/p95 first-byte, failure rate, heat and battery. Abort on leaks, cert failures, or silent direct fallbacks.

## Non-goals

- Selection between VPN protocol profiles as a form of camouflage.
- Claims that a fake TLS header or browser name makes raw WireGuard undetectable.
- Borrowing Mozilla/Fastly proxy endpoints for unauthorized generic VPN use.
- Shipping experimental obfuscation as audited privacy protection without physical-network and security validation.

## Primary external references

- Fastly: https://fastly.quic.co/blog/we-built-proxy-behind-firefoxs-new-built-in-vpn-heres-how-it-works
- Mozilla: https://firefox-source-docs.mozilla.org/toolkit/ipprotection/index.html
- IETF RFC 9298: https://www.rfc-editor.org/rfc/rfc9298.html
- IETF RFC 9484: https://www.rfc-editor.org/rfc/rfc9484.html
- Xray TLS fingerprint API: https://xtls.github.io/en/config/transports/tls.html
- Zapret documentation (packet desync research, not directly transferable to Android): https://github.com/bol-van/zapret/blob/master/docs/readme.en.md
- AmneziaWG 3.1 masking examples, which require a matching remote server: https://docs.amnezia.org/documentation/amnezia-wg/
