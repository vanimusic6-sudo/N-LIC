# LIC-Assist / REALITY TCP — verified same-node recovery (2026-11-05)

**Status:** implementation and unit tests pending GitHub CI and physical-network checks.
The feature must not be called a proven DPI bypass before reproducible field tests.

## What is genuinely under the client's control

The REALITY client uses uTLS to generate a TLS-like ClientHello. In the
N-LIC Xray compiler, the two tested fingerprints are `chrome` and `firefox`.
They change the outer TLS handshake **without** changing the VLESS
credentials, REALITY public key, shortId, SNI, provider-selected Vision
flow, TCP server, IP, or authenticated TLS verification.

REALITY server acceptance and the observable handshake are affected by
fingerprint behavior, but `fingerprint=firefox` **does not embed Gecko** or
create a real browser HTTP/2 session.

A current Xray issue (#7259, opened late October 2026) reports that some
REALITY servers reject old uTLS fingerprints; the reporter observed
chrome/firefox/safari working but Edge failing, apparently depending on
server release and key shares. The suggested underlying cause is unproven
and must not be treated as a protocol guarantee.

A separate incident (#7306) reports stalled multi-segment ClientHello
under hundreds of simultaneous REALITY dials. N-LIC therefore does not
launch concurrent REALITY fingerprint probes.

## What the Smart mode actually does

Only for **manually selected** VLESS+REALITY+TCP RAW:

1. Compile the selected subscription node as-is. Verify protocol and transport.
2. Start pinned Xray core on a password-protected loopback SOCKS listener.
   Local socket readiness alone is **not** success.
3. Validate **end-to-end HTTPS through the selected encrypted Xray
   outbound**, first with the core's certificate-validating HTTPS probe.
   If the single probe fails, try the existing independent certificate-
   verified SOCKS/HTTPS readiness checks against other fixed endpoints.
4. If HTTPS works: keep this exact runtime, create Android TUN only now.
   Do not reconnect, change fingerprint or disrupt working traffic.
5. If and only if an outbound HTTPS check fails after core startup,
   stop the failed runtime, close the SOCKS listener, wait 250 ms, retry
   the **same** server with a different supported uTLS browser ClientHello:
   default/Chrome → Firefox; Firefox → Chrome. At most one alternate.
6. Require the second attempt to pass the **same** HTTPS verification
   before creating Android routes. If both fail, show a safe failure code;
   **never route direct, never drop TLS/REALITY, never switch provider nodes**.

The user has a manual selection: `profile` (safe default), `smart`,
`firefox`, `chrome`. Smart is opt-in. If LIC-Auto is enabled, that
separate mode retains its original node selection and this same-node
fingerprint retry does not run. If the selected profile is not REALITY
RAW TCP, smart mode is a no-op and is not presented as having helped.

## Failure interpretation

- Xray initialization failure is **not** classified as DPI.
- Local SOCKS authentication failure is **not** classified as DPI.
- TLS verification/authentication failure is **not proof** of DPI:
  wrong key, shortId, server-side target behavior, upstream outages,
  or other network failures are possible.
- A single `example.com` failure is **not** classified as VPN failure:
  the readiness path checks several independent certificate-validated
  HTTPS endpoints.
- Do not interpret a simple TCP connection to the server as a usable VPN.
- No VPN credentials, UUIDs, subscription URIs, keys or SNI are logged.

## What we explicitly reject

- Blind segmentation, fake TCP loss or arbitrary random packet injection.
- Disabling certificate checks to make a probe pass.
- Changing REALITY target, SNI, shortId, UUID, public key or Vision flow
  without compatible server configuration.
- Claiming that `fingerprint=chrome` is a real current Chrome browser.
- Automatic discovery of unrelated VPN nodes as LIC-Assist.

## Measurements required before "working bypass" claim

With one profile and one physical network, run 8-10 alternating A/B
attempts for `profile`, `firefox`, and `smart`. Record successful
Xray-authenticated HTTPS egress, cold-start time, failures and error
phases, after 20 min streaming with network changes, DNS/IPv6 leaks,
thermal load and battery. Repeat on Wi-Fi and mobile. Use no secrets or
target hostnames in logs.

Success means **same REALITY server and keys** passes authenticated
HTTPS repeatedly after Smart fallback when the original fingerprint
repeatedly fails. This only supports a network-specific improvement,
not a universal bypass.

## Sources

- Official REALITY: https://xtls.github.io/en/config/transports/reality.html
- Official TLS uTLS: https://xtls.github.io/en/config/transports/tls.html
- Official VLESS flow and Vision: https://xtls.github.io/en/config/outbounds/vless.html
- Browser fingerprint compatibility (issue, not controlled research):
  https://github.com/XTLS/Xray-core/issues/7259
- Concurrent ClientHello issue (reported, investigation ongoing):
  https://github.com/XTLS/Xray-core/issues/7306
- Amnezia-client Android report:
  https://github.com/amnezia-vpn/amnezia-client/issues/2704
