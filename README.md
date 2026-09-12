# GboardNavFix

LSPosed/Xposed-модуль, убирающий пустой нижний отступ (nav bar padding) у Gboard.

## Идея

Gboard читает высоту резервируемого под навигацию отступа из системных
(framework, не своих) dimen-ресурсов:

- `android:dimen/navigation_bar_height`
- `android:dimen/navigation_bar_frame_height`

Модуль хукает `Resources.getDimensionPixelSize`/`getDimension`, скоуп —
только `com.google.android.inputmethod.latin`, и обнуляет значение для
этих ресурсов, не затрагивая остальную систему.

## Сборка

Локально:
```
./gradlew assembleDebug
```
(или открыть в Android Studio и Build → Build APK)

Через GitHub Actions:
1. Запушь репозиторий на GitHub.
2. Вкладка **Actions** → workflow "Build APK" запустится автоматически
   при пуше в `main` (или запусти вручную через "Run workflow").
3. После завершения зайди в сам run → внизу **Artifacts** →
   скачай `GboardNavFix-debug.zip`, внутри — готовый `app-debug.apk`.

## Установка

1. Установить полученный APK.
2. В LSPosed Manager включить модуль, убедиться что в scope выбран Gboard.
3. Force Stop Gboard (или перезагрузка).

## Если не помогло

В `GboardNavPadFix.java` включи `USE_VIEW_FALLBACK_DEBUG_LOGGING = true`,
пересобери, открой клавиатуру и смотри `adb logcat | grep GboardNavFix` —
это покажет реальный класс/значение, если конкретная версия Gboard не
использует эти dimen напрямую.

## Альтернатива без root/LSPosed

Тот же эффект системно, через RRO (Shizuku + FabricateOverlay), без
установки самого модуля — см. обсуждение в issues или гугли
"FabricateOverlay navigation_bar_height Pixel".

## Лицензия

MIT
