# LIC-Assist REALITY — actual TCP/TLS client-side transport techniques

Date: 2026-11-05
Status: experimental feature branch, requires field validation. Automatic tests
and APK compilation do NOT prove censorship bypass or underlying packet layout.

## Why this is more than a uTLS fingerprint selector

With LIC-Assist enabled for a single VLESS + TCP/RAW + REALITY node,
N-LIC compares **real authenticated HTTPS** through Xray, then can launch
the actual Android VPN with one of these 8 TCP packetization/connection
profiles (not just Chrome/Firefox/Safari):

| Strategy | Xray config | Effect and limits |
| --- | --- | --- |
| stock | no change | User's imported transport and uTLS ClientHello |
| split-soft | streamSettings.finalmask.tcp.fragment | Bounded TLS ClientHello record splitting, ~180–260 B fragments, 2–5 ms delays |
| split-early | streamSettings.finalmask.tcp.fragment | Earlier, smaller ~64–128 B initial TLS record segment, then ~160–220 B, 4–8 ms delays |
| split-zero | streamSettings.finalmask.tcp.fragment | TLS record splitting without extra 0 ms delay, not guaranteed separate *TCP* packets |
| mss1200 | streamSettings.sockopt.tcpMaxSeg | TCP_MAXSEG 1200; negotiates an altered first-flight segment size, subject to kernel and path |
| mss900 | streamSettings.sockopt.tcpMaxSeg | TCP_MAXSEG 900; stronger segmentation effect, may increase overhead |
| tfo-off | streamSettings.sockopt.tcpFastOpen | Disable TCP Fast Open on this physical socket where OS supports the sockopt |
| idle-keepalive | streamSettings.sockopt.tcpKeepAliveIdle/Interval | 30 sec / 12 sec keepalive. Primarily idle-connection stability, NOT a proven DPI evasion technique |

### When does it intervene?

- Only for a manually selected VLESS + TCP RAW + REALITY server.
- LIC-Assist main screen switch is ON; this feature is opt-in.
- Cold start on a new node/network: compare imported and curated uTLS,
  then compare stock transport and compatible TCP/TLS transport techniques.
- Only authenticated HTTPS through real Xray outbound qualifies as a
  working candidate. *Local SOCKS open or raw TCP connect never suffices*.
- Prefer imported transport if it works; an alternate must beat it by
  >=90 ms **and** >=15% to be chosen for latency reasons. If the
  imported transport fails, an authenticated alternate may be chosen.
- The winning combination is started **again**, passes real HTTPS
  and must establish TUN/HEV before its safe identifiers are cached.
- Subsequent connections on the same coarse network try that combination
  first. Failed variants are shut down before trying the next one.
- The existing 12h salted-node-id cache now remembers the safe
  transport-strategy ID as well as the browser uTLS ID; it never stores
  SNI, server addresses, UUIDs or credentials in the preference cache.
- No random fragmentation, injection of fake traffic, server-side
  change, protocol conversion, TLS verification bypass, or direct fallback.
- If LIC-Bay is enabled, these socket/FinalMask modes are omitted: the
  physical remote TCP connection belongs to the separate ByeDPI SOCKS
  relay, and changing Xray socket options would misrepresent their effect.
- LIC-Auto (server selector) is a separate mode and never launches this
  same-node packetization calibration.

### What is and isn't guaranteed

- Xray Core v26.10.31 supports `streamSettings.finalmask.tcp.fragment`
  and `streamSettings.sockopt.tcpMaxSeg`, `tcpFastOpen`,
  `tcpKeepAliveIdle` and `tcpKeepAliveInterval`.
- Fragmenting a TLS record does **not** guarantee physically separate
  TCP segments; Linux/Android kernel or NIC may coalesce writes.
- Smaller TCP MSS can increase load and harm performance; it may also
  change how middleboxes classify a flow.
- TCP keepalive is for idle stability and may slightly increase
  battery/network traffic. It is not applied unless selected.
- We have NOT shown that any of these bypasses the user's TSPU:
  earlier tests showed stock REALITY working 18/18 and 512KiB streaming
  working for three browser fingerprints. There was no blocked baseline
  against which to measure recovery.
- The first Smart connection can run several sequential profiles and
  therefore take longer than a normal VPN connection. Later connections
  use the verified cached variant. No background traffic is added.

### Suggested field test

1. Leave one server selected. Keep LIC-Auto and LIC-Bay OFF.
2. Turn on LIC-Assist and connect. Main status shows browser and
   real TCP technique selected. Confirm browser/app access through VPN.
3. Disconnect/reconnect. Check that calibration does not repeat.
4. Tap settings → Repeat REALITY selection to start a new, single
   controlled calibration the next time.
5. On a network where ordinary REALITY actually fails, compare original
   OFF vs Smart ON on the **same** server and network, with repeated
   attempts and error phases. Never claim censorship-specific success
   without repeatability and controls.
6. Use authorized router captures and offline
   `tools/reality_clienthello_audit.py` to see whether the chosen
   FinalMask/TCP MSS strategy changes the observed first flight.
   Do not share raw captures or secret credentials.

### Sources

- https://xtls.github.io/en/config/transports/finalmask.html
- https://xtls.github.io/en/config/transports/sockopt.html
- https://xtls.github.io/en/config/transports/reality.html
- https://github.com/XTLS/Xray-core/releases/tag/v26.10.31
