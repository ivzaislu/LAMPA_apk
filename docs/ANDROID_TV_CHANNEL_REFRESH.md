# Android TV Home: кастомная политика каналов

Этот документ фиксирует Android TV-часть кастомизации `ivzaislu/LAMPA_apk`.

Полная архитектура сборки, синхронизации и релизов описана в [CUSTOM_BUILD.md](CUSTOM_BUILD.md).

## Почему каналы не удалены из проекта

Анализ старого рабочего APK показал, что все 9 Android TV каналов и их код оставались внутри приложения. Ключевое отличие было в том, что публичные `Scheduler.scheduleUpdate()` и `Scheduler.updateContent()` ничего не делали.

Поэтому текущая кастомизация не вырезает `ChannelManager`, `LampaChannels`, `WatchNext` и provider-код. Это уменьшает конфликтность при переносе новых upstream-версий.

## Master switch

Центральная политика находится в:

`app/src/main/java/top/rootu/lampa/channels/TvChannelsPolicy.kt`

Настройка хранится через `Prefs.androidTvChannelsEnabled` под ключом:

`android_tv_channels_enabled`

Default: **OFF**.

На Android TV пункт меню позволяет включить или выключить интеграцию.

## OFF

OFF означает, что Lampa не должна работать с Android TV Home:

- встроенные Preview Channels не создаются и не обновляются;
- plugin Preview Channels (`plugin:*`) не публикуются;
- Watch Next не создаётся и не обновляется;
- `updateRecsChannel()` и `updateChanByName()` блокируются;
- player/torrent events не создают delayed update coroutine;
- `HomeWatch` игнорирует TV broadcasts;
- `RecsService` legacy path блокируется;
- `MainActivity.updatePlayNext()` не выполняет запись.

При переходе в OFF вызывается `TvChannelsPolicy.clearPublishedContent()`.

Она удаляет только данные, принадлежащие Lampa:

- встроенные Lampa Preview Channels;
- plugin channels с internal id `plugin:*`;
- Lampa-owned Watch Next rows.

Каналы других приложений не должны затрагиваться.

При старте приложения в состоянии OFF очистка повторяется, чтобы убрать остатки от старых APK.

## ON

ON возвращает event-driven интеграцию:

- recs после соответствующих событий;
- history/book/like/look/viewed/scheduled/continued/thrown;
- Watch Next.

При этом периодический массовый refresh не возвращается.

## Scheduler всегда отключён

В `Scheduler.kt`:

```kotlin
fun scheduleUpdate(sched: Boolean) {
    return
}

fun updateContent(sync: Boolean) {
    return
}
```

Внутренние реализации Scheduler оставлены в коде, но публичные входы являются no-op.

## Многоуровневые guards

Защита стоит в:

- `AndroidJS.kt`;
- `MainActivity.kt`;
- `LampaChannels.kt`;
- `PluginChannels.kt`;
- `ChannelManager.kt`;
- `WatchNext.kt`;
- `HomeWatch.kt`;
- `RecsService.kt`;
- `Scheduler.kt`.

Это защищает от будущего upstream-кода, который может начать вызывать низкоуровневые методы напрямую.

## Static CI

`scripts/verify_tv_channels_off.py` проверяет:

- no-op Scheduler entry points;
- наличие master-switch guards;
- default OFF;
- наличие cleanup path;
- защиты прямых точек записи.

Если upstream удалит или обойдёт ожидаемую защиту, CI падает.

## Runtime CI

`app/src/androidTest/java/top/rootu/lampa/channels/TvChannelsOffInstrumentedTest.kt` запускается на Android TV API 28.

Тесты сначала доказывают, что TvProvider реально доступен и запись при ON работает, а затем проверяют, что OFF блокирует прямые update paths и удаляет Preview/Watch Next данные.

Таким образом OFF проверяется не только по исходникам, но и на реальном Android TV provider в emulator.

## Правило при upstream-обновлении

Код каналов не удалять.

При конфликте адаптировать master-switch и guards к новому upstream API, сохраняя инварианты:

- default OFF;
- OFF = никакой записи в Android TV Home;
- OFF очищает только Lampa-owned данные;
- ON = event-driven updates;
- Scheduler = no-op.


## OFF race regression (2026-11-23)

Run 45195165259 never reached instrumentation: the emulator exited because
software CPU emulation (`-accel off`) is no longer supported by its default
configuration. CI now enables KVM explicitly, fails early if unavailable and
uploads test reports even on failure.

The old entry guards were not atomic with writes. A built-in coroutine, plugin
batch waiting for ChannelManager's lock, or Watch Next insertion could pass ON,
wait through OFF cleanup, and publish afterwards. All creation paths now share
TvChannelsPolicy.publicationLock with switch changes and cleanup; deferred
built-in work checks the switch again inside that monitor. Direct ChannelHelper
creation follows the same rule. Cleanup rechecks OFF under the monitor so a late
cleanup cannot erase content after the user has switched back ON.

Watch Next cleanup uses the provider package_name column rather than inferring
ownership from a launch intent. This also removes owned legacy rows with absent
intents. Channel queries explicitly filter package ownership. The existing-row
Watch Next update targets its row URI rather than the whole collection.

Instrumentation covers real plugin publication, direct-helper rejection while
OFF, writers waiting across OFF cleanup, and cleanup of an intentless owned
Watch Next row. ON is required to create rows first, preventing vacuous OFF
passes. Local static checks alone do not prove runtime correctness.
