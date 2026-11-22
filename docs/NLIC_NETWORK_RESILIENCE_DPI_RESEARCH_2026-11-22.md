# N-LIC 2026 — Network resilience and DPI architecture review

Status: implementation hardening on `feature/nlic-awg31-packet-engine-20261112`; **not a field-validated release**.
Date: 2026-11-22. Scope: Android user-space VPN with a single VpnService TUN; existing Xray, sing-box, ByeDPI and AWG profile import.

## 1. What the censorship research actually shows
- Russia's TSPU/DPI infrastructure can interfere at the DNS/IP, SNI/TLS handshake and VPN-protocol/flow-pattern layers. The outcome varies by ISP and physical access network; a generic "VPN connected" indication is not proof of functional egress.
- OONI measured TLS reset and timed-out connections around ClientHello, as well as protocol-level interference against VPNs. New reports in 2026 describe escalating VPN interference.
- Censorship resistance and confidentiality are independent: encrypting the payload does not hide source/destination IPs, packet timing, size sequences or failed handshakes.
- DNS or IP-based restrictions may be entirely unaffected by TLS record splitting; lack of Internet or an allowlist covering no usable server cannot be defeated by packet camouflage.

Sources (retrieved 2026-11-22):
- https://ooni.org/post/2024-russia-report/
- https://www.hrw.org/news/2026/09/24/russia-crackdown-on-bypassing-online-censorship
- https://www.hrw.org/report/2025/07/30/disrupted-throttled-and-blocked/state-censorship-control-and-increasing-isolation
- https://www.petsymposium.org/foci/2025/foci-2025-0016.pdf

## 2. Internet architecture and threat boundary
Path: Android apps -> Android VpnService/TUN -> ONE selected userspace routing core -> authenticated/encrypted tunnel -> remote endpoint -> destination.
The access ISP sees remote IP, port, connection length, timing, traffic volumes and some handshake features. The selected VPN server can see destinations and application traffic metadata. HTTPS to the final destination provides end-to-end application-layer integrity/confidentiality; the VPN never replaces TLS validation.
IPv4/IPv6, DNS and TCP/UDP must be captured consistently. Incorrect per-app exclusions, dropped IPv6 routes, unprotected bootstrap sockets or tunnel teardown can expose traffic.
Android's *Always-on VPN + Block connections without VPN* is a system-enforced protection users must explicitly enable. Application-only restart logic **cannot** guarantee zero leaks during a process crash or tunnel replacement. The N-LIC UID is deliberately excluded to avoid self-routing loops; bootstrap and explicit subscription downloads need separate auditing.
Android VpnService source: https://developer.android.com/develop/connectivity/vpn

## 3. Security and protocol choices
**Keep** established cryptography rather than invent a cipher:
- WireGuard / compatible AmneziaWG: Noise_IK, Curve25519, ChaCha20-Poly1305 with authenticated encryption and periodic rekeys; AWG changes *obfuscation*, not the AEAD contract.
- VLESS has no confidentiality of its own; allow only authenticated REALITY or verified TLS; XHTTP requires a matching server-side inbound. Do not silently reinterpret RAW as XHTTP.
- Trojan requires verified TLS. Shadowsocks must use an AEAD method, VMess a supported authenticated cipher; import validators already restrict these.
- Xray private SOCKS is bound to 127.0.0.1 with random credentials; monitor should verify real HTTPS egress and never trust an open SOCKS port alone.
- Reject insecure TLS, unrecognized config fields that could change routing, and unsupported protocol downgrades.

Sources:
- https://www.wireguard.com/protocol/
- https://docs.amnezia.org/documentation/amnezia-wg/
- https://xtls.github.io/en/config/transport.html
- https://mas.owasp.org/MASTG/knowledge/android/MASVS-STORAGE/MASTG-KNOW-0047/

## 4. Existing components verified in the branch
- Xray REALITY+RAW, XHTTP/TLS/REALITY imports and compiler, with explicit server capability requirement.
- LIC-Auto selects a first certificate-validated HTTPS egress, bounded candidate list and low-frequency watchdog.
- Auto recovery is opt-in and limited to safe egress-failure categories.
- AWG 3.1 has a **data-only parser**; it is **not** a working native AWG tunnel.
- Native ByeDPI fake-TTL/TCP-OOB is conditional; no privileged raw NFQUEUE interception exists on unrooted Android.

## 5. Hardening implemented in this patch
1. Add final outbound confidentiality assertion to Xray compilation: VLESS/Trojan without verified TLS or authenticated REALITY are rejected even if an upstream parser later regresses.
2. Harden HTTPS health probe: certificate-validated 2xx only; a redirect (301/302/307) is no longer counted as working egress.
3. Zero temporary SOCKS username/password byte arrays after the handshake, including error paths.
4. For subscription URLs, reject ambiguous trailing-dot hostname spelling. Reject IPv6 6to4 addresses that might sidestep IPv4 special-use address checks.
5. Add bounded 20% retry jitter and saturating retry counters to limit synchronized restart storms and prevent integer overflow. Cap stays at 180s.
6. Add regression tests for the invariants above. No new dependencies or external code have been introduced.

## 6. Comparison with other designs
- **Xray REALITY/XHTTP:** useful for HTTPS-like transport when both client and server support it; not a universal bypass and SNI/certificate/authentication must remain correct.
- **AmneziaWG 3.1:** guards packet signatures and timing while retaining WireGuard cryptographic primitives; requires a matching runtime on both ends.
- **Tor obfs4/WebTunnel/Snowflake:** bridge distribution and transport diversity help evade address/protocol blocking; importing their APIs does not magically replace an Android TUN VPN core.
- **ByeDPI/zapret-like splitting:** network path dependent, sometimes breaks TLS or adds latency. Changing TCP writes is not necessarily on-wire desynchronization.
Tor source: https://support.torproject.org/tor-browser/circumvention/connecting-from-censored-regions/

## 7. Required next architecture milestones (NOT implemented here)
1. Integrate and verify a compatible native AWG 3.1 userspace engine under a single Android VpnService, including license/ABI/interface audit. Never downgrade imported AWG to standard WG.
2. Preserve a single TUN across endpoint switches where the core supports hot reconfiguration; use atomic routing and Android lockdown for strict no-leak mode.
3. Add physical-link-aware DNS/bootstrap policy for Wi-Fi vs cellular with IPv4/IPv6 and NAT64 validation; avoid unprotected recurring probes.
4. Measure on-device HTTPS egress, leak checks, failover duration, energy, thermal and radio wakeups. Never infer real censorship success from compilation or a local test.
5. Version and pin all native binary hashes. Run Android Release + unit tests + lint, emulator network tests and controlled field trials before production promotion.

## 8. Field acceptance criteria
- 20 cold starts per transport on Wi-Fi and cellular; record connection-success rate and time-to-first-successful HTTPS.
- 20 Wi-Fi->cellular and cellular->Wi-Fi switches; include captive portal, airplane mode, IPv6-only/NAT64, background, battery saver, process kill and screen-off cases.
- Verify IPv4/IPv6 leak behavior and DNS egress using an *external* trusted endpoint, with and without Android Always-on lockdown; explicitly document user opt-outs and failures.
- Run invalid-certificate, wrong REALITY key, malformed subscription, localhost/CGNAT/6to4 and broken SOCKS server negative tests.
- Check Native CPU, wakelocks, heat, battery and indefinite reconnect storms.
- Never claim successful DPI evasion without tests on affected networks. Do not promote PR as production before the evidence exists.

License: GPL-3.0 in the repository. No newly imported code or dependency added by this patch.
