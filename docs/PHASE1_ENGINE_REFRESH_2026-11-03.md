# N-LIC Phase 1 — engine refresh (2026-11-03)

## Scope and compatibility

This is a **rootless incremental rebuild**, not a guarantee of universal DPI bypass. It deliberately preserves the user's selected live ByeDPI command, Android VPN semantics, original package signature/release policy, and existing UI profile preferences. Helping subscription import and new VPN cores are out of scope.

## Changes delivered

1. **Typed probe evidence** (`ProbeDiagnostics.kt`): reachable, HTTP rejection, redirect, suspected HTML block page, empty body, read failure, DNS failure, connect failure, TLS failure, socket timeout, network failure, unknown.
2. **HTTP integrity** (`SiteCheckUtils.kt`): no automatic redirects; 4 KiB cap per sample; no full-body downloads; bounded attempts/timeouts/concurrency; store successful request latency and per-site result counts; no URL path/query or body in app log.
3. **Conservative scoring** (`StrategyRanking.kt`): completed and successful strategies first, tied outcomes favour fewer expensive packet transforms and then measured latency. This is a recommendation, not background switching of active user settings.
4. **Safer probe presets**: nine intentionally simpler candidates including a low-impact no-transform `-An` control, record segmentation, simple split, disorder and carefully isolated fake/OOB variants. The 60 old complex presets are preserved under `proxytest_strategies_legacy.list`, not deleted. User-defined commands remain supported.
5. **Lower energy use in tests**: default concurrency 20 → 6 and attempts 1 → 2 for new settings only; existing saved settings are preserved. Full request body and auto-redirect are no longer downloaded/followed.
6. **Proxy argument hygiene**: never log full custom command, domains and SNI from parsed profile. Empty OOB character now safely uses a default. Validate malformed port numerically before starting the worker. Restrict interpolated strategy SNI to a domain-like input.
7. **Native TUN modernization**: test a pinned HEV 2.19.3 revision with TCP/UDP session robustness, idle stack-memory and Android JNI changes. Android Java API changed upstream in 2.17, but N-LIC's existing C shim dynamically loads stable public `hev_socks5_tunnel_main_from_str`/`quit` symbols. A passing build is required; runtime ABI needs device validation.

## Upstream ByeDPI compatibility gate (important)

A candidate advance from `ba532298` to `98875761` **failed compilation** under N-LIC's Android minSdk 21 NDK build: upstream `main.c` uses `getifaddrs`/`freeifaddrs`, unavailable in the Android API 21 compilation environment. The update was **reverted** before delivery.

There is a second, independent compatibility hazard: upstream repurposed `-T/--timeout` to `--tcp-timeout`, introduced `-W/--tls-timeout`, and changed the older `-W/--await-int` shorthand to `-z`. Blindly bumping the submodule would silently change behaviour of user-provided commands. Do not retry the bump without an API-21-compatible parser/build patch and versioned legacy-args migration/tests.

Pinned production ByeDPI baseline is therefore still `ba532298de7b28cfe854aea83d061369d13ca290`.

## Unsolved issues / limits

- Content heuristic only **suspects** known block page text; it can produce false positives and does not detect unknown 200 block pages, binary content or translated notifications.
- Median successful request latency is measured per domain, not full-body speed or protocol handshake RTT. A site that allows 4 KiB then throttles remains unverified.
- A failed TLS handshake might be caused by certificate validity, wrong server settings or network congestion; diagnostics do **not** label it as proven censorship.
- Native engine stop/start remains process-based, with no device-observed performance or leak check. HEV 2.19.3 needs Android runtime smoke test.
- No QUIC Initial detector or automatic failover of arbitrary VPN UDP protocols is implemented yet.
- Existing Play Protect harmful-app warning remains unresolved and must not be bypassed using renaming/resigning tricks.

## Minimum device test matrix

- Android 21/24/29/34+ install and native startup, ARMv7/ARM64 when available; check 16KiB page-size compatibility.
- Proxy-only and VPN mode start, stop, rapid restart, background, screen off; verify no orphan worker or hot loop.
- HTTP and HTTPS 200, 3xx redirect, 403/451, known block HTML, actual timeout, DNS failure, TLS certificate error and incorrect proxy port.
- TCP and UDP, IPv4 and IPv6, Google Private DNS interactions, app allowlist/denylist, VPN always-on/lockdown.
- Captures: local-only metadata and no credentials/SNI logs; no exposed LAN SOCKS listener unless explicitly configured.
- Cold and warm start latency, throughput (small and large objects), p50/p95, CPU, power/radio activity and reconnection under packet loss.

## Relevant CI

PR #3 carries source changes. The N-LIC Quality Gate must run `:app:testDebugUnitTest :app:assembleDebug`; CodeQL is complementary. **Passing CI is not proof of device safety, actual censorship circumvention or Play Protect clearance.**
