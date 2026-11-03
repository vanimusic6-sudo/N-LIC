# N-LIC 2.0.0: VPN client, evidence-driven DPI decisions and safety boundaries

Date: 2026-11-04 — Development Preview, not a production-ready secure VPN.

## What changed

N-LIC is now designed as a single VPN-focused app rather than a ByeDPI command-line wrapper. The main screen has the VPN power action, selected server, short network status and safe navigation. The Core centre imports an encrypted subscription via HTTPS, a single link, or a bounded local document, updates subscription servers on demand and distinguishes LIC-Auto server selection from the optional LIC-Bay TCP detour. The bare ByeDPI command-line editing surface is removed from the managed libbox build.

### Protocol matrix — 2.0 DEV

| Format | Import | Expected runtime | Caveat |
|---|---|---|---|
| VLESS with verified TLS / REALITY | URI and curated JSON | libbox sing-box SOCKS outbound | Strict cipher/transport allowlist; actual server connectivity not yet demonstrated on user network |
| VMess, verified Trojan TLS, Shadowsocks | URI and curated JSON | libbox | Some URI schemes and transport options not supported |
| Hysteria2, TUIC | URI and curated JSON | libbox QUIC/UDP | LIC-Bay TCP detour explicitly incompatible |
| AnyTLS | URI and curated JSON | libbox TLS | Native schema verification on emulator required |
| WireGuard | standard single-peer .conf /.wg | sing-box 1.14 `endpoints` | Full-tunnel only; one peer; requires real UDP; no LIC-Auto/Bay; not AmneziaWG |
| Other .txt/.b64 or JSON arrays | list of supported secure URIs | depends on protocol | Reject arbitrary scripts and imports containing unknown executable routes |
| Generic OpenVPN .ovpn / AmneziaWG custom keys | **not supported** | none | Do not misrepresent imported unsupported formats as connected |

WireGuard **outbound** was deprecated and removed from recent sing-box; the code uses the supported WireGuard **endpoint**. See https://sing-box.sagernet.org/configuration/endpoint/wireguard/ and https://sing-box.sagernet.org/configuration/endpoint/.

## Why the best config for every Russian operator is not a static table

OONI's 2026 Russia measurement of Telegram covers **hundreds of Russian ASNs**, detects RST/timeouts, and explicitly notes that blocking is not visible on every tested network:
https://explorer.ooni.org/findings/2026-russia-blocked-telegram

The 2024 OONI research also documents TLS-level filtering and SNI-triggered disruption across providers:
https://ooni.org/post/2024-russia-report

ByeDPI upstream documents its SNI midpoint split syntax `-s0+sm`:
https://github.com/hufrea/byedpi/blob/main/README.md

Consequently it would be misleading to brand hardcoded commands as “verified for MTS / Beeline / Rostelecom / Megafon / Tele2 / Yota and all Wi-Fi providers”. Instead N-LIC uses the following transparent adaptive policy:
1. When the user explicitly ran the strategy test, results were saved locally **per network type** (Wi-Fi, cellular, Ethernet), not globally for every carrier.
2. On connection, a candidate is accepted only when results are at most 14 days old, the test completed, 3+ successes and 70%+ positive requests, and the command passes conservative safety checks.
3. Candidates are ranked by actual success, stream throughput/quality (when tested), then packet manipulation cost and latency. No random switching mid-session.
4. Without measurements, N-LIC uses an **unverified** basic SNI split `-s0+sm` for Byedpi, clearly labeled as *baseline*; it cannot guarantee circumvention.
5. These tests are intentionally **user-initiated**. Silent probing of external sites, scanning providers or uploading SIM/SSID/ASN information to remote collection servers is not implemented.
6. LIC-Auto selects secure servers using libbox's own remote connectivity tests; LIC-Bay is optional and **off by default**. It must not be used for WireGuard, Hysteria2 or TUIC.

## Data protection & limits

- Subscription URLs, WireGuard private keys and imported server credentials are encrypted in the app-private Android Keystore-backed AES-256-GCM vault. Backup of secrets is disabled and files are not exported by default.
- File import uses Android Storage Access Framework `OpenDocument` with no blanket storage access and a hard **512 KiB** cap. Config files are parsed as *data*, not run as scripts. JSON local inbound, DNS and route options provided by an untrusted subscription are discarded rather than trusted.
- Download of subscription URLs is explicit HTTPS-only, DNS-rebinding-aware, with no redirects or proxy, and a maximum size. Refresh failure preserves last-known-good cached profiles.
- Loopback SOCKS 127.0.0.1:11089 on LIC-Core is password-protected with a new 192-bit random secret on every session. Native command error messages are scrubbed of passwords/URLs.
- **OPEN SECURITY RISK:** the optional ByeDPI listener for LIC-Bay, generally 127.0.0.1:1080, is still unauthenticated. Other apps may use it while LIC-Bay is on. Production needs isolation or authentication in native proxy.
- **OPEN SECURITY RISK:** no independently verified fail-closed traffic kill switch. Android's native settings for *Always-on VPN* + *Block connections without VPN* are available through a user-consented shortcut. Failure to enable them permits direct traffic during reconnect.
- **OPEN SECURITY RISK:** DNS/IPv6 leakage and app whitelist/blacklist behavior are not yet validated on physical Wi-Fi, cellular or an IPv6-only/NAT64 network. The app must not claim guaranteed anonymity.
- **OPEN SUPPLY-CHAIN RISK:** libbox native binary is pinned to SHA-256 but is built externally; a pinned hash establishes byte integrity, not a reproducible chain of trust.
- **OPEN COMPATIBILITY RISK:** AnyTLS and WireGuard compilation must be checked by the **real pinned native Go parser**, not only JVM JSON tests. ARM64 APK compilation success does not prove server VPN connectivity.

## Mandatory end-to-end acceptance before stable release

1. Test one known good TLS/REALITY server with LIC-Bay OFF, and verify remote HTTPS GET 204 over the encrypted SOCKS as well as actual apps over Android TUN.
2. Import WireGuard .conf, connect via UDP, validate key handshake plus DNS and IPv6 forwarding, reject profiles with arbitrary scripts and incomplete AllowedIPs. Confirm the tunnel breaks safely on failure.
3. Test AnyTLS remote handshake on both Wi-Fi and cellular. Compare with sing-box reference client using the **same** server and profile, no user secrets in bug reports.
4. Test subscription refresh while connected and disconnected; stale cache and encrypted storage survive failed refresh, app upgrades and process death.
5. Test stale benchmarks, operator switches, 2G/4G/5G/Wi-Fi and VPN handover without sharing network identifiers, then measure throughput, radio heat and battery use.
6. From a second test app try to access both local listeners; verify Core requires credentials, and determine how to isolate LIC-Bay.
7. Check Android Always-on + block-without-VPN, DNS, UDP/QUIC, IPv6-only NAT64, crash/revoke behavior, Play Protect and apk signing provenance.
8. Only after on-device evidence consider merging this preview into the main branch.

**Summary:** 2.0.0 is a working *implementation preview* with automated builds and source audits, **not** a validated leak-free production VPN or a guaranteed anti-censorship product.
