# Upstream sync, версия, обновления и релизы

Краткое описание release-контура кастомной ветки.

Подробная архитектура: [CUSTOM_BUILD.md](CUSTOM_BUILD.md).

## Источник кода

Официальный источник:

`lampa-app/LAMPA:main`

Наш fork `main` fast-forward'ится до upstream.

Кастомная ветка поддерживается как:

```text
upstream main + один custom commit
```

Workflow `.github/workflows/sync-upstream.yml` не накапливает merge-коммиты. Он проверяет merge во временном состоянии, создаёт итоговое дерево и формирует новый один commit с родителем `upstream/main`.

Автоматическая проверка upstream выполняется раз в неделю — по воскресеньям в 05:23 UTC. При необходимости workflow можно запустить вручную через `workflow_dispatch`.

## Источник версии

`app/build.gradle`:

- `versionCode` берётся через `git rev-list --count origin/main`;
- `versionName` берётся через `git describe --tags --abbrev=0 origin/main`.

CI fetch'ит upstream tags перед сборкой и проверяет значения уже внутри готового APK.

## Источник обновлений

`BuildConfig.UPDATE_REPO_ID` задан как:

`ivzaislu/LAMPA_apk`

Поэтому Lite Updater читает только releases нашего fork.

Draft/prerelease игнорируются. Для установки выбирается APK asset, а не последний произвольный asset.

## Имена releases

GitHub Release:

`vX.Y.Z-custom`

Android `versionName` внутри APK:

`X.Y.Z`

Пример:

```text
release:     v1.13.2-custom
versionName: 1.13.2
```

## Release gate

`.github/workflows/custom-apk.yml` публикует release только после:

- static custom invariants;
- успешной сборки;
- Android TV emulator tests;
- проверки inherited version;
- проверки APK signature;
- совпадения signer certificate с `RELEASE_CERT_SHA256`.

Публично публикуются только APK и его SHA-256.

Полный signing report остаётся в Actions artifact.

## Поведение при новом upstream tag

После появления новой официальной версии:

1. sync переносит upstream main в fork;
2. пересобирается один custom commit поверх нового main;
3. полный CI проверяет кастомизацию;
4. создаётся `vX.Y.Z-custom`;
5. установленная кастомная LAMPA видит новую версию только в нашем репозитории.

## Поведение без нового tag

Новые upstream commits попадают в кастомную ветку, но новый публичный release для уже существующего `vX.Y.Z-custom` не создаётся.

## Ошибки и конфликты

Merge conflict в sync останавливает workflow до переписывания ветки.

Static/runtime/signing/version failure блокирует новый release.

Предыдущий опубликованный release остаётся доступным.
