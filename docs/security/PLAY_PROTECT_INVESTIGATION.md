# N-LIC — Play Protect block: preliminary security review (2026-11-03)

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
