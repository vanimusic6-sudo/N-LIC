# N-LIC — Phase 1: deep survey of contemporary DPI circumvention (2026-11-03)

**Status:** source-based comparative audit, NOT a completed network experiment, vulnerability proof, or user-device benchmark.  
**Scope:** public specifications, vendor/project documentation, academic measurements, and N-LIC Android/native source at `research/dpi-probe-audit-20261103` (HEAD at audit start: `97ef01699c4de2a5218e27eab3192cc0f4947ae4`).  
**Goal:** rank solutions by *practical Android rootless integration into the existing ByeDPI app* and *likely long-term maintainability*, not by claims that an individual strategy is guaranteed to work against every censor.

## Executive findings

1. A DPI bypass is not a VPN; no TCP desynchronization trick reliably fixes an IP-prefix block, a network whitelist, or arbitrary already encrypted UDP VPN traffic.
2. Reliable classification of *why* a connection failed is more valuable than 100 untested command strings. Separate DNS spoofing, IP failure, TLS SNI/ClientHello intervention, QUIC Initial intervention, server HTTP denial, content throttling after the handshake, and native app failure.
3. Russia's 2026 OONI reports document both DNS and TLS filtering and March 2026 HTTP reset/timeout patterns. An academic FOCI 2026 paper documents large-scale Russian QUIC Initial SNI censorship; static TCP-only strategies cannot cover that path.
4. The sustainable architecture has two tiers: (A) rootless local SOCKS/TUN DPI assist for ordinary compatible flows, and (B) authenticated, maintained VPN/proxy transport cores for endpoints where a local desynchronizer cannot help.
5. The native TLS, QUIC and full packet signature space is moving quickly. Protocol-conformant, versioned transport implementation and measurement infrastructure will outlive a static collection of fake packets.
6. The most interesting early upstream improvements are **ByeDPI's newer fixes**, **hev-socks5-tunnel's native/Android bug fixes**, **zapret2's Lua profile model** and **sing-box's TLS/protocol-specific diagnostics**. All updates must be qualified independently.

## Evidence and uncertainty

- **Measured evidence:** peer-reviewed FOCI/USENIX/NDSS papers and OONI measurements. These establish observations *on specified networks/dates*, not a blanket guarantee.
- **Implemented feature:** upstream code and official documentation. Existence does not establish effectiveness or Android compatibility.
- **Vendor claim:** claims of undetectability, speed and perfect camouflage are hypotheses to validate under controlled network conditions, not findings.
- **Engineering ranking:** feasibility/maintenance ratings below are qualitative expert estimates, not field success percentages.
- **Still untested:** N-LIC on a current physical Android device, end-to-end QUIC compatibility, simultaneous VPN external-app integration, real-world low-power/thermal effect, and new methods' success rate.

## A. Network censorship: separate mechanisms

| Layer / symptom | How it can be recognized in a controlled experiment | What could help | What usually cannot |
| --- | --- | --- | --- |
| DNS manipulation | DNS reply points at known block page; different trusted resolvers disagree | DoH/DoT, in-tunnel DNS, authenticated resolution | TCP fragmentation of an HTTPS connection after wrong DNS |
| IP/prefix or port block | TCP SYN never completes across direct and proxy/no-transform conditions, repeatable over addresses | Alternate endpoint, different reachable IP family/route, remotely hosted relay | Fake ClientHello split to the *same blocked destination IP* |
| HTTP Host or plaintext pattern | HTTP redirect, block page or reset only for targeted host | Host/header normalization, selective split/fragment where compatible, encrypted transport | Claiming HTTPS 200 proves unblocking |
| TLS ClientHello / SNI | TCP established, then TLS ClientHello reset/times out; changes with hostname | Tested TLS-record split or TCP desync; ECH when both sides actually support it | Changing encrypted payload by arbitrary byte edits |
| UDP/QUIC Initial | UDP no response, targeted QUIC handshake blocked; TCP equivalent may work | Explicit QUIC fallback, QUIC-v2/transport-aware research, endpoint/proxy choice | Blind TCP-only OOB/split profile |
| Protocol fingerprint / active probe | Distinct handshake/packet sizes and server response to unknown client | Authentication-bound camouflage, real TLS/HTTP stack behavior, server fallback | Merely changing client SNI while server remains fingerprintable |
| Rate/volume dependent throttling | First handshake succeeds, throughput drops after a repeatable byte/time threshold | Continuous throughput probes, alternate authenticated transport/route | Testing only a 4 KiB GET or one TLS handshake |
| Full allowlist/shutdown | Only approved destinations work (or none) across protocols | Reachable permitted relay *if one exists*; for genuine shutdown out-of-band comms | Any claimed universal packet trick |

