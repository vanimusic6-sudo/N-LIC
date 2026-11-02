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


## Repeated Play Protect block after rebranding — binary forensic follow-up (2026-11-03)

**User reproduction**: The post-hardening N-LIC Dev APK still triggers the same "Вредоносное приложение заблокировано" Google Play Protect warning. Screenshot shows the **new N-LIC icon and name**, so rebranding was present in the tested binary. Thus, changing the app label and graphic identity **did not resolve** classification. Neither did reducing storage permissions or disabling external activities. This is an unresolved harmful-app block, not merely a first-install "unknown app" prompt.

**Independent read-only provenance test**: https://github.com/vanimusic6-sudo/N-LIC/actions/runs/41501956136 (completed successfully, report artifact `N-LIC-Binary-Provenance-Report`). Compared three exact APK files using `aapt`, `apksigner` and ZIP integrity checks, with machine-readable hashes:

| Image | SHA-256 | Package ID | Native .so entries |
| --- | --- | --- | --- |
| Official upstream `v.1.8.2` universal release | `3df9570313c9fb03de19fde7dbf7e56b943fd0845b2d57b687c5859aff4e0001` | `io.github.romanvht.byedpi` | 8 |
| N-LIC hardened DEV (reproduces warning) | `f66196d40515c326a45dc10270991fb393d2e61c84a89db8f596036657df1bfa` | `io.github.vanimusic6sudo.nlic.dev` | 8 |
| N-LIC old optimized release test (reproduces warning) | `3a429002267a26935386264a92ce02504f2f3835b29b82f1bc36f8f9c1a16b82` | `io.github.vanimusic6sudo.nlic.dev` | 8 |

**Important technical findings:**

- Package ID and signing certificate both differ from the upstream official release.
- N-LIC builds use the same Android target SDK 34 and minimum SDK 21 as official upstream.
- The hardened DEV build has removed broad storage permissions, unlike the official upstream. Google still blocks hardened N-LIC.
- All **eight** bundled native library names and architectures match between official and fork. **None of the eight N-LIC native library hashes matches the official built binaries.** This can be explained by different NDK/compiler/build environment, native build flags or other source/build differences; it is **not evidence of malware or an established reason for the Play Protect block**. A reproducible native build diff and memory-safety analysis are still needed.
- Signing cert fingerprints (SHA-256): official `77451075acea406406475d74d459883a49a64051faf32e42f718f3f9777a8dfb`; N-LIC Dev `9eac8ece5fb89eeacb03c5eff20155724a206d73180d58c78fc7d3911dbee5c5`.
- The official release contains one minified DEX; N-LIC optimized test contains one minified DEX; hardened N-LIC Debug contains 10 unminified DEX. This is consistent with the chosen build variants, but not itself a benign-code validation.
- The CodeQL GitHub Actions jobs for N-LIC passed on commit `5de14a2a09...` (workflow runs 41499460530 / 41499469417), but passing a job does not guarantee zero alerts or a clean dynamic/native audit.
- **No definitive malware classification subtype, trigger or benign/malicious verdict has been obtained from Google.** Do not claim "Riskware" as a confirmed subtype.

**Official Google guidance:** https://developers.google.com/android/play-protect/warning-dev-guidance  
**Official developer appeal:** https://support.google.com/googleplay/android-developer/contact/protectappeals

The appeal form requires a SHA-256 for the exact APK **uploaded to VirusTotal**. A hash alone is not enough: the developer must upload the exact public test APK to VirusTotal before filing (be aware VirusTotal files may be shared with security vendors), verify SHA and include screenshot, GitHub source, controlled sample comparison, and reproducible behavior. Submit only after reviewing unresolved code/security issues; this is not a way to suppress protection on a phone.

**Do not** keep changing package IDs, icons or signing keys to find an identity that bypasses Play Protect. Avoid overriding the installer warning. Continue security validation and request a classification review supported by facts.
