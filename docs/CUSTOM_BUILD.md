# Кастомная сборка LAMPA: как всё устроено

Этот документ описывает текущее устройство ветки `optimize-tv-channel-updates` в `ivzaislu/LAMPA_apk`.

Цель кастомизации — получать свежую LAMPA из официального `lampa-app/LAMPA`, не переделывать APK вручную на каждой версии и при этом сохранять два отличия:

1. интеграция с Android TV Home/Watch Next полностью управляется нашим master switch и по умолчанию выключена;
2. обновления установленной кастомной LAMPA приходят только из `ivzaislu/LAMPA_apk`, а не из официального репозитория.

## Модель веток

Поддерживается простая модель:

```text
lampa-app/LAMPA:main
        ↓
ivzaislu/LAMPA_apk:main
        ↓
один кастомный commit
        ↓
ivzaislu/LAMPA_apk:optimize-tv-channel-updates
```

`main` нашего fork должен повторять официальный upstream `main`.

`optimize-tv-channel-updates` содержит тот же upstream-код плюс один логический кастомный commit.

Автосинхронизация не накапливает merge-коммиты: при переходе upstream на новую ревизию workflow сначала проверяет чистый merge, затем формирует новое дерево и создаёт новый один кастомный commit с родителем `upstream/main`.

## Версия APK

Версия приложения наследуется от `origin/main`, а не от количества кастомных коммитов:

- `versionName` = последний upstream tag, достижимый из `origin/main`;
- `versionCode` = число коммитов в `origin/main`.

Кастомные изменения не меняют Android-версию приложения.

Пример:

```text
upstream release:       v1.13.2
наш GitHub release:     v1.13.2-custom
versionName внутри APK: 1.13.2
```

CI после сборки открывает готовый APK через `aapt` и проверяет фактические `versionName` и `versionCode` против `origin/main`. Несовпадение блокирует релиз.

## Откуда приложение получает обновления

Lite flavor остаётся с `enableUpdate = true`, но update endpoint задан отдельно:

```text
ivzaislu/LAMPA_apk
```

`Updater.kt` обращается к GitHub Releases этого fork:

```text
https://api.github.com/repos/ivzaislu/LAMPA_apk/releases
```

Официальные releases `lampa-app/LAMPA` установленное кастомное приложение не использует.

Updater:

- пропускает draft releases;
- пропускает prerelease releases;
- сравнивает числовую часть версии;
- не предлагает ветку 2.x текущей 1.x-сборке;
- скачивает только APK asset;
- сначала ищет `app-lite-release.apk`, затем любой asset с расширением `.apk`;
- не может случайно скачать `.sha256` или технический отчёт вместо APK.

Суффикс GitHub release `-custom` при сравнении версии отбрасывается, поэтому `v1.13.2-custom` сравнивается как `1.13.2`.

## Android TV Home и Watch Next

Код Android TV каналов не вырезан. Это важно для совместимости с upstream.

Вместо удаления кода используется `TvChannelsPolicy` и настройка:

```text
android_tv_channels_enabled
```

Значение по умолчанию — `false`.

### OFF

При OFF:

- Preview Channels Lampa не создаются и не обновляются;
- Watch Next от Lampa не создаётся и не обновляется;
- player/torrent события не запускают обновление recs;
- HomeWatch broadcast игнорируется;
- legacy RecsService path заблокирован;
- существующие Lampa-owned Preview Channels и Watch Next rows очищаются.

Очистка ограничена данными Lampa и не должна удалять каналы других приложений.

### ON

При ON возвращаются event-driven обновления каналов и Watch Next.

Периодический массовый Scheduler при этом всё равно остаётся отключённым.

## Scheduler

`Scheduler.scheduleUpdate()` и `Scheduler.updateContent()` намеренно являются no-op.

Это повторяет ключевое поведение старой рабочей кастомной APK: код каналов существует, но автоматический массовый refresh не запускается.

## Защита от upstream-регрессий

Guards стоят в нескольких слоях:

- `AndroidJS.kt`;
- `MainActivity.kt`;
- `LampaChannels.kt`;
- `ChannelManager.kt`;
- `WatchNext.kt`;
- `HomeWatch.kt`;
- `RecsService.kt`;
- `Scheduler.kt`.

Поэтому одного случайного нового вызова из upstream недостаточно, чтобы при OFF снова начать писать в TvProvider.

Статический скрипт:

```text
scripts/verify_tv_channels_off.py
```

проверяет наличие ключевых защит после каждого upstream-обновления.

Второй скрипт:

```text
scripts/verify_update_channel.py
```

проверяет, что:

- версия по-прежнему наследуется от `origin/main`;
- update-channel по-прежнему указывает на `ivzaislu/LAMPA_apk`;
- Updater выбирает APK, а не произвольный asset.

