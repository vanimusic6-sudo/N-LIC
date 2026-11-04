#!/usr/bin/env python3
"""Fast V2 layout/accessibility contract: catches invisible Settings regressions.

This is a static safety gate, NOT a substitute for on-device UI tests.
"""
from pathlib import Path
import re
from xml.etree import ElementTree as ET

root = Path(__file__).resolve().parents[2]
layout = root / "app/src/main/res/layout/activity_nlic_control.xml"
activity_path = root / "app/src/main/java/io/github/romanvht/byedpi/activities/SubscriptionLabActivity.kt"
settings_path = root / "app/src/main/java/io/github/romanvht/byedpi/activities/NlicV2Settings.kt"
manifest_path = root / "app/src/main/AndroidManifest.xml"

android = "{http://schemas.android.com/apk/res/android}"
view = ET.parse(layout).getroot()
ids = {}
for element in view.iter():
    raw = element.get(android + "id", "")
    if raw.startswith("@+id/"):
        name = raw.split("/", 1)[1]
        assert name not in ids, f"duplicate ID {name}"
        ids[name] = element
required = {
    "nlic_settings_button", "nlic_connect", "nlic_mode_bypass",
    "nlic_mode_subscription", "nlic_subscription_panel",
    "nlic_bypass_panel", "nlic_auto_switch", "nlic_bay_switch",
    "nlic_adaptive_switch", "nlic_manage_profiles",
    "nlic_server_picker_button", "nlic_stage_label",
}
assert required <= ids.keys(), f"missing V2 control: {required - ids.keys()}"
assert ids["nlic_settings_button"].get(android + "contentDescription"), (
    "Settings button must have an accessible label"
)
assert ids["nlic_settings_button"].get(android + "clickable") == "true"
assert ids["nlic_settings_button"].get(android + "focusable") == "true"
assert ids["nlic_connect"].get(android + "visibility") != "gone"

activity = activity_path.read_text(encoding="utf-8")
settings = settings_path.read_text(encoding="utf-8")
refs = set(re.findall(r"R\.id\.([a-zA-Z_][a-zA-Z_0-9]*)", activity))
assert refs <= ids.keys(), f"activity references missing layout controls: {refs - ids.keys()}"
assert "R.id.nlic_settings_button" in activity
assert ").setOnClickListener { showCompactSettings() }" in activity
assert "setOnMenuItemClickListener" not in activity
assert "NlicV2Settings.show(" in activity
for caption in (
    "Ядро локального обхода", "Проверить стратегии",
    "Домены и исключения", "DNS, IPv6 и приложения",
    "Защита Android VPN", "Состояние соединения", "Очистить серверы"
):
    assert caption in settings, f"settings item inaccessible: {caption}"

manifest = ET.parse(manifest_path).getroot()
apps = manifest.find("application")
assert apps is not None and apps.get(android + "usesCleartextTraffic") == "false"
activities = {a.get(android + "name"): a for a in apps.findall("activity")}
for name in (".activities.SubscriptionLabActivity", ".activities.SettingsActivity"):
    assert activities[name].get(android + "exported") == "false", name
print("N-LIC V2 UI contract and exported-activity checks: OK")
