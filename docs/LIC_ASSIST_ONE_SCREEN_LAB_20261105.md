# N-LIC / LIC-Assist · one clear experiment and one verified choice

**Status:** experimental Android release build. It does not prove a specific
TSPU/DPI mechanism or promise censorship circumvention.

## Why we changed it

User feedback: Smart chose the imported profile despite slightly faster
Firefox/Chrome; users couldn't tell what worked and couldn't test every
technique themselves. The UI exposed duplicate uTLS/TCP/Reset menus; the
normal VPN startup ran a hidden, lengthy calibration for every new network.

## New workflow

On the VPN home screen:

1. Switch **LIC-Assist · REALITY** ON for a manually chosen
   VLESS + TCP (RAW) + REALITY server. No need to open extra settings.
2. Tap **«Проверить все способы»** while VPN is disconnected, with LIC-Auto
   and LIC-Bay OFF. This is the *only* visible comparison control.
3. The isolated, unexported test service checks all distinct supported
   browser ClientHello variants (imported / Chrome / Firefox / Safari),
   then tests all seven TCP techniques using the fastest HTTPS+stream-
   verified browser. No server, key, SNI, shortId, UUID or Vision flow changes.
   This is two-stage exploration, NOT every possible fingerprint×TCP
   combination (which would create too much traffic).
4. **Each variant must complete two certificate-checked HTTPS requests
   and deliver exactly 512 KiB over encrypted Xray/SOCKS.**
   The report marks working, partially working, unsupported and failed
   variants separately. A bad technique cannot be selected for VPN.
5. The list suggests the lowest median HTTPS time *among fully verified
   variants*, without a hidden 90ms/15% bonus for baseline. At equal
   timing, prefer plain TCP.
6. If the main switch is ON, the fastest fully verified method is saved
   after the explicit test. The user can also select a different fully
   verified method in the same results dialog, using **«Использовать»**.
   If the switch was OFF, no method is applied until the user presses
   **«Использовать»**.
7. The result summary stays on the home screen and can be opened again.

## Normal VPN connection

- The user's saved, lab-tested method is tried **first**, once, without
  a hidden full scan.
- If it cannot establish HTTPS, the native engine tries the same browser
  on normal TCP and then other compatible browser profiles on the **same**
  server; no unverified TCP fragment/MSS strategy is launched silently.
- A recovered connection does not silently re-label an untested variant
  as the new "best". An old failing preference is invalidated; run the lab
  again to choose another verified one.
- The selected combination is read via a private Android AtomicFile from
  both the UI and the native VPN process. No cross-process
  SharedPreferences race and no secret endpoint data in the stored file.
- A user-selected choice does not silently expire after 12 hours and
  revert to the imported profile. It stays until retested or invalidated.
- The existing independent LIC-Auto and LIC-Bay switches are left intact.
  The full test does not claim to work when LIC-Bay owns the physical TCP
  connection.
- No profile changes on a running VPN; switching selection happens before
  the next connection.

## Understanding measurements

Successful HTTPS, even with a 512-KiB body, is not proof that DPI blocks
the other techniques: the server, DNS, ISP routing, upstream test site and
REALITY compatibility are independent possible causes of failure.

A 10ms or 20ms difference in two samples can be statistical noise. The
winner is the **observed** fastest among fully verified variants; if the
difference is under 50ms, the report explicitly warns about uncertainty.
The user can manually select a different known-working variant.

The test is opt-in; on an already working connection it creates no
background traffic, packet injection or added encryption risks.

## External documentation

- Xray FinalMask fragment (TLS record splitting):
  https://xtls.github.io/en/config/transports/finalmask.html
- Xray socket options (TCP MSS, TFO, keepalive):
  https://xtls.github.io/en/config/transports/sockopt.html
