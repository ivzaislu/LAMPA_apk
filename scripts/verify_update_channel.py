#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

checks = {
    "app/build.gradle": [
        "commandLine 'git', 'rev-list', '--count', 'origin/main'",
        "commandLine 'git', 'describe', '--tags', '--abbrev=0', 'origin/main'",
        'UPDATE_REPO_ID',
        'ivzaislu/LAMPA_apk',
    ],
    "app/src/main/java/top/rootu/lampa/helpers/Updater.kt": [
        "BuildConfig.UPDATE_REPO_ID",
        "rel.draft || rel.prerelease",
        'it.name.endsWith(".apk", ignoreCase = true)',
    ],
}

errors = []
for rel, required in checks.items():
    path = ROOT / rel
    if not path.exists():
        errors.append(f"missing file: {rel}")
        continue
    text = path.read_text(encoding="utf-8")
    for needle in required:
        if needle not in text:
            errors.append(f"{rel}: missing update/version invariant: {needle}")

updater = (ROOT / "app/src/main/java/top/rootu/lampa/helpers/Updater.kt").read_text(encoding="utf-8")
if "lampa-app/LAMPA/releases" in updater:
    errors.append("Updater.kt: upstream release URL is hard-coded again")
if "for (asset in rel.assets)" in updater:
    errors.append("Updater.kt: updater must not select the last arbitrary release asset")

if errors:
    print("Custom update-channel invariant FAILED:")
    for error in errors:
        print(f" - {error}")
    sys.exit(1)

print("Custom update-channel invariant OK: version follows main and updates use ivzaislu/LAMPA_apk.")
