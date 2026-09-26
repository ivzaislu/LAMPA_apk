# Android TV: каналы и Watch Next в кастомной ветке

Эта памятка фиксирует кастомизацию Android TV Home для `ivzaislu/LAMPA_apk`.

## История решения

Старый рабочий APK, снятый с приставки 26 сентября 2026 года, показал:

- все 9 каналов в приложении оставались в коде;
- `LampaChannels.update()`, `updateRecsChannel()` и `updateChanByName()` присутствовали;
- код 15-минутного `JobScheduler` тоже присутствовал;
- но публичные `Scheduler.scheduleUpdate()` и `Scheduler.updateContent()` сразу завершались.

То есть рабочая кастомизация не удаляла каналы из проекта — она не давала запускаться массовому Scheduler.

## Текущая схема

Новая ветка делает это управляемо и жёстче.

### 1. Массовый Scheduler всегда отключён

Файл:

`app/src/main/java/top/rootu/lampa/sched/Scheduler.kt`

Публичные точки входа остаются заглушками:

```kotlin
fun scheduleUpdate(sched: Boolean) {
    return
}

fun updateContent(sync: Boolean) {
    return
}
```

Поэтому не запускаются:

- периодический full refresh;
- full refresh при первом запуске;
- full refresh после загрузки приложения;
- full refresh через `BootReceiver`;
- full refresh после `ACTION_INITIALIZE_PROGRAMS`;
- массовый refresh через `ContentJobService`.

### 2. Код каналов НЕ удаляется

Нельзя вырезать:

- `ChannelManager`;
- `LampaChannels`;
- `WatchNext`;
- provider-классы;
- event-driven вызовы из `AndroidJS`.

Это позволяет переносить кастомизацию на новые upstream-версии без восстановления удалённого кода.

### 3. Есть единый master switch

Файл:

`app/src/main/java/top/rootu/lampa/channels/TvChannelsPolicy.kt`

Настройка хранится в:

`Prefs.androidTvChannelsEnabled`

Ключ:

`android_tv_channels_enabled`

**Значение по умолчанию: `false` (OFF).**

В меню приложения на Android TV есть пункт:

- «Включить каналы Android TV»
- «Выключить каналы Android TV»

### 4. Что означает OFF

OFF означает полное отключение интеграции Lampa с Android TV Home:

- `ChannelManager.update()` не пишет preview channels;
- `LampaChannels.update()` не запускается;
- `updateRecsChannel()` не работает;
- `updateChanByName()` не работает;
- `WatchNext.add/rem/updateWatchNext/addLastPlayed/removeContinueWatch` не работают;
- `AndroidJS` не создаёт отложенные coroutine для обновления каналов;
- `MainActivity.updatePlayNext()` сразу выходит;
- старый pre-O путь `RecsService.updateRecs()` не вызывается из player/torrent events.

При выключении вызывается очистка опубликованных данных:

- удаляются preview-каналы Lampa;
- удаляются Watch Next rows, принадлежащие Lampa.

При каждом запуске приложения, если настройка OFF, очистка повторяется безопасно. Это нужно, чтобы убрать хвосты от старых APK.

### 5. Что означает ON

ON возвращает только **event-driven** интеграцию:

- запуск плеера/торрента может обновить `recs`;
- изменения `history/book/like/look/viewed/scheduled/continued/thrown` обновляют соответствующий канал;
- Watch Next обновляется по событию.

При этом массовый Scheduler остаётся отключённым. То есть ON не возвращает 15-минутные фоновые full refresh.

## Где стоят защиты

Защита намеренно стоит в нескольких слоях.

### Верхний слой

`AndroidJS.kt`

Не создаёт работу каналов при OFF.

`MainActivity.kt`

Не выполняет Play Next обработку при OFF и содержит пользовательский переключатель.

### Средний слой

`LampaChannels.kt`

Все публичные методы проверяют `TvChannelsPolicy.enabled`.

### Нижний слой

`ChannelManager.kt` и `WatchNext.kt`

Даже если новый код в будущем случайно вызовет их напрямую, запись в TvProvider блокируется при OFF.

Это сделано специально: одного guard только в UI недостаточно для будущих upstream-изменений.

## Как переносить на новую upstream-версию

После merge/rebase с новой Lampa:

1. Проверить `Scheduler.kt`: `scheduleUpdate()` и `updateContent()` должны оставаться заглушками.
2. Проверить наличие `Prefs.androidTvChannelsEnabled`, default = `false`.
3. Проверить `TvChannelsPolicy.kt`.
4. Проверить guards в:
   - `AndroidJS.kt`;
   - `MainActivity.updatePlayNext()`;
   - `LampaChannels.kt`;
   - `ChannelManager.kt`;
   - `WatchNext.kt`.
5. Проверить, что пункт ON/OFF всё ещё есть в `MainActivity.showMenuDialog()`.
6. Проверить, что OFF вызывает `TvChannelsPolicy.clearPublishedContent()`.
7. Не удалять классы каналов физически.
8. Собрать APK и проверить на приставке.

## Проверка на приставке

### OFF

После установки/запуска:

- каналы Lampa не должны появляться или обновляться;
- Watch Next от Lampa должен быть очищен;
- запуск фильма/торрента не должен создавать обновление `recs`;
- изменение избранного/истории не должно создавать/обновлять канал;
- через 15+ минут не должно происходить фонового refresh.

### ON

После включения через меню:

- массового refresh сразу не требуется;
- после реального события соответствующий канал должен обновиться;
- `recs` должен обновляться после событий просмотра;
- Watch Next должен снова реагировать на события.

## CI-сборка

В ветке есть:

`.github/workflows/custom-apk.yml`

Workflow собирает `Lite Debug APK` и сохраняет его как artifact.

Особенности CI:

- `fetch-depth: 0` обязателен, потому что версия вычисляется из git refs;
- `app/build.gradle` конфигурирует release signing даже при debug build, поэтому workflow создаёт временный локальный keystore;
- debug APK подписан debug-ключом и обычно не устанавливается поверх release APK с другой подписью без удаления старой версии.

## Главное правило

**Не вырезать каналы. Отключать их через master switch и оставлять Scheduler заглушенным.**

Так будущие версии проще обновлять, сравнивать и восстанавливать.