### 2026 observations that materially influence Phase 1

- OONI: **Telegram Russia (March 30, 2026)**, measurements from 547 ASNs, resets and timeouts; some reported CDN sessions stop transferring around 16–20 KiB. [Primary finding](https://explorer.ooni.org/findings/2026-russia-blocked-telegram)
- OONI: **Bluesky Russia (April 23, 2026)**, TLS ClientHello RST/timeout plus some DNS block-page fingerprinting. [Primary finding](https://explorer.ooni.org/findings/2026-russia-blocked-bluesky)
- FOCI 2026: **On Russia's Early Introduction of QUIC SNI Censorship**. QUIC v1 Initial uses derivable Initial protection, permitting DPI to inspect SNI; some observed filters differ by QUIC version and 4-tuple state. [Peer-reviewed proceedings](https://www.petsymposium.org/foci/2026/foci-2026-0010.php)
- NDSS: **Detecting Probe-resistant Proxies** demonstrates that even secret-protected transports can exhibit TCP-level probe fingerprints. [Research](https://www.ndss-symposium.org/ndss-paper/detecting-probe-resistant-proxies/)
- USENIX: **OpenVPN is Open to VPN Fingerprinting**, protocol markers, packet sizes and active probing. [Research](https://www.usenix.org/conference/usenixsecurity22/presentation/xue-diwen)
- USENIX 2025: **Exposing and Circumventing SNI-based QUIC Censorship of the Great Firewall of China** illustrates computational limitations of QUIC DPI and that filtering is protocol-specific. [Research session](https://www.usenix.org/conference/usenixsecurity25/technical-sessions)

**Consequence:** one global command, one static domain list, or a single HTTP status indicator cannot represent all blocking types. Avoid deceptive success percentages.

## B. N-LIC current-code audit and improvement backlog

**Architecture observed:** Android `VpnService` (optional) → TUN → `hev-socks5-tunnel` → local SOCKS5 → `hufrea/byedpi` outbound TCP/UDP; proxy-only mode does not require Android's VPN interface. C glue launches byedpi in a one-shot native worker process. Android builds strategy arguments, manages lifecycle, and runs per-domain HTTP checks.

**Pinned commits:**
- `hufrea/byedpi@ba532298de7b28cfe854aea83d061369d13ca290`; upstream main has 9 subsequent commits as of inspection, including `connect_target` for HTTP CONNECT, file-descriptor exhaustion, TCP/TLS timeout separation, ServerHello timeout and protocol logging. Treat as *candidate fixes*, not assumed Android-working.
- `heiher/hev-socks5-tunnel@4d6c334dbfb68a79d1970c2744e62d09f71df12f` corresponds to Feb 2026 2.14.4; current published release `2.19.3` (Oct 25, 2026) fixes Android JNI relocation and includes send/receive and idle-memory changes. **The Java API changed in 2.17, so do not bump blindly**; N-LIC uses a custom JNI wrapper and needs ABI verification.
- Existing Gradle/CMake/JNI builds depend on a reproducible submodule checkout; upstream C code has direct memory-management and parser surfaces worthy of fuzzing.

### Confirmed or source-observed issues

| Severity | Evidence in current N-LIC source | Engineering action |
| --- | --- | --- |
| P0 for research correctness | `SiteCheckUtils` historically counted non-2xx error pages as successful; patched in PR #2, but HTTP 200 censorship block pages can still pass | Add expected-content fingerprints and classify ambiguous responses |
| P0 for adaptive decisions | Current results model stores only success count per URL and strategy, no observed failure category, TLS handshake stage, QUIC capability, first-byte latency distribution or transport | Replace boolean-only outcomes with evidence-rich `ProbeResult` schema and safe aggregation |
| P1 scope | Default test command list contains many complex strategies, with no one-transform/no-transform control or randomized A/B ordering | Minimal baseline, clean comparisons and measured complexity penalty |
| P1 UDP | HEV supports UDP forwarding but existing route may use ByeDPI UDP association; no proof that QUIC Initial packets are parsed/processed as intended by each chosen strategy | Test UDP and QUIC separately from TCP, including very small and fragmented datagrams |
| P1 IPv6 | `ByeDpiVpnService` adds `::/0` only if IPv6 toggle is enabled; verify intended policy and device leakage/fail-closed behavior | Explicit IPv6 route tests; no silent assurance |
| P1 LAN exposure | The C SOCKS proxy can be configured to listen outside loopback. If enabled with no authentication it can expose a relay | Keep loopback-only by default, authenticate or restrict external listeners |
| P1 native stability | Upstream native core may be restarted only in a fresh worker process; worker stop and binder death need validation under cancellation, overload and reconnect | Add lifecycle stress matrix and crash telemetry without payloads |
| P1 external app compatibility | Two independent Android `VpnService` instances cannot both own the primary VPN interface | Helping external: proxy-only; Helping integrated: one N-LIC VPN/TUN |
| P2 performance | Always-on fake/split techniques can increase packet count, retransmissions, TCP latency, power cost or produce unstable connections on some networks | Apply only where matching, measure p50/p95 energy and latency |
| P2 subscription and privacy | Future Helping will ingest URLs, passwords and keys; importing text is a new trust boundary | Secret redaction, hard input limits, schema validation, no credential logs |
| Unresolved | Play Protect previously classified test APK as harmful; rebranding didn't settle it | Do not claim secure installation; investigate and appeal only with provenance |

The quality gate and CodeQL on the merged research+security baseline completed, but **a passing workflow cannot prove absence of native bugs**. No physical Android device or controlled DPI packet captures are accessible from this source audit.

### Core mechanism audit

- `split`: low-cost, relatively portable; DPI may reassemble and some OS/TLS stacks coalesce adjacent writes. Endpoint content need not change. Good selective fallback, not universal.
- `disorder`: deliberately triggers reorder/retransmission; works only under certain middlebox/TCP behaviors. Susceptible to extra latency, asymmetric paths, poor mobile loss.
- `fake`, limited TTL, MD5-signature path: attempts to desynchronize middlebox's view vs server. TTL/hop count varies, so wrong settings can reach destination or be ineffective. Fake data can itself be a fingerprint; no universal robust TTL.
- `OOB/disoob`: special TCP urgent handling; may depend on kernel and remote stack interpretations. Less predictable than record segmentation; should be optional.
- `tlsrec`: application-layer TLS record segmentation preserves the TLS handshake semantics if correctly implemented, but not equally accepted by every DPI. Not all QUIC paths use TLS records; behavior differs for ECH/ClientHello changes.
- `HTTP Host case/whitespace`: can evade naive plaintext rules but is an easily normalizable legacy trick and irrelevant to encrypted TLS payloads.
- `--auto` with failure triggers/cache: useful starting point, yet trigger semantics and cached scopes may not match network changes. A route/cache key needs network identity, destination, protocol, TTL, and rollback.

## C. Technology / project comparisons

### 1. ByeDPI 0.17.x and current native main

**Strengths:** already integrated; rootless Android via SOCKS; broad TCP tools; basic UDP; simple C source and small binary; existing failure-triggered auto.
**Weaknesses:** correctness is highly OS-specific; no native generic VPN transport authentication; TCP transformations do not defeat IP blocks; arbitrary UDP VPN handshakes are not guaranteed to benefit; complexity explodes with large command chains. Near-term *upgrade/targeted refactor candidate*. [Source/releases](https://github.com/hufrea/byedpi/releases)

### 2. zapret2 1.0.6 and older zapret

**Strengths:** rich protocol inspection, host/packet filters, profile matching and Lua-defined transformation logic; active v2 development and blockcheck2 controlled searches. v1 is EOL. **Key inspiration: separate policy/profiles from packet engine**.
**Weaknesses:** transparent NFQUEUE/raw packet manipulation needs privileged OS hooks (on Android root for direct deployment), high development complexity and interface differences. Do not claim its kernel packet engine is a drop-in rootless Android library. Candidate: study/test profile model and selected mechanisms, port *only portable* logic. [zapret2 releases](https://github.com/bol-van/zapret2/releases) · [project](https://github.com/bol-van/zapret2) · [original Android limitations](https://github.com/bol-van/zapret/blob/master/docs/readme.en.md)

### 3. sing-box / SFA / native libbox

**Strengths:** Android-capable core, policy routing, authenticated transports, TUN and SOCKS endpoints, current DNS and protocol support; 1.14.4 released October 2026. Versioned TLS `fragment`/`record_fragment`, ECH, Naive, ShadowTLS, AnyTLS, Hysteria2 and TUIC. A potential foundation for **Helping integrated** without reinventing cryptography.
**Weaknesses:** integration can be large: Go runtime, binaries, license GPL-3.0, changes between versions, app size and resource use. Official documentation says TCP TLS fragmentation is primarily for simple plaintext matching filters and recommends the record-level method first; `uTLS` alone is not reliable browser mimicry. 1.15 features in docs are not necessarily stable as of 1.14.4. [Official TLS](https://sing-box.sagernet.org/configuration/shared/tls/) · [route action](https://sing-box.sagernet.org/configuration/route/rule_action/) · [release](https://github.com/SagerNet/sing-box/releases) · [Android](https://github.com/SagerNet/sing-box-for-android)

### 4. Xray-core, VLESS+REALITY/Vision and XHTTP

**Strengths:** mature configurable transport core, TLS/REALITY/HTTP-based camouflage, VLESS/Trojan/SS, WebSocket/gRPC, native Android wrappers such as libXray. REALITY emphasizes server-authentication and fallback-to-real-site semantics; XHTTP offers server-supported HTTP-shaped transport. Good **Helping integrated** candidate.
**Weaknesses:** requires compatible server and exact endpoint profile; client and server stacks can be fingerprinted, and a server's public IP can still be blocked. REALITY fallback may create unintended open forwarder behavior if deployed incorrectly. Official project issues include real XHTTP resource/hang concerns: validate version and mobile stability. [REALITY documentation](https://xtls.github.io/en/config/transports/reality.html) · [transport compatibility](https://xtls.github.io/en/config/transport.html) · [core](https://github.com/XTLS/Xray-core) · [libXray](https://github.com/XTLS/libXray)

### 4b. Xray FinalMask (late-2026 transport post-processing)

**What it is:** the current Xray transport architecture includes `FinalMask`, a last-stage TCP/UDP camouflage layer that runs *after* TLS/REALITY processing, with TCP `fragment` and `header-custom` capabilities, configurable UDP obfuscation (including `salamander`/`gecko`), and QUIC tuning. This is **more relevant than adopting a single new command-line trick**, because the layer where a transform is applied is explicit.

**Strength:** convenient architecture for composing transport-specific camouflage with the same authenticated proxy core, while keeping semantics aware of direction and transport. **Weakness:** some FinalMask transforms require matching server logic or can violate TLS/QUIC semantics; not every option is supported rootlessly, and combining layers can create recognisable traffic instead of removing signatures. An Xray `fragment` transport setting is not the same thing as kernel IP fragmentation. Some `xdns` modes tunnel through DNS at very small effective MTU and poor efficiency; these are research-grade fallbacks, not a mobile-performance default.

**Porting idea:** borrow the layering and explicit capability description for N-LIC's **profile planner**, do not transplant arbitrary masks or assume all FinalMask features run on top of ByeDPI SOCKS.

[Official FinalMask docs](https://xtls.github.io/en/config/transports/finalmask.html) · [Source documentation](https://github.com/XTLS/Xray-docs-next/blob/main/docs/en/config/transports/finalmask.md)

### 4c. sing-box TLS spoof (requires OS privileges)

The new `spoof` / `spoof_method` fields can send a forged whitelisted-SNI ClientHello before the real one. The sing-box documentation explicitly restricts this method to Linux/macOS/Windows with elevated privileges. **It is not a direct Android rootless option**; copying the field into N-LIC config would not make the capability available.

[Official sing-box TLS](https://sing-box.sagernet.org/configuration/shared/tls/)

### 5. NaiveProxy (Chromium network stack)

**Strengths:** unusually good browser-like behavior from reusing Chromium's real TLS/HTTP stack, HTTP/2 multiplexing, padding, plausible server fronting. More principled than mimicking just JA3/ClientHello. Android support exists.
**Weaknesses:** comparatively heavyweight; maintaining Chromium alignment is costly, needs a compatible frontend/server and certificates; cannot overcome blocked server IP or universal whitelists. [Original project](https://github.com/klzgrad/naiveproxy) · [sing-box outbound support](https://sing-box.sagernet.org/configuration/outbound/naive/)

### 6. AmneziaWG 3.1

**Strengths:** WireGuard cryptographic model preserved, evolving UDP handshake/header/body/sequence/timing camouflage; version 3.1 addresses mid-2026 filtering patterns. Strong option for an *integrated* UDP VPN transport when user controls compatible server.
**Weaknesses:** cannot simply wrap a standard WireGuard app in a generic TCP SOCKS proxy and expect it to become AWG3.1; requires matched server/client support; extra padding can cost mobile bytes and throughput, and UDP/IP blocking persists. [Official architecture/version notes](https://docs.amnezia.org/documentation/amnezia-wg/)

### 7. Hysteria2 / Gecko-Salamander / Chrome QUIC mimicry

**Strengths:** UDP/QUIC based, sophisticated congestion handling; Salamander/Gecko optional QUIC-obfuscation modes; 2026 client-side Chrome QUIC handshake parrot in maintained implementations.
**Weaknesses:** client+server matching required for obfs; strong UDP censorship makes it unusable; basic Hello mimicry may still leave behavioral fingerprints, and port-hopping is not reliable if a whole IP or UDP protocol is filtered. [sing-box Hysteria2](https://sing-box.sagernet.org/configuration/outbound/hysteria2/) · [Hysteria releases](https://github.com/HyNetworks/hysteria/releases)

### 8. TUIC

**Strengths:** performant QUIC transport, both TCP and UDP relay, tunable congestion, supported by established proxy cores.
**Weaknesses:** QUIC Initial visibility, UDP blocking, handshake/fingerprint differences, server dependency; forcing UDP over streams adds overhead. [Official implementation config](https://sing-box.sagernet.org/configuration/outbound/tuic/)

### 9. ShadowTLS v3 + AnyTLS

**Strengths:** cover a real TLS handshake / add framed traffic and padding/multiplex features on top of TLS, integrated into sing-box. Server side can blend with real-site handshake behavior.
**Weaknesses:** correctly configured remote servers required, cover-site and active-probe characteristics vary; these are transport schemes, **not unconditional patches for arbitrary VPN protocols**. [ShadowTLS official](https://github.com/ihciah/shadow-tls) · [sing-box inbound](https://sing-box.sagernet.org/configuration/inbound/shadowtls/) · [AnyTLS](https://sing-box.sagernet.org/configuration/inbound/anytls/)

### 10. ECH (Encrypted ClientHello), RFC 9849 (March 2026)

**Strengths:** standards-track solution encrypting inner ClientHello, eliminating plaintext origin SNI for servers supporting ECH. A significantly more sustainable privacy primitive than perpetual Host-case tricks.
**Weaknesses:** requires ECH-enabled endpoint, DNS HTTPS/SVCB bootstrap and client TLS support; visible outer metadata and destination IP remain; censor can interfere with ECH or its prerequisites. Must not fake that ECH enables universal access to an incompatible VPN server. [IETF RFC 9849](https://www.rfc-editor.org/rfc/rfc9849.html)

### 11. DoH/DoT/ODoH and DNS separation

**Strengths:** solve/measure DNS tampering, privacy of DNS request data; DoH often uses standard HTTPS. Useful for high-integrity failure diagnosis and preventing incorrect routing.
**Weaknesses:** do NOT hide destination IP or defeat TLS SNI/IP blocking. DNS can be leaked outside intended VPN; ODoH adds endpoints and complexity and does not itself provide full web traffic anonymity. [DoH docs](https://developers.cloudflare.com/1.1.1.1/encryption/dns-over-https/) · [ODoH](https://developers.cloudflare.com/1.1.1.1/encryption/oblivious-dns-over-https/)

### 12. MASQUE CONNECT-UDP/CONNECT-IP (RFC 9298/9484)

**Strengths:** standards-based UDP/IP tunneling over authenticated HTTP, potentially ideal for **Helping** transport independence where a client/server pair implements MASQUE; used in modern network stacks.
**Weaknesses:** requires remote MASQUE proxy and compatible client, may increase cost and dependencies; not a generic UDP-over-SOCKS feature or a magic proxy for VPN apps that don't support it. Endpoints may still be blocked. [RFC 9298](https://www.rfc-editor.org/rfc/rfc9298.html) · [RFC 9484](https://www.rfc-editor.org/rfc/rfc9484.html)

### 13. Tor obfs4 / WebTunnel / Snowflake / meek

**Strengths:** operationally diverse fallback mechanisms; obfs4 protects against naive probe detection using secrets; WebTunnel imitates HTTPS; Snowflake uses volunteer WebRTC intermediaries; meek historically uses CDN fronting.
**Weaknesses:** generally are **whole transport + distribution systems**, not drop-in ByeDPI flags; moving traffic onto Tor changes privacy, latency and architecture. meek fronting depends on CDN support and often has throughput limitations. WebTunnel needs self-hosted web endpoints. Snowflake availability and latency fluctuate. These serve as **reference/optional separate ecosystem**, not the core first step. [Tor transport overview](https://support.torproject.org/tor-browser/circumvention/unblocking-tor/) · [WebTunnel](https://community.torproject.org/relay/setup/webtunnel/)

### 14. Geneva (automated strategy evolution)

**Strengths:** published research concept for discovering middlebox desynchronization opportunities with genetic search rather than copying existing profiles.
**Weaknesses:** research implementation requires Linux privileged packet hooks; experiments can be conspicuous, costly, and highly network-specific. **Do not run global live mutation search on users' traffic**; borrow the concept for an isolated controlled lab with safety/traffic budgets. [Research repo](https://github.com/Kkevsterrr/geneva)

### 15. TCP-specific microstrategies: RTT-based segmentation, fake TTL, packet disorder, OOB

**Strengths:** targeted per-connection, often doable via existing C hooks without a server change; cheap when only a minority of connections need it.
**Weaknesses:** sensitive to kernel, routing, packet coalescing, deployment and censor's TCP reassembly. Multiple unnecessary tricks can cause throughput loss and overheat. Keep an allowlist, versioned strategy schema, performance budget and safe fallback.

## D. Future of the threat model: what is likely to last

- **Protocol correctness + standard transport:** ECH, MASQUE, real HTTPS/browser TLS stacks and server authentication are conceptually more maintainable than static malformed packets, *when both endpoints are compatible*.
- **Evidence-based control plane:** decoupled fingerprint/diagnostic metadata is useful even if every individual evasion mechanism becomes obsolete.
- **Native desync:** a worthwhile complementary rootless tactic, especially where server-side changes are impossible; unlikely to remain a comprehensive answer to modern mixed TCP/UDP/active-probe/allowlist blocking.
- **UDP-specific paths:** new QUIC-specific blocking research rules out declaring TCP-only improvements universal. Support different outbound families and transparent fallbacks, not blind forcing of HTTP/3.
- **Evolving censorship:** human and automated middlebox vendors can normalize protocol tricks, improve reassembly and change policy with little notice. Any untested 'permanent unblocking' claim is false.

## E. Experiment and observability blueprint

**Controlled outcomes:** `DNS_BAD`, `IP_UNREACHABLE`, `TCP_CONNECT_FAILED`, `TLS_HANDSHAKE_TIMEOUT`, `TLS_AUTH_FAILED`, `HTTP_BLOCK_PAGE`, `HTTP_REJECTED`, `QUIC_HANDSHAKE_FAILED`, `FIRST_BYTES_OK_BUT_THROTTLED`, `SERVER_AUTHENTICATED`, `NATIVE_ENGINE_FAILED`, `UNKNOWN`. Do not conflate a failed TLS certificate validation with successful censorship circumvention.

**Four baselines:** (1) direct; (2) local SOCKS without transformations; (3) isolated single candidate; (4) chosen adaptive profile. Shuffle order, network, time, IP family and cache state. Treat timeouts and application problems as separate classes. Use valid user-controlled/consented health endpoints.

**Measurements:** binary reachability + content sentinel; TLS certificate/server authenticity; first-byte and full-object latencies (p50/p95); success rate over repeated attempts; RTT/retransmits if available; DNS route and IPv6 handling; UDP/QUIC fallback; Android CPU, memory, radio bytes, foreground-service lifecycle and battery/thermal impact.

**Measurement pitfalls:**
- A 200 response can be a block page.
- A 4 KiB probe cannot detect throttling after 16–20 KiB or later.
- Native state may warm up with first-connection effects.
- Repeating many attempts on the same IP may trigger temporary per-flow blocks.
- A VPN handshake cannot be inferred from an HTTP health check.
- A 'fake' packet may reach the real endpoint in a short path.
- A different cell network, NAT, VPN or DNS provider can change results entirely.

**Privacy policy:** collect the minimum metadata required, never full traffic payloads or subscription credentials, allow local-only export with redaction, and avoid scanning other people's hosts for research without permission.

## F. Phase-1 engineering gates (recommended)

**Gate 1 — Accuracy:** implement typed outcomes, known-good body checks, TLS/auth errors and direct/no-transform baselines; test 4 KiB and 64 KiB responses separately so late throttling is visible.

**Gate 2 — Native upgrade isolation:** create pinned-commit update branches for ByeDPI and HEV separately. First ABI/JNI compile + Android install, then differential TCP/UDP/IPv4/IPv6/stop/restart/energy checks.

**Gate 3 — Strategy policy:** introduce a versioned profile format with a small number of ranked techniques and a finite retry/time/packet budget; no unbounded 'add 10 more fake' escalation.

**Gate 4 — Helping compatibility model:** define external-client proxy-only compatibility; leave built-in VPN engine selection to a separate benchmark between sing-box and libXray (APK size, update rate, protocol support, license, Android wrapper, energy).

**Gate 5 — Security:** preserve outstanding Play Protect warning evidence; run fuzzing/static diagnostics and native provenance checks before APK distribution or public claims of harmlessness.

## G. Final ranked list for N-LIC (feasibility AND longer-term usefulness)

*Scale: integration 5=small change, 1=major rearchitecture; future value 5=portable, maintainable capability, 1=highly fragile. These are qualitative engineering judgements, NOT bypass success percentages. Ranking is tailored to N-LIC's current Android C/SOCKS architecture; changing to a general-purpose integrated VPN core changes the order.*

| Rank | Solution | Integration /5 | Future /5 | Strong point | Limitation / cost | Decision |
| ---: | --- | :---: | :---: | --- | --- | --- |
| 1 | Evidence-based DPI classifier + control probes | 5 | 5 | Stops false successes and picks correct class of fix | Requires careful test endpoint semantics | Start immediately |
| 2 | Upgrade and selectively harden pinned ByeDPI C core | 5 | 4 | Lowest integration friction, upstream fixes available | JNI/ABI and regressions | Isolated branch |
| 3 | N-LIC Adaptive per-network/endpoint profile cache and rollback | 5 | 5 | Keeps strategies fast and relevant as conditions change | Classification and cache-poisoning risks | Build after #1 |
| 4 | Targeted TLS record split / minimal TCP split | 5 | 3 | Rootless and already in core; low cost if scoped | DPI normalization, TLS/QUIC distinction | Keep and benchmark |
| 5 | Typed QUIC/UDP failure diagnosis and safe protocol fallback | 4 | 5 | Fixes fundamental TCP-only blindness | Cannot make a UDP-only app work over TCP by magic | Add now |
| 6 | DNS/IPv6 policy and DoH diagnostic integration | 4 | 5 | Addresses separate real blocking and leak pathways | Not a replacement for IP/SNI bypass | Add now |
| 7 | Update HEV tun2socks with ABI/perf regression gate | 4 | 4 | Current upstream fixes Android JNI, UDP/TCP, idle memory | Java/JNI binding changes | Staged upgrade |
| 8 | Targeted fake/disorder/OOB with complexity and retransmit limits | 5 | 2 | Reuses current engine with no remote server | Fragile and often noisy; can reduce throughput | Selective fallback only |
| 9 | Authenticated loopback SOCKS5/HTTP CONNECT gateway for Helping | 4 | 4 | Reuses app network plumbing for compatible VPN clients | UDP-only and no-proxy clients unsupported | Design Phase 2, early tests |
| 10 | Standards-based ECH awareness / compatible TLS support | 3 | 5 | Long-term defense against plaintext inner SNI exposure | Needs server DNS/TLS ecosystem support | Strategically important |
| 11 | sing-box integrated transport core (SFA/libbox patterns) | 3 | 5 | Broad protocols/routing/DNS, strong Android ecosystem | Heavy embedded core, GPL, version/API churn | Phase 2 research spike |
| 12 | NaiveProxy Chromium TLS stack | 3 | 5 | Authentic browser-like TLS/HTTP behavior | Larger dependency, server/fronting requirements | Strategic optional transport |
| 13 | Xray-core/libXray integration | 3 | 4 | Good VLESS/REALITY/XHTTP reach, Android library | Large core, server profiles and lifecycle | Alternative to #11, avoid two cores initially |
| 14 | VLESS+REALITY / XHTTP transport profiles | 3 | 4 | Strong authenticated HTTPS-like server-mediated path | Requires server; fingerprint/IP exposure remain | Through a maintained core |
| 15 | Hysteria2 Gecko/Salamander + Chrome QUIC behavior | 3 | 4 | Mature lossy-link UDP transport with evolving obfs | UDP blocking and exact server/client match | Through compatible core |
| 16 | ShadowTLS v3 / AnyTLS | 3 | 4 | TLS-shaped transport with maintained sing-box support | Server support and behavioral signature risks | Later integrated profiles |
| 17 | AmneziaWG 3.1 matched client/server adapter | 2 | 4 | Modernized WG-compatible encrypted transport semantics | Complex UDP native integration; no generic SOCKS patch | Phase 2 dedicated adapter |
| 18 | MASQUE CONNECT-UDP/CONNECT-IP | 2 | 5 | IETF standards, general HTTP-based tunnel semantics | Server/client ecosystem and footprint | Strategic R&D |
| 18a | Xray FinalMask layered post-encryption masks | 2 | 4 | Modern transport-aware TCP/UDP/QUIC camouflage layers | Requires core/server compatibility, some modes fragile | Design reference; not direct ByeDPI port |
| 19 | zapret2 portable policy concepts (not NFQUEUE engine) | 2 | 4 | Lua-driven filters/profiles and deep packet research | Root/privilege portability of packet layer | Mine ideas; port selectively |
| 20 | QUIC Initial/version-specific adaptive research | 2 | 3 | Addresses observed QUIC-v1 censor behavior | Moving target, protocol-compliance/security pitfalls | Lab-only experiments |
| 21 | Tor WebTunnel / obfs4 optional client transport | 2 | 4 | Diversifies transport and resists some probes | Distribution, latency and different security model | Separate optional integration |
| 22 | TUIC transport | 3 | 3 | Modern QUIC proxy/UDP relay | UDP block, server support and transport fingerprints | Through integrated core only |
| 23 | Snowflake / ephemeral volunteer proxy transport | 1 | 4 | Diverse bridge addresses and WebRTC-based reachability | Broker/volunteer availability, overhead and latency | Optional ecosystem, not Core |
| 24 | Geneva-style automatic strategy generation | 1 | 3 | Potential novel discoveries without static presets | Linux/root hooks, noisy experiments and reproducibility | Controlled research lab only |
| 25 | meek-style CDN domain fronting | 1 | 2 | Can exploit supported fronting where possible | Many clouds restrict fronting, costly/slow and brittle | Do not build core around it |
| 26 | Random fake packets everywhere / permanent traffic flooding | 1 | 1 | Easy to prototype as a visual gimmick | Fingerprintable, potentially harmful to performance and bandwidth | Reject |

### Narrow recommendation

**In Phase 1, ship only G1–G3 and the low-risk portions of #4–#8** after actual device tests. Preserve the privacy-hardening branch and no-Play-Protect-bypass policy. Treat VPN protocol engines as a comparative design study, not a stealth 'drop-in' to ByeDPI. The highest-value original N-LIC feature would be *why-it-failed diagnostics with bounded adaptive recovery*, not an overlong list of aggressive flags.

## Source references and current-version anchors

- [ByeDPI releases](https://github.com/hufrea/byedpi/releases), [ByeDPI main repo](https://github.com/hufrea/byedpi)
- [zapret2 releases v1.0.6 (2026-10-21)](https://github.com/bol-van/zapret2/releases), [zapret2 Lua](https://github.com/bol-van/zapret2/blob/master/lua/zapret-lib.lua)
- [hev-socks5-tunnel 2.19.3 (2026-10-25)](https://github.com/heiher/hev-socks5-tunnel/releases)
- [sing-box releases 1.14.4 (2026-10-24)](https://github.com/SagerNet/sing-box/releases), [TLS](https://sing-box.sagernet.org/configuration/shared/tls/), [TUN](https://sing-box.sagernet.org/configuration/inbound/tun/), [Hysteria2](https://sing-box.sagernet.org/configuration/outbound/hysteria2/)
- [Xray FinalMask reference](https://github.com/XTLS/Xray-docs-next/blob/main/docs/en/config/transports/finalmask.md) and [sing-box privileged TLS spoof](https://sing-box.sagernet.org/configuration/shared/tls/)
- [Xray-core releases](https://github.com/XTLS/Xray-core/releases), [Xray transport compatibility](https://xtls.github.io/en/config/transport.html)
- [NaiveProxy Chromium reference](https://github.com/klzgrad/naiveproxy)
- [AmneziaWG 3.1 notes](https://docs.amnezia.org/documentation/amnezia-wg/)
- [IETF RFC 9849 ECH](https://www.rfc-editor.org/rfc/rfc9849.html), [RFC 9298](https://www.rfc-editor.org/rfc/rfc9298.html), [RFC 9484](https://www.rfc-editor.org/rfc/rfc9484.html)
- [OONI Telegram Russia (2026-03-30)](https://explorer.ooni.org/findings/2026-russia-blocked-telegram), [OONI Bluesky Russia (2026-04-23)](https://explorer.ooni.org/findings/2026-russia-blocked-bluesky)
- [FOCI 2026 QUIC Russia](https://www.petsymposium.org/foci/2026/foci-2026-0010.php)
- [Tor transport overview](https://support.torproject.org/tor-browser/circumvention/unblocking-tor/), [Geneva](https://github.com/Kkevsterrr/geneva)
- [NDSS active probing study](https://www.ndss-symposium.org/ndss-paper/detecting-probe-resistant-proxies/), [USENIX VPN fingerprinting](https://www.usenix.org/conference/usenixsecurity22/presentation/xue-diwen)

*This report is a living source audit. Revalidate moving project versions and field performance before implementing new techniques.*
