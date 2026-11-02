# N-LIC — Android security review, 2026-11-03

**Status: initial source audit and hardening; dynamic malware assessment and full native-code audit are NOT complete.**
This report does not override or disprove the Google Play Protect harmful-app block.

## Scope and methodology

Sources examined directly in the fork (upstream based on ByeByeDPI v1.8.2): AndroidManifest.xml, Gradle signing/build config, GitHub Actions, permissions/file picker, settings import/export, backup XML, notification/service and launch components, IPC/broadcast handlers, native JNI glue, pinned Git submodule metadata and representative networking implementation.

Build checks: Gradle APK build, Android Lint and unit tests; a separate [CodeQL workflow](../../.github/workflows/codeql-security-audit.yml) was introduced for Java/Kotlin and C/C++. **Results must be checked in GitHub Actions; an added workflow does not establish a clean scan.**

Excluded from this phase: line-by-line audit of every upstream ByeDPI and hev-socks5-tunnel C file, reverse engineering of Play Protect's classifier, rooted/physical-device dynamic analysis, runtime network capture, independent malware scanners and reproducible install tests on different Android OEM versions.

## Confirmed findings and code changes

| Finding | Risk/impact | Remediation in identity/security branch | Validation status |
|---|---|---|---|
| Exported `ToggleActivity` accepted untrusted external strategy/action extras and could change network behavior | **HIGH** unauthorized cross-app control | `android:exported=false`; launcher shortcuts now target public `MainActivity`, require visible user confirmation, and only accept matching pinned saved strategies | Source fixed; device shortcut regression test required |
| Exported `SettingsActivity` without a required permission | **MEDIUM** unwanted external app navigation | Private activity; same-app navigation remains explicit | Source fixed |
| Main screen used dynamically registered `RECEIVER_EXPORTED` for internal status events | **MEDIUM** external intent/status spoofing | Use `ContextCompat.RECEIVER_NOT_EXPORTED` | Source fixed; device test required |
| `MANAGE_EXTERNAL_STORAGE` and legacy external-storage permissions | **HIGH privacy footprint** for routine file import/export | Removed permissions and legacy file browser; `ACTION_OPEN_DOCUMENT`/`ACTION_CREATE_DOCUMENT` access only a user-selected URI | Code changed; user import/export smoke test required |
| Unrestricted `allowBackup=true`, placeholder D2D/cloud extraction XML | **MEDIUM** accidental copying of settings, domain lists and history | `allowBackup=false` and explicit cloud/device-transfer exclusion for file, root, prefs and database domains | Source fixed; OEM-specific verification required |
| Unbounded `readText()` on settings JSON from user-selected files | **LOW/MEDIUM availability** oversized import/memory exhaustion | Bound import to 2 MiB before parsing | Source fixed; large-file test required |
| Inherited original app name, icon, source URL and readme signing hash | **MEDIUM provenance/confusion** independent fork displayed as upstream | New N-LIC icon (legacy/adaptive/monochrome/notification/Android TV), locale strings, repo link and credits | Resource changes require build/visual checks |
| Original release and dev signing identities must be distinct | **MEDIUM supply-chain/update identity** | N-LIC DEV already has its own stable cert. Added a separate manual/protected production certificate workflow and Codespaces creation script; no existing key was rotated | Production key **not created by assistant**. Operator must back it up and configure environment |
| Signed APK blocked by Play Protect in both debug and optimized release-test modes | **OPEN SECURITY INCIDENT** | Preserve evidence, compare provenance, audit behavior; no "disable Play Protect" instructions or repackaging tricks | **Not resolved** |

## Other review observations

- `QUERY_ALL_PACKAGES` remains present for the existing app-selective routing UI, whose implementation enumerates installed packages. This is broad permission/data access. Consider a constrained query alternative before store submission; do not remove it blindly while the UI depends on it.
- VPN permission (`BIND_VPN_SERVICE`), notification permission, boot receiver and battery-exemption prompt have apparent functional uses. Whether each is appropriate on every Android version is not proven.
- Runtime networking: native ByeDPI proxy and `hev-socks5-tunnel` deliberately inspect/forward user traffic. No evidence was established from this limited source review of an independent telemetry/exfiltration endpoint. **Absence of discovered evidence is not proof that no such endpoint or unsafe behavior exists.**
- `MainActivity` has an explicit user-triggered log export. Saved logs and diagnostic reports can contain network metadata, strategy arguments or domain names; treat as potentially sensitive.
- Git submodules pin upstream C code by commit; this improves reproducibility but does **not** prove safety. The C parser, socket handlers, threading, JNI, and embedded tun2socks/lwIP dependencies still require fuzzing and memory-safety review.
- Build jobs that can access signing environment secrets execute Gradle and third-party GitHub actions. A malicious workflow/build/dependency commit could exfiltrate those secrets. Keep signing jobs on protected master with manual approval and use an offline production key for stronger isolation.
- Enforce the original GPL-3.0 license and clearly credit romanvht, hufrea, heiher, and dovecoteescapee. This is a separate fork, not an "official" upstream update.

## Play Protect evidence

Official ByeByeDPI v1.8.2 obtained from romanvht's GitHub is not blocked on the reported device. Early N-LIC Debug and ReleaseTest (both signed by N-LIC DEV certificate and using the same N-LIC package ID) **were both blocked**.

The original upstream manifest is byte-identical to the pre-hardening fork's manifest. Thus, broad inherited permissions alone and Debug-only build mode do not fully explain the differing verdicts. Package/signing reputation, packaging, and security classifier heuristics remain **hypotheses**; never claim an unverified "false positive".

Initial reference: [Play Protect investigation](../../../../tree/audit/play-protect-20261103/docs/security/PLAY_PROTECT_INVESTIGATION.md).
If independent review establishes a benign build, use Google's [official review/appeal process](https://developers.google.com/android/play-protect/warning-dev-guidance).

## Test matrix / release gate

- [ ] GitHub Actions builds the full feature branch with static permission baseline, lint and unit tests.
- [ ] CodeQL completes Java/Kotlin and C/C++ analysis (or failures/coverage gaps documented).
- [ ] The N-LIC icon and text render correctly on Android 8–17 and Android TV.
- [ ] Dev APK installs and runs only on a controlled device **without overriding Play Protect**.
- [ ] Internal launcher shortcuts and pinned strategy shortcuts require confirmation and work after consent.
- [ ] Settings import/export, log save and large-file rejection function on Android 10–17.
- [ ] VPN/Proxy mode, Android notification, tile, device boot and app-selective routing are regression tested.
- [ ] Native memory safety review (ASan/UBSan, fuzz malformed requests, inspect pinned C dependencies) completed.
- [ ] No accidental IP/DNS leak in intended VPN modes and no unexpected connections in controlled capture.
- [ ] Release signing key is generated by the owner, stored offline and protected via GitHub environment.
- [ ] The Play Protect classification is independently investigated/resolved before general release.

**Do not declare the app safe or distribute it as a public production release until the open issues are resolved.**
