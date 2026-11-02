# N-LIC — Play Protect block: preliminary security review (2026-11-03)

## Controlled two-variant build — completed

GitHub Actions comparison run: https://github.com/vanimusic6-sudo/N-LIC/actions/runs/41492502618 — **success**, artifacts `N-LIC-Play-Protect-Audit-Only`.

The workflow built two APKs from the *same* commit with the *same* package ID, version code and N-LIC development signing certificate. The variants differ in debuggability, release minification/shrinking and compiled build mode.

| Metric | Debug | Release-optimized test |
| --- | --- | --- |
| Artifact | `N-LIC-debug.apk` | `N-LIC-release-optimized-test.apk` |
| SHA-256 | `04de8103aa0373ac9d2b21a4f9365b2aabaf89d596c7c761d97abb75840b64c9` | `3a429002267a26935386264a92ce02504f2f3835b29b82f1bc36f8f9c1a16b82` |
| Package ID | `io.github.vanimusic6sudo.nlic.dev` | `io.github.vanimusic6sudo.nlic.dev` |
| Version code | `1820` | `1820` |
| Certificate SHA-256 | `9eac8ece5fb89eeacb03c5eff20155724a206d73180d58c78fc7d3911dbee5c5` | Same |
| `application-debuggable` | Present | Absent |
| APK verification | Passed | Passed |

This **isolates build type** better than simply comparing the N-LIC debug build against the original upstream release (which also differs by signing identity and package name). No installation, Play Protect result, device behavior or dynamic malware scan has yet been observed for the release-optimized test. **A successful build and valid APK signature are not evidence of benign software.** Do not circumvent installation warnings.


## Upstream version comparison — critical evidence (added 2026-11-03)

- N-LIC was forked from upstream `romanvht/ByeByeDPI` **master** at commit `7b7eecf6b2b1e5174e1c857489e207bf0e919863`. N-LIC source is not a different experimental branch of the upstream project.
- The latest official upstream APK release is **v.1.8.2**, published 2026-10-02, tag commit `96a3c1ff555043d915af814aa4f0e9a572c89aba`.
- Comparing upstream `v.1.8.2...master`: only **3 commits**, modifying **one file**: `app/src/main/assets/proxytest_strategies.list`. There are **no Kotlin, C, manifest or Gradle changes** between those two upstream versions.
- The **actual source AndroidManifest.xml Git blob SHA** is `5d1a10f3b1938e37544c5e562a889be8e5feb66f` in both the official `v.1.8.2` tag and the N-LIC fork. Therefore the broad permissions and exported activities are inherited from the upstream release, **not newly introduced by N-LIC**.
- N-LIC adds independent `applicationId`, `.dev` application ID suffix, our new dev signing certificate, and **builds `:app:assembleDebug`**. The official public release is a **release build** with a different cert and application ID.
- This narrows possible explanation of differing Play Protect verdicts to **APK build mode/content, new signing identity, package identity/reputation or device-specific Play Protect assessment**, rather than a different branch or newly added Android permission. **None is proven as the trigger.**
- Reproducing the verdict with a same-tag release-mode comparison build under controlled conditions is a high-priority next step; do not use this as a pretext to suppress or evade malware detection.

Source references:
- https://github.com/romanvht/ByeByeDPI/releases/tag/v.1.8.2
- https://github.com/romanvht/ByeByeDPI/compare/v.1.8.2...master
- https://github.com/vanimusic6-sudo/N-LIC/compare/romanvht:master...master


Status: **OPEN / investigation**, not a clearance to install. The first N-LIC Dev APK was blocked by Google Play Protect with a harmful-app warning. **Do not bypass, suppress or disable security scanning** to test this build on a primary device.

## Build examined

- GitHub Actions: https://github.com/vanimusic6-sudo/N-LIC/actions/runs/41487349263
- Artifact: `N-LIC-Dev-APK` (13161544276), extracted APK `app-universal-debug.apk`
- APK SHA-256: `04de8103aa0373ac9d2b21a4f9365b2aabaf89d596c7c761d97abb75840b64c9`
- Android application ID in the compiled manifest: `io.github.vanimusic6sudo.nlic.dev`
- Verified certificate subject: `CN=N-LIC Development, OU=Development, O=N-LIC`
- Signing cert SHA-256: `9eac8ece5fb89eeacb03c5eff20155724a206d73180d58c78fc7d3911dbee5c5`
- Native libraries included: `libbyedpi.so` and `libhev-socks5-tunnel.so` for ARM/x86 ABIs.
- UI name and icon remain the upstream **ByeByeDPI** identity.

## Observations confirmed from source

