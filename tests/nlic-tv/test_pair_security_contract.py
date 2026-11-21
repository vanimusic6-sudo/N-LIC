#!/usr/bin/env python3
"""Static transfer-security contract; JVM unit tests cover actual AES-GCM."""
from pathlib import Path
import xml.etree.ElementTree as ET

base = Path("app/src/main")
android = "{http://schemas.android.com/apk/res/android}"
m = ET.parse(base / "AndroidManifest.xml").getroot()
application = m.find("application")
assert application is not None
assert application.get(android + "allowBackup") == "false"
assert application.get(android + "usesCleartextTraffic") == "false"
permissions = {x.get(android + "name") for x in m.findall("uses-permission")}
assert "android.permission.INTERNET" in permissions
assert "android.permission.CAMERA" not in permissions, "Camera scanning uses stock phone camera"
assert "android.permission.QUERY_ALL_PACKAGES" not in permissions
sender = next(x for x in application.findall("activity")
              if x.get(android + "name") == ".activities.TvSendActivity")
assert sender.get(android + "exported") == "false", "Sender may only be opened via app"
main = next(x for x in application.findall("activity")
            if x.get(android + "name") == ".activities.MainActivity")
assert any(x.get(android + "scheme") == "nlic" and x.get(android + "host") == "pair"
           for f in main.findall("intent-filter") for x in f.findall("data"))
source = (base / "java/io/github/romanvht/byedpi/subscription/TvPairTransfer.kt").read_text()
for required in (
    'AES/GCM/NoPadding', 'SecureRandom()', 'privateIpv4',
    'TTL_MS = 120_000L', 'MAX_BYTES = 2 * 1024 * 1024',
    'MessageDigest.isEqual', 'SubscriptionImport.parseNode',
    'SubscriptionVault.write', 'server.close()', 'secret.fill(0)',
    'ServerSocket(0, 3, address)', 'context.applicationContext',
):
    assert required in source, required
for forbidden in ('Log.d(', 'Log.e(', 'android.permission.MANAGE_EXTERNAL_STORAGE'):
    assert forbidden not in source, forbidden
phone = (base / "java/io/github/romanvht/byedpi/activities/TvSendActivity.kt").read_text()
assert 'FLAG_SECURE' in phone
assert 'selectedTarget()' in phone
assert 'TvPairTransfer.send' in phone
assert 'SubscriptionFetcher.fetch(text)' in phone
assert 'setOnClickListener { chooseSubscription() }' in phone
assert 'getStringExtra' not in phone
build = Path("app/build.gradle.kts").read_text()
assert 'NLIC_TV_MULTI_ABI' in build
assert 'setOf("armeabi-v7a", "arm64-v8a")' in build
assert 'com.google.zxing:core:3.5.3' in build
assert 'versionCode = 2104' in build
phone_ids = {x.get(android + "id") for x in
             ET.parse(base / "res/layout/activity_nlic_control.xml").getroot().iter()}
tv_ids = {x.get(android + "id") for x in
          ET.parse(base / "res/layout-television/activity_nlic_control.xml").getroot().iter()}
assert phone_ids == tv_ids
for identifier in ("nlic_tv_receive", "nlic_phone_send_tv"):
    assert "@+id/" + identifier in phone_ids
print("QR deep-link, least-privilege sender, encrypted LAN and dual-ABI contracts passed")
