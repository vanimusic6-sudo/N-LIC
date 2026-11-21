#!/usr/bin/env python3
"""TV compatibility contract: no alternate VPN path, no permission widening."""
from pathlib import Path
import xml.etree.ElementTree as ET

BASE = Path("app/src/main")
A = "{http://schemas.android.com/apk/res/android}"
manifest = ET.parse(BASE / "AndroidManifest.xml").getroot()
app = manifest.find("application")
assert app is not None and app.get(A + "banner")
features = {
    item.get(A + "name"): item.get(A + "required")
    for item in manifest.findall("uses-feature")
}
assert features.get("android.software.leanback") == "false"
assert features.get("android.hardware.touchscreen") == "false"
main = next(item for item in app.findall("activity")
            if item.get(A + "name") == ".activities.MainActivity")
assert any(c.get(A + "name") == "android.intent.category.LEANBACK_LAUNCHER"
           for f in main.findall("intent-filter") for c in f.findall("category"))
assert main.get(A + "exported") == "true"
assert all(item.get(A + "exported") == "false" for item in app.findall("service")
           if item.get(A + "name", "").endswith("ByeDpiVpnService"))
phone = ET.parse(BASE / "res/layout/activity_nlic_control.xml").getroot()
tv = ET.parse(BASE / "res/layout-television/activity_nlic_control.xml").getroot()
def ids(root):
    return {v for el in root.iter() if (v := el.get(A + "id")) is not None}
assert ids(phone) == ids(tv), "The TV screen must retain all cockpit bindings"
assert "@+id/nlic_connect" in ids(tv)
kotlin = (BASE / "java/io/github/romanvht/byedpi/activities/SubscriptionLabActivity.kt").read_text()
assert "if (isTelevision()) setupTvNavigation()" in kotlin
assert "onBackPressedDispatcher.addCallback" in kotlin
assert "findViewById<View>(R.id.nlic_connect).requestFocus()" in kotlin
assert "VpnService.prepare(this)" in kotlin, "Never skip Android VPN user consent"
assert "nlic_quick_connect" in kotlin
assert "android:layout_width=\"720dp\"" in (BASE / "res/layout-television/activity_nlic_control.xml").read_text()
print("TV manifest, layout, D-pad focus and VPN-consent contracts verified.")
