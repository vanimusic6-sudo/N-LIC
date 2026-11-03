# N-LIC: four-phase research and engineering roadmap
**Established:** 2026-11-03  
**Development baseline:** `research/dpi-probe-audit-20261103`, stacked on `feature/nlic-identity-security-20261103`.  
**Status:** planning, not a claim of completed or working anti-DPI features.

## Project goal

Build a transparent, testable, privacy-conscious Android DPI-circumvention toolkit with two distinct modes:

- **Standalone N-LIC:** local desynchronization/proxy for eligible traffic, with optional Android TUN/VPN routing.
- **N-LIC Helping:** a local proxy or integrated VPN transport adapter that tries compatible low-cost DPI mitigations **on the connection to a user's VPN server**. It never claims to be a universal VPN bypass, replacement for encryption, or a way to hide all network metadata.

Four phases follow the requested order; critical security tests are **cross-cutting gates** and must not be postponed until phase 3.

## Phase 1 — learn real blocking mechanisms and integrate proven techniques

**Research:** Distinguish DNS interference, IP blocking, TCP resets, TLS ClientHello/SNI filtering, UDP/QUIC filtering, active probing, throughput shaping, and network instability. Distinguish what the endpoint and ISP can observe from what remains encrypted.

**Compare implementations:** N-LIC/ByeDPI core (`hufrea/byedpi`), current `bol-van/zapret2` (older `zapret` repo is EOL), sing-box, Xray-core, AmneziaWG and applicable client code. Compare protocol scope, Android/root constraints, how failure is detected, fallbacks, test evidence, and licensing. Avoid indiscriminately copying code/strategies.

