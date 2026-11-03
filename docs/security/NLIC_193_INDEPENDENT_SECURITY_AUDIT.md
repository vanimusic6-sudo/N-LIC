# N-LIC 1.9.3 — independent security review of LIC-Core and LIC-Bay

Date: 2026-11-03  
Branch: `fix/nlic-core-bay-ui-security-20261103`  
Scope: Android application code, pinned libbox 1.14.3 integration, HEV SOCKS5 bridge, legacy ByeDPI detour, subscription import, secrets and lifecycle. This review is **source-based**, not a penetration test on a physical device. CI code scanning and user-device tests are separately required.

## Threat model

N-LIC handles access tokens in HTTPS subscription URLs, VPN credentials in proxy URIs, encrypted remote traffic, DNS bootstrap, and a native SOCKS5 listener inside the user's Android application. Attackers considered: (1) another app running on the same phone; (2) a malicious subscription provider or compromised subscription host; (3) an untrusted Wi-Fi/cellular network; (4) transient VPN and process failures; (5) compromised third-party native AAR or Gradle dependencies.

## Findings and status

| Severity | Finding | Remediation / status |
|---|---|---|
| **High, fixed in branch** | Core exposed an unauthenticated `127.0.0.1:11089` mixed HTTP/SOCKS listener. On Android, another app may be able to use TCP loopback ports opened by N-LIC, bypassing the app's intended traffic policy. | Make Core inbound SOCKS5-only; create 192-bit ephemeral random password per session; pass it to sing-box inbound `users` and HEV `socks5.username/password`. Added positive handshake and compiler unit tests. **Device adversarial test still required**. |
| **High, fixed in branch** | HTTPS subscription importer resolved public IP addresses before opening a new, separately resolved HTTPS socket (DNS rebinding / check-use mismatch). | Replace fetch with OkHttp, a validating DNS callback for the *actual* dial and fail if any answer is private, loopback, multicast, or link-local; no proxy, no redirects, strict HTTPS and response-size limit. Added URL/IP guard tests. |
| **High, fixed in branch** | VPN state was published as Running before remote authentication / readiness; UI could claim a VPN connection that had never authenticated. | Delay success until native engine completes its handshake and remote HTTPS probe. Native startup now reports redacted stage codes. The UI also distinguishes an Android foreground VPN from a locally verified remote endpoint. |
| **Medium, fixed in branch** | Experimental Core could leave IPv6 outside TUN according to legacy mobile carrier policy. | Capture an IPv6 route for Core mode while retaining legacy policy when Core is off. **No real IPv6-leak pass claimed** until measured on both Wi-Fi and cellular. |
| **Medium, fixed in branch** | Native libbox exceptions might reveal credential-bearing outbound JSON; plaintext config snapshots persist unless explicitly erased. | Never forward native exception strings to logs or UI; record safe phase only. Ensure libbox's `configuration.json` is removed and reject startup if removal fails. Note: transient plaintext files remain an upstream libbox behavior and require device forensics. |
| **Medium, fixed in branch** | Network-interface enumeration could return an empty list if just one mobile interface threw an exception; callback marker was updated before notification succeeded. | Per-interface error isolation and only mark changes after successful native callback; event driven, no polling. |
| **Medium, partially mitigated** | LIC-Bay startup previously only tested whether ByeDPI SOCKS accepted a greeting, not whether the selected encrypted server could be reached through it. | Fixed-node LIC-Bay now checks SOCKS5 CONNECT to the selected host/port through ByeDPI before starting libbox, then native libbox validates its real outbound over TLS; URLTest mode relies on sing-box's per-node encrypted checks. **It may still fail on specific DPI/network policies**. |
| **Medium, OPEN** | Legacy LIC-Bay ByeDPI listener on `127.0.0.1:1080` is not authenticated. Other apps on the phone may be able to use it. | LIC-Bay remains default OFF and the new interface warns about the risk. A production fix needs native ByeDPI authentication or an isolated Unix-domain FD transport; simply protecting sing-box's inbound does not protect this second listener. |
| **High, OPEN** | This experimental VPN does not implement a proven end-to-end always-on kill switch or guarantee absence of DNS, UDP, IPv6 and traffic leaks when startup fails or links hand over. | Must run physical tests with Android Always-on VPN/Block connections without VPN and verify fail-closed behavior on Wi-Fi and cellular before use for sensitive data. Do **not** advertise a production kill switch. |
| **High, OPEN** | Native libbox AAR comes from an externally published third-party GitHub artifact; matching SHA-256 only pins bytes. | Verify provenance, reproducible build and SBOM; review license of the shipped native library and transitive dependencies before public production release. |
| **Medium, OPEN** | Not every imported URI option and transport variant is supported by the strict sing-box compiler. | Unsupported profiles fail closed. Test representative VLESS/REALITY, Trojan, VMess, Shadowsocks, HY2 and TUIC with real non-secret samples locally; no hidden TLS fallback. |
| **Medium, OPEN** | Android/Google Play Protect had flagged previous builds. | Build signing is pinned to a distinct DEV certificate. A successful release compilation/CodeQL check is **not** a malware attestation or Play Protect approval; do not disable Play Protect. |

## Evidence and automated checks

- The workflow `N-LIC Quality Gate` executes Android debug compilation and unit tests.
- The dedicated `N-LIC 1.9.3 Core Preview APK` workflow builds minified **release** code with pinned native libbox AAR hash, release unit tests, lint, APK signature and ELF verification.
- The `N-LIC CodeQL security audit` separately runs `java-kotlin` and `c-cpp` security-extended scanning.
- Regression tests cover URL validation, public-IP DNS answers, randomized SOCKS-auth configuration and SOCKS5 authentication bytes, and existing secure VLESS/Reality parsing.
- Workflow results must be inspected for the **final commit**; queued and incomplete jobs are not evidence of success.

## Device verification still required

1. On a test device, verify single-server VLESS/REALITY or Trojan/TLS first with LIC-Bay **OFF**, using actual remote HTTPS, DNS and IPv4/IPv6 external-route checks.
2. Exercise foreground/background, airplane mode, Wi-Fi→cellular and cellular→Wi-Fi handover, VPN revocation, service crash, TCP+UDP and QUIC. Confirm there is **no** false Connected state.
3. Turn on LIC-Bay only on a TCP-capable node; compare direct vs ByeDPI detour and check that the correct upstream server is connected. Attempt unauthorized use of both loopback SOCKS ports from a separate test app.
4. Audit on-device sensitive file storage, process memory snapshots, logs, Android backup, network packet capture and default-route/Private DNS behavior.
5. Only after those measurements decide whether to merge and promote release beyond the explicitly experimental DEV certificate.

## Bottom line

The branch removes several concrete source-level vulnerabilities, introduces safer failure reporting and improves connection diagnosis. **It is not yet a security-certified VPN.** In particular, LIC-Bay's secondary unauthenticated SOCKS listener and system-wide fail-closed protection are outstanding before production use.