## Runtime-тест Android TV

`TvChannelsOffInstrumentedTest` запускается на Android TV API 28 emulator.

Тест проверяет реальный TvProvider в обе стороны:

1. при ON Preview Channel/Program и Watch Next действительно можно записать;
2. после OFF эти данные реально удаляются;
3. прямые update paths при OFF ничего не записывают;
4. Scheduler не создаёт content job.

Это исключает ложноположительный тест, который мог бы пройти только потому, что TvProvider отсутствует.

## Подпись release APK

Release-сборка использует GitHub Actions Secrets:

- `KEYSTORE_BASE64`;
- `KEYSTORE_PASSWORD`;
- `RELEASE_SIGN_KEY_ALIAS`;
- `RELEASE_SIGN_KEY_PASSWORD`;
- `RELEASE_CERT_SHA256`.

После сборки `apksigner` проверяет APK и SHA-256 сертификата.

Если сертификат готового APK не совпадает с `RELEASE_CERT_SHA256`, публикация останавливается.

Технический `release-signing.txt` хранится только как Actions artifact для диагностики. В публичный GitHub Release он не выкладывается.

Важно: текущий кастомный ключ имеет другой сертификат, чем старый APK, который ранее был снят с приставки. Поэтому старый APK с сертификатом `C8066A...` нельзя обновить поверх без его исходного signing key. Все новые кастомные releases с текущим ключом обновляются друг поверх друга.

## CI и публикация release

Workflow:

```text
.github/workflows/custom-apk.yml
```

делает следующее:

1. получает upstream tags;
2. запускает оба static invariant scripts;
3. собирает Debug APK и instrumentation APK;
4. запускает Android TV emulator tests;
5. восстанавливает release keystore;
6. собирает Lite Release APK;
7. проверяет наследование `versionName/versionCode`;
8. проверяет подпись и `RELEASE_CERT_SHA256`;
9. сохраняет полный технический artifact в Actions;
10. после успешных release + emulator jobs публикует GitHub Release.

Публичный release содержит только:

```text
LAMPA-vX.Y.Z-custom.apk
LAMPA-vX.Y.Z-custom.apk.sha256
```

Если release для этой upstream-версии уже существует, новый дубликат не создаётся.

## Автосинхронизация upstream

Workflow:

```text
.github/workflows/sync-upstream.yml
```

запускается раз в неделю — по воскресеньям в 05:23 UTC — и также может быть запущен вручную.

Алгоритм:

1. fetch `lampa-app/LAMPA:main` и tags;
2. допускается только поддерживаемая линия `v1.*`;
3. fork `main` fast-forward'ится до upstream `main`;
4. upstream временно merge'ится в кастомное дерево без создания merge-коммита;
5. запускаются static invariant checks;
6. если merge чистый и проверки зелёные, создаётся новый один кастомный commit с родителем `upstream/main`;
7. `optimize-tv-channel-updates` обновляется через `--force-with-lease`;
8. push запускает полный Custom APK CI;
9. новый GitHub Release появляется только после полного зелёного CI.

Если есть merge conflict, несовместимое изменение upstream или invariant нарушен, sync workflow падает и не переписывает кастомную ветку.

Если compile/emulator/release checks после push не проходят, новый публичный release не создаётся; предыдущий рабочий release остаётся доступным.

## Что происходит при новом upstream release

Например, установлен наш `1.13.1`, а upstream публикует `v1.13.2`.

После sync:

```text
fork main                    -> upstream v1.13.2
optimize-tv-channel-updates  -> upstream v1.13.2 + один custom commit
APK versionName              -> 1.13.2
GitHub Release               -> v1.13.2-custom
Updater source               -> ivzaislu/LAMPA_apk
```

Установленная кастомная LAMPA увидит `v1.13.2-custom`, сравнит его как `1.13.2`, скачает APK из нашего release и передаст его Android installer.

## Коммиты upstream без нового tag

Если upstream `main` получил новые commits, но release tag не изменился:

- код в кастомной ветке обновится;
- `versionCode` изменится;
- `versionName` останется прежним;
- существующий `vX.Y.Z-custom` release не будет заменён автоматически.

То есть публичные обновления следуют официальным версиям, а не каждому промежуточному commit upstream.

## Главное правило сопровождения

Не редактировать новую upstream APK вручную.

Правильный цикл:

```text
upstream main обновился
        ↓
sync workflow
        ↓
один custom commit поверх нового main
        ↓
static checks
        ↓
full CI + Android TV emulator
        ↓
signed release
        ↓
custom GitHub Release
        ↓
обновление внутри LAMPA из нашего repo
```

Если upstream меняет затронутые нами участки и возникает конфликт, сначала адаптируется этот один логический custom patch, затем снова запускается вся проверочная цепочка.
