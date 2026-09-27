#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

checks = {
    "app/src/main/java/top/rootu/lampa/sched/Scheduler.kt": [
        "fun scheduleUpdate(sched: Boolean)",
        "fun updateContent(sync: Boolean)",
        "Intentionally disabled",
    ],
    "app/src/main/java/top/rootu/lampa/AndroidJS.kt": [
        "TvChannelsPolicy.requestedEnabled",
        "TvChannelsPolicy.enabled",
    ],
    "app/src/main/java/top/rootu/lampa/MainActivity.kt": [
        "TvChannelsPolicy.clearPublishedContent()",
        "if (!TvChannelsPolicy.enabled) return@withContext",
        'action = if (isAndroidTV) "toggleTvChannels" else "updateOrClose"',
    ],
    "app/src/main/java/top/rootu/lampa/channels/LampaChannels.kt": [
        "if (!TvChannelsPolicy.enabled) return",
    ],
    "app/src/main/java/top/rootu/lampa/channels/ChannelManager.kt": [
        "if (!TvChannelsPolicy.enabled) return",
        "LAMPA_CHANNEL_NAMES",
    ],
    "app/src/main/java/top/rootu/lampa/channels/WatchNext.kt": [
        "if (!TvChannelsPolicy.enabled) return",
        "fun clearAll(): Int",
    ],
    "app/src/main/java/top/rootu/lampa/receivers/HomeWatch.kt": [
        "!TvChannelsPolicy.requestedEnabled",
    ],
    "app/src/main/java/top/rootu/lampa/recs/RecsService.kt": [
        "!TvChannelsPolicy.requestedEnabled",
    ],
    "app/src/main/java/top/rootu/lampa/helpers/Prefs.kt": [
        'ANDROID_TV_CHANNELS_ENABLED_KEY = "android_tv_channels_enabled"',
        "get() = appPrefs.getBoolean(ANDROID_TV_CHANNELS_ENABLED_KEY, false)",
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
            errors.append(f"{rel}: missing guard/token: {needle}")

scheduler = (ROOT / "app/src/main/java/top/rootu/lampa/sched/Scheduler.kt").read_text(encoding="utf-8")
for fn in ("scheduleUpdate", "updateContent"):
    start = scheduler.find(f"fun {fn}(")
    if start < 0:
        errors.append(f"Scheduler.kt: missing {fn}")
        continue
    body_start = scheduler.find("{", start)
    body_end = scheduler.find("}", body_start)
    body = scheduler[body_start + 1:body_end]
    if "return" not in body:
        errors.append(f"Scheduler.kt: {fn} is no longer a no-op")

if errors:
    print("Android TV OFF invariant FAILED:")
    for error in errors:
        print(f" - {error}")
    sys.exit(1)

print("Android TV OFF invariant OK: all known entry-point guards are present.")
