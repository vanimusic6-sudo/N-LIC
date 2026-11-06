# N-LIC local privacy hardening — 2026-11-07

This branch is a device-test candidate, not a production security claim.

## Implemented without a cooperating server

- Xray subscription mode captures the full IPv6 default route. Unsupported IPv6 fails inside the VPN instead of escaping over the carrier.
- Xray mode does not inherit carrier DNS. Android DNS packets are sent into TUN -> HEV -> authenticated loopback SOCKS -> encrypted Xray outbound.
- sing-box keeps its existing DoH-through-VPN resolver and now uses a bounded 2048-entry in-memory cache with an 8 second query timeout.
- No EDNS Client Subnet is configured by N-LIC.
- Optional network-change handling now observes NET_CAPABILITY_NOT_VPN physical networks instead of Android's default network, avoiding false reconnects caused by N-LIC's own VPN.
- Smart REALITY remains a curated Chrome / Firefox / Safari set. No random fingerprint generator was added.
- HTTPS-only subscription import, system trust anchors, insecure-TLS rejection, authenticated private SOCKS and encrypted subscription storage remain in place.

## UI

Manual REALITY profile / strategy testing moved from the main connection screen to Settings -> DPI bypass. Normal connection controls stay on the main screen.

## Not implemented in this local-only pass

- ECH for arbitrary destinations: the remote TLS endpoint must support it.
- MASQUE / CONNECT-IP: requires a compatible remote gateway.
- Global cover traffic or padding: significant battery/data cost and uncertain benefit.
- Raw fake/overlap/checksum packet tricks as a core security feature: the current Android Xray path does not provide a reliable raw-packet control point.
- A claim of local DNSSEC validation: current engines are not treated as validating resolvers.

## Required device tests

1. IPv4 and IPv6 leak checks on VLESS/REALITY and WireGuard.
2. DNS leak checks with Android Private DNS both off and on.
3. Wi-Fi -> cellular -> Wi-Fi handover.
4. Cold start, reconnect, LIC-helper fallback, LIC-Auto and LIC-Bay.
5. Thermal / battery observation under sustained traffic.