**Implementation sequence:**
1. Fix correctness of reachability probes and add consistent error taxonomies (stage already started in PR #2).
2. Create a test harness with direct, proxy-without-transform, and one-transformation-at-a-time baselines.
3. Catalogue desync methods actually supported by the pinned Android binary. Determine which upstream fixes/features merit an isolated native update.
4. Implement constrained profile selection by endpoint/network/protocol with TTL and opt-out, only after measuring real improvements.
5. Record controlled test outcomes: repeated success, first-byte time, connection setup, retransmissions, memory/CPU, battery and compatibility.

**Exit gate:** Instrumented and device-tested evidence that changes improve at least one documented network/blocking scenario *without* unacceptable compatibility or thermal regression. Unknown network conditions stay an explicit 'unknown' rather than an invented success.

## Phase 2 — N-LIC Helping: a compatible VPN connectivity assistant

### A. External VPN application — local proxy bridge

Architecture: `VPN client outbound (if it supports upstream proxy) → N-LIC local proxy (127.0.0.1) → VPN server`.

- Run **N-LIC Proxy Mode without N-LIC's own Android VpnService**, so a separate VPN app may own the system VPN interface.
- Offer **SOCKS5/HTTP CONNECT** where explicitly compatible. Never promise that *all* external VPN apps can use a proxy; apps must expose an appropriate upstream proxy/dialer hook.
- UDP-based WireGuard, AmneziaWG, OpenVPN UDP, Hysteria2 and TUIC need specific UDP relay support or transport integration; SOCKS-over-TCP alone is not a universal solution. Determine client/server semantics before adding adapters.
- Verify both sides of the chain, detect loops, protect local inbound against other Android apps, and surface failure reasons.

### B. Imported subscription — integrated transport

Architecture: `Android applications → single N-LIC TUN/VpnService → integrated VPN/proxy core → server`, with a DPI-assist **dialer/transport layer** on compatible connections.

- An Android profile/user normally has **one active VpnService**. Never stack N-LIC VPN and another independent VPN TUN service and claim they run together.
- Select a maintained compatible core (candidate: sing-box or Xray-core) following code/security/licensing/build review rather than reimplementing cryptography.
- Import explicit supported URI/config formats (for example, `vless://`, `trojan://`, `ss://`, `hysteria2://`, and compatible structured configs), and detect protocols from parsed, validated scheme/type. Let users override import results. **A subscription URL alone does not prove a protocol** and importing arbitrary unknown formats is unsafe.
- Store secrets privately. Subscription tokens, server passwords, keys and full URLs must never appear in crash logs or telemetry. Restrict subscription remote fetch/redirects to intended HTTPS sources.
- Expose clear modes: standalone, Helping external proxy, Helping integrated VPN. No silent mode switch that creates leaks.
- Use per-protocol compatibility gating; do not modify integrity-protected/encrypted payload bytes or assert that an arbitrary VPN handshake is transformable.

### Helping decision engine

Initial states: `DIRECT_BASELINE → CLASSIFY_FAILURE → COMPATIBLE_LIGHT_RETRY → LIMITED_ESCALATION → CONNECTED / FAIL_CLOSED`.

- Only run tests needed for the observed transport.
- Change first-flight handshake handling only if legal for that protocol and demonstrated compatible with the remote server; avoid permanent extra traffic on successful flows.
- Rate-limit retries with backoff; cache per network/endpoint/protocol with expiry, explicit rollback and a 'disable helping' switch.
- Report whether a connection genuinely reached the intended authenticated server, not merely whether a proxy returned 200.
- A 'disguise' is not an authenticated identity: do not imply perfect impersonation, anonymity or guaranteed defeat of DPI.

**Exit gate:** At least one controlled external-client SOCKS5/TCP integration and one integrated VPN protocol, both working on physical Android with IPv4/IPv6, DNS, reconnection, backgrounding and VPN lockdown cases reviewed. Unsupported protocols must fail clearly rather than pretending to work.

## Phase 3 — security verification, deeper DPI model, and original ideas

- Threat-model Android IPC, local proxy listeners, untrusted subscription/import parsing, native C networking, dependency and binary provenance, signing/release and DNS/IPv6 routing.
- Audit and fuzz native parsers (`ByeDPI`, `hev-socks5-tunnel`) using ASan/UBSan where toolchains permit. Keep dependency revisions pinned; verify native build contents.
- Build a controlled DPI behaviour taxonomy from repeatable packet captures and reproducible synthetic filters. Distinguish performance defects, external network failures, block pages and actual protocol discrimination.
- Prototype new experimental transforms **only** behind feature flags and compatibility tests; require measurable benefit vs a simpler strategy, with automatic safe rollback.
- Explicitly resolve the existing Google Play Protect harmful-app warning through provenance/security investigation and the official review path; never try package/icon/signature rotation as a bypass.

**Exit gate:** Documented threat model, meaningful Android/native security test coverage, reviewed open findings, provenance record and reproducible protocol-specific experiments; no claim of full security from static checks alone.

## Phase 4 — usability, polish and release

- Finish original N-LIC identity, icon, typography, accessibility, Android TV support and motion/energy profiling.
- A concise home screen: **Standalone**, **Helping (external app)**, **Helping (imported VPN)**, with a live connection-state explanation.
- One-tap diagnostics export that removes tokens/credentials and excludes sensitive user traffic; clearly labelled experimental toggles and fallback/rollback options.
- Release checklist: functionality on device, cold start and battery benchmarks, fail-closed/leak checks, signing/provenance, license attribution, and unresolved Play Protect investigation outcome.

## Architecture boundaries to settle early

| Item | Technical constraint | Project decision |
| --- | --- | --- |
| Two VPN apps on Android | Normally one active `VpnService` per user/profile | External: N-LIC proxy only. Integrated: N-LIC is the single VPN service. |
| 'Any VPN app' support | Many clients do not allow upstream SOCKS and some require UDP | Clearly show **supported / needs adapter / unsupported**, per client and transport. |
| SOCKS5 listener on localhost | Other apps may reach loopback ports | Use authenticated ephemeral listener or another safe IPC boundary; never expose a no-auth proxy to LAN. |
| VPN server handshake | Transport and encryption framing cannot be modified arbitrarily | Apply compatible pre-encryption dialer methods or validated on-wire TCP transforms, with server-authentication validation. |
| DNS and IPv6 | Route/policy mismatches can bypass intended VPN scope | Device-level dual-stack and DNS fail-closed checks are release gates. |
| Power and stability | More fakes/retries/segmentation can increase energy and latency | Minimal effective profile; bounded retries and measured overhead. |

## Public references to investigate (verify revisions before copying)

- Android VpnService lifecycle: https://developer.android.com/develop/connectivity/vpn
- ByeDPI: https://github.com/hufrea/byedpi
- zapret2: https://github.com/bol-van/zapret2
- sing-box: https://sing-box.sagernet.org/
- Xray-core: https://github.com/XTLS/Xray-core
- AmneziaWG: https://docs.amnezia.org/documentation/amnezia-wg/
