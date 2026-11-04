# N-LIC: video-transfer diagnostics and staged strategies

Date: 2026-11-03. Branch: `feature/nlic-video-ip-diagnostics-20261103`.

## Why this exists

**Observed**: a small YouTube thumbnail and a speed-test homepage open, while actual YouTube videos load extremely slowly; Telegram remains inaccessible. Neither behavior can be safely diagnosed by a 4 KiB HTTP-200 homepage check.

**Measured elsewhere**: OONI documented cases in Russia where certain CDN flows fail after roughly 16–20 KiB. This is evidence of one *possible* failure mode, not proof that it is happening to this user.

## What was changed

- N-LIC's strategy testing now has an optional **256 KiB HTTPS Range probe** via exactly the same local SOCKS5 engine used to test a candidate strategy.
- A user may supply a valid, short-lived HTTPS `googlevideo.com/videoplayback` URL (including its signed query) or use the fixed Cloudflare fixture at `https://speed.cloudflare.com/__down?bytes=262144`. The latter is **not equivalent** to the YouTube CDN and is only a comparison endpoint.
- The sample is read in 8 KiB chunks, with individual read timeouts and a bounded 25-second processing budget. Transfer throughput is calculated only from the observed sample; it is not an entire video's bitrate.
- Results distinguish complete transfer, slow complete transfer, stall after some data, reset, early EOF, small object, non-media response, expired/forbidden link, redirects, and network errors.
- The sample URL is removed from Android preferences when testing starts. The complete signed URL and body are **not stored in the results** or deliberately written to logs; only host and counters are retained. Removing preferences does not guarantee secure erasure from flash storage.
- Video result and KiB/s appear in strategy cards and safely summarized copied diagnostics; successful full transfers are ranked above strategies which only load small HTML/images, provided normal site success is equal.
- Experimental profile catalog now contains **13** candidates, including the user's observed effective `-r3+s`, `-r3+s -T3 -At -o1 -r-5+se`, and `-o1 -r-5+se -T3 -At -d1 -f-1 -t8` commands, plus progressively staged fallbacks. No active user profile is changed without their action.

## Limits

1. Test traffic uses Android `HttpURLConnection` over SOCKS5 and usually HTTP/1.1. It does **not** prove that Chrome/YouTube HTTP/2, QUIC/HTTP/3, Android native player, YouTube token authorizations or app CDN routing work.
2. A small response may mean the server legitimately returned less than 256 KiB, not a block. The sample result is therefore `SHORT_OBJECT`.
3. A reset/timeout and poor transfer rate are *symptoms*, not proof that a DPI box did it. Expired signed URLs, upstream VPN, congested CDN edges, mobile radio, or TLS negotiation also cause failures.
4. Users should never share full signed `videoplayback` URLs in public logs/screenshots.
5. No live per-flow auto-switching is enabled, because restarting a SOCKS proxy mid-video could destroy already established connections.
6. The new strategy options are *candidates*. They are not field-validated bypass guarantees.

## Telegram: a different class of failure

Native Telegram clients connect to IP-addressed data centers using MTProto; browsing `telegram.org` only tests a website, not Telegram's actual messaging transport. Official Telegram docs say DC IP/port options change, and that MTProxy is an outbound remote relay used when Telegram server IP ranges are inaccessible.

If the actual *destination IP* is dropped or blackholed upstream, manipulating TLS SNI/record segmentation at the device **cannot restore routing to that destination**. A working **remote MTProxy/SOCKS relay or a trusted remote VPN tunnel** is required. N-LIC's existing local SOCKS proxy has no independent remote server and is not itself an MTProxy service. An integrated optional trusted upstream relay, with explicit user-supplied server settings and leak protection, belongs to the later Helping phase; it is **not implemented in this branch**.

## Test checklist

1. Keep the same network, upstream VPN setting, and streaming URL for comparison. Consider signed-link expiration: if a request returns HTTP 403, discard the comparison.
2. Open N-LIC → Test → Settings. Enable video-range checks and enter a valid sample URL. The URL is removed from preferences after a test starts.
3. Run the 13 profiles and compare `bytesRead`, `outcome` and transfer `KiB/s`, not only website `successCount`.
4. Also test with and without upstream VPN and compare direct native YouTube playback manually: a good proxy sample can still coexist with bad QUIC playback.
5. Run on Wi-Fi and mobile separately; note device heat, network resets, any startup regressions.
6. Do not disable Play Protect to install an APK. Security warning remains open and CI cannot certify malware safety.

## Future architecture experiments (not shipped)

- Domain-aware DNS/CDN diagnostics with neutral trusted control domains, direct/proxy comparison, and transport-specific UDP/QUIC trace classifications.
- Local stable SOCKS sessions, ability to maintain existing connections while selecting a profile only for new connections; cache with network-identity key, expiry and rollback.
- Experimental TCP-only fallback **only for a user-selected domain** if UDP/QUIC reliably fails; never unconditionally block all UDP because this can break DNS, games, video and VPNs.
- Helping with consented remote MTProxy / SOCKS5 / VLESS relay, no dependency on blocked Telegram IPs.
