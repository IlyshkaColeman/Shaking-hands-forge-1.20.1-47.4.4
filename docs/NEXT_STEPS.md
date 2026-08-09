# Что ещё нужно доработать

Этот файл — короткий roadmap после аудита Forge-порта. Сборка проходит, основные механики и ресурсы подключены, но ниже остались зоны, которые стоит довести перед большим публичным релизом.

## Приоритет 1 — проверить в игре

- Пройти полный тест на двух клиентах: G/Shift+G dap, Sync Dap, High Five, Hug, Huddle, Grab/Throw, Kick, Catch, Push, Clap.
- Отдельно проверить сложные цепочки: Fire Dap → J Divine Flame Combo, Fire Dap → G Fusion, Fusion → Meteor.
- Проверить Heaven Dap: заряд, звук, тряску камеры, телепорт, возврат и “Perfect Friendship”.
- Проверить, что `GuiOverlayMessageMixin` красиво заменяет только тексты механик и не трогает чужие actionbar-сообщения.
- Проверить dedicated server: подключение клиента, регистрация пакетов, отсутствие client-only классов на сервере.

## Приоритет 2 — первое лицо и анимации

- `FirstPersonAnimationTest` сейчас остаётся пустым фасадом. Часть первого лица работает через `CoopAnim/FpAnimationPlayer`, но полноценную отдельную систему first-person рук лучше допилить отдельно.
- Проверить все длительные hold-анимации: G charge, fire charge, H waiting, grab charge, huddle idle.
- Дочистить комментарии `reduced`, `ported later`, `stub`, если код уже реализован. Они путают будущую разработку.

## Приоритет 3 — документация и UX

- Обновить `CONTROLS_AND_MECHANICS.md`, чтобы он точно совпадал с текущим кодом. Например, standalone ground pound сейчас отключён и работает только через thrown/spin-flow.
- Добавить GIF/скриншоты в README: high five, sync dap, fire charge, grab/throw, meteor.
- Добавить таблицу известных конфликтов клавиш: F пересекается с vanilla swap hands, G/H/J используются в QTE.
- Добавить SHA-256 для каждого GitHub Release.

## Приоритет 4 — совместимость

- Сейчас мод рассчитан на Minecraft `1.20.1` + Forge `47.4.4`.
- Совместимость с `1.21.x` лучше делать отдельной веткой/портом: Forge/NeoForge/Fabric API и анимационные библиотеки там отличаются.
- Не менять внутренний `mod_id=coopmoves` без отдельной миграции конфига/сетевых id/asset namespace.

## Приоритет 5 — полировка перед релизом

- Прогнать `gradlew clean build`.
- Проверить jar в чистой сборке Minecraft с зависимостями:
  - Forge 47.4.4
  - player-animation-lib-forge-1.0.2-rc1+1.20
  - bendy-lib-forge-4.0.0
- Проверить, что jar называется `Shaking-hands-forge-1.20.1-47.4.4.jar`.
- Создать GitHub Release и приложить jar + SHA-256.