1. **Powerful declared privileges:** `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `RECEIVE_BOOT_COMPLETED`, VPN/foreground services, and legacy storage. Some have genuine purposes, but scope should be minimized.
2. **Externally callable activity:** `ToggleActivity` is `android:exported="true"` without a caller permission, accepts a `strategy` intent parameter, and can alter stored command arguments, start or stop the network service. This is a genuine third-party control surface; document external automation needs before restricting it.
3. **SettingsActivity also exported:** there is no known requirement for arbitrary apps to start it; investigate and restrict.
4. **Potential backup exposure:** `android:allowBackup="true"` with essentially default/placeholder `backup_rules.xml` and `data_extraction_rules.xml`. Review preferences, history, stored lists and users' privacy.
5. **JNI/native code:** native execution of the upstream ByeDPI engine and SOCKS tunnel is central to the product. The pinned source submodules are `hufrea/byedpi@ba532298...` and `heiher/hev-socks5-tunnel@4d6c334d...`; these require a proper native and supply-chain review.
6. **Debug build:** built via `:app:assembleDebug`, with a new self-managed signing identity. The presence of a new key alone does NOT explain a specific harmful-app classification.
7. **Limited static APK review:** APK ZIP was inspected, signature extracted, DEX and shared libraries enumerated, and readable HTTP(S) URL strings checked. No obvious unknown HTTP(S) endpoint appeared in the quick scan. This is **not** a complete static/dynamic malware audit.

## Historical context (not proof)

Upstream developer's release v.1.3.8 (2024-11-07) states that a prior Google Play Protect false positive was fixed: https://github.com/romanvht/ByeByeDPI/releases/tag/v.1.3.8. The present detection could share a cause or could be unrelated; it must be investigated independently.

Google's guidance distinguishes **harmful-app blocks** from merely unknown-app scan notices:
https://developers.google.com/android/play-protect/warning-dev-guidance

## Hypotheses, **not proven causes**

- Security classifier categorizes packet-manipulating native VPN tools as riskware.
- Broad app/file visibility and boot/battery privileges increase the app's apparent risk.
- New debug-signed fork still uses the original app label/icon, potentially complicating attribution.
- Actual native code, dependencies or packaging could have a separate security problem.

No hypothesis alone explains the verdict without additional evidence.

## Action plan

1. Keep the original APK and hash unchanged for a control comparison.
2. Compare the same-device Play Protect result for the upstream official APK and the initial N-LIC development APK **without overriding warnings**.
3. Review native code/submodule provenance and dynamic network behavior in a controlled environment.
4. Restrict unnecessary exported components and assess any desired third-party automation functionality.
5. Replace broad file access with Storage Access Framework for routine file import/export. Audit `QUERY_ALL_PACKAGES` requirements.
6. Disable or explicitly constrain cloud/device backup for sensitive settings.
7. Provide accurate N-LIC branding, provenance and upstream credits—not superficial changes intended to evade detection.
8. Run independent security review (malware scanners + network behavior), review false positive evidence, and use official Play Protect developer appeal if warranted.
9. Until verified, do not recommend normal installation on a primary device.

**Conclusion:** The APK is signed with N-LIC's development certificate and incorporates the expected engines. That establishes provenance, not safety. The exact reason for Play Protect's classifier remains unverified.

## Device verification: release-optimized build ALSO blocked (2026-11-03)

User confirmed the official upstream ByeByeDPI **v.1.8.2**, downloaded from the author's GitHub, is **not** flagged by Google Play Protect on their phone; however, both N-LIC **Debug** and N-LIC **ReleaseTest** APKs provoke the harmful-app block.

**Updated interpretation:**
- `android:debuggable` and lack of release shrinking cannot explain the outcome *alone*. Mark prior debug-only theory as **not supported**.
- The two N-LIC APKs were compiled from the same source under the same app ID `io.github.vanimusic6sudo.nlic.dev` and signing certificate fingerprint `9eac8e...`. Distinguishing them by build type did not change the Google verdict.
- Most important unisolated variables compared with upstream are **different application package ID, different signer and signer reputation, upstream vs N-LIC compiled artifact, and distribution context**. These are hypotheses, **not proof** of Play Protect's internal rationale.
- Original upstream 1.8.2 universal release is about 3,332,888 bytes, N-LIC release-optimized test 3,333,400 bytes (difference 512 bytes). Near-equal APK size **does not prove** binary equivalence.
- Local ZIP file comparison of the two N-LIC variants: all 4 `libhev-socks5-tunnel.so` files have identical bytes; `libbyedpi.so` differs between debug and release variants as expected from compile modes. All 9 common assets files are identical.
- Google's published Riskware warning text closely resembles the user's Russian generic text, but **category must be treated as tentative** without verified exact category metadata.
- Until deeper independent code and behavior review, recommend **not overriding Play Protect** and do not treat the official original's clean classification as definitive evidence for the fork.

**Next actions:**
1. Capture the exact Play Protect details/category and check whether the user can view a detection reason.
2. Seek an official review of the Play Protect classification from the developer portal if security review supports a false positive: https://developers.google.com/android/play-protect/warning-dev-guidance
3. Continue native library, exported-component and dependency security audit on a controlled device. Verify no unexpected traffic or private data leakage.
4. Avoid signing-key rotation, APK renaming, or artificial obfuscation as a means of hiding from the scanner; prioritize genuine hardening and reproducible evidence.

