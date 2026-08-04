# Coop Moves — Forge 1.20.1 port (ПОЛНЫЙ)

Порт мода «Dap ur Homies» (Fabric 1.21.1) на **Forge 47.4.4 / Minecraft 1.20.1**.
Анимации переведены на **KosmX Player Animation Library** (Forge).

## Статус — все механики портированы (сборка #39 зелёная)

Каркас, реестры/ассеты, сеть (`CoopNetwork` SimpleChannel), анимации KosmX
(`CoopAnim` + `CoopAnimationHandler` + `PoseNetworking`) — готовы.

Механики (все играбельны):
- [x] Grab / Throw / Human Shield / Spin (+ дуга броска `TrajectoryRenderer`)
- [x] Ground Pound (`GroundPoundHandler`) — Sneak в воздухе = dive + AOE-слэм (+ HUD)
- [x] HighFive (полный): tier 0–3, Combo (H+H), Sike (ПКМ+H), Mutual Sike
- [x] Hug: hold-to-hug (F после хай-файва) + QTE-hug (G) — `HighFiveHugHandler`, `HighFiveQTEHugHandler`
- [x] Huddle (`HuddleHandler`) — групповой сбор на F + 3 QTE-шага (G/H) → баффы/XP
- [x] MarioJump, Clap (V), Push, Kick/DropKick (T), FallCatch (R)
- [x] Slap (`SlapHandler`) — полный релиз заряда без партнёра: back/front slap + camera-flick
- [x] Mahito — проклятие через дап
- [x] **Dap-ядро** (`ChargedDapHandler`): заряд G → тиры 0–5, whiff/fizzle/eye-contact,
      triple dap, perfect dap, fire dap + fusion + J-combo, heaven dap (полёт Y=500 + возврат)
- [x] Dap-семейство: Facing / NormalFacing (dap-loop) / DapCombo / PerfectDapCombo /
      DapFusion / Meteor / FallDap / DapHold / FireSlap / DivineFlamCombo
- [x] QTE-система (общий диспетчер) + клиентский ввод G/H в QTE/Fusion окна

Клиент / рендер / HUD:
- [x] HUD заряда/огня + бары партнёров + heaven-ready + whiff-cooldown + «PRESS J!» (`ChargedDapClientHandler`)
- [x] Impact-кадры удара (perfect/facing): текстуры impact1‑3 / frame0‑3 / impac7‑9
- [x] HeavenWhiteOverlay (белая вспышка heaven-дапа), Ground Pound HUD, дуга броска
- [x] Косметика верхних тиров: огненное торнадо, кольца Сатурна, aura-beam,
      каскад heaven-частиц, sonic/expanding кольца, star-burst, massive shockwave

Дополнительно доведено после первичного порта:
- **Spin (вертолёт)** полностью (`SpinHandler` + client + триггеры в GrabInputHandler,
  spin-combo с Ground Pound: раскрутка → подхват пассажира → мега-паунд).
- **HighFive Combo (H+H) + Sike** (обманный хай-файв, mutual sike).
- **Анимации от первого лица** (KosmX FP: `FpAnimationPlayer` + попозный конфиг в `CoopAnim`).
- **PlayerCleanupHandler** — очистка состояния на смерть/респаун/выход (фикс залипания R).

Также добавлено: **Meteor HUD + G-fire**, **Kick hit-flash**, и **CoopImpact-силуэт**
игрока (белый/чёрный силуэт на импакте) через 2 core-шейдера
(`cooptest_entity_white/black`) + `CoopImpactRenderType` + миксин
`LivingEntityRendererMixin` (первый миксин в порте; безопасный фолбэк на обычный
рендер, если шейдеры не загрузятся).

Остаточные мелочи:
- Мьют громкости во время heaven-дапа (Mojmap-опции громкости неудобно ставить) — пропущено.
- Divine Flame client-cue: серверный путь `startDivineFlame` в порте не вызывается
  (fire-combo идёт через ChargedDapHandler, подсказка «PRESS J» уже в dap-HUD).
- У Ground Pound в исходном репозитории нет файлов анимации dive/land (только геймплей).
- Рантайм шейдеров/миксина силуэта проверяется в игре (компиляция — зелёная).

## Проверено в игре (01.08.2026)

Mod loads on Forge 47.4.4 / MC 1.20.1, KosmX playeranimator dependency resolves.
Звуки играются (`/playsound testcoop:epic_dap player @s`) — реестр звуков и `.ogg`
ассеты подтверждены. Реестры/конфиг/инициализация рабочие.

**Grab теперь играбелен** (готов к тесту): ПКМ по игроку с поднятой рукой
захватывает, клавиши R/T/V работают.

### Как тестировать Grab (нужно 2 игрока / 2 клиента)
- **R** — поднять руку (grab ready). Второй игрок жмёт ПКМ по тебе → он тебя держит.
  Повторное **R** у держащего — бросить (drop).
- **T** (удерживать) — зарядка броска, отпустить — YEET. Заряд даёт звук/тряску.
- **V** — переключить режим «живой щит» (пока держишь кого-то).
- **Shift** (когда тебя держат) — вырваться (escape).
- В полёте после броска: **WASD** — воздушный контроль, **Space** с элитрами — буст.

### Как тестировать High Five (2 игрока)
- **H** (руки пустые) — поднять руку (держится 2.5 с, иначе «left hanging»).
- Оба игрока с поднятой рукой на расстоянии ≤1.6 блока → соединение,
  тир 0–3 по максимальной недавней скорости (беги/прыгай перед хлопком для тира повыше).
- Combo (H+H после хлопка), Sike (ПКМ+H), Hug/Huddle/QTE — пока не работают (STAGE).

### Как тестировать MarioJump / Clap
- **MarioJump**: встань на голову другого игрока и нажми **пробел** → отскок вверх
  («WAHOO!» / «BONK!»), звук mariojump.
- **Clap**: с пустыми руками жми **V** (когда никого не держишь). Быстрые
  повторные нажатия повышают тир (slow → spam → strong). Сильный клап (tier 2)
  распугивает животных в радиусе 15 блоков; двое рядом в окне 0.3 с → «синк»-клап.

### Как тестировать Push (2 игрока)
- Присядь (**Shift**) и **ПКМ** по игроку рядом (≤2.5 блока), держи ~1.5 с →
  «Tell homie to right-click!».
- Партнёр жмёт **ПКМ** по тебе → его подбрасывает вверх (в потолок не пробьёт).
- Прыжок прямо перед запуском (окно 0.8 с) даёт максимальную высоту.

### Как тестировать Kick
- С пустыми руками **тапни T** рядом с игроком/мобом → пинок с отбросом.
- **Спринт + зажать T** ~3 с → заряд, отпусти = дроп-кик (сильный отброс/урон,
  «двойной» эффект если цель одна). Кулдаун 2 с.

### Как тестировать FallCatch (2 игрока)
- Один падает с высоты. Другой заранее жмёт **R** (поднять руку) и стоит в радиусе
  ~3 блока от точки приземления. При касании земли урон от падения отменяется →
  «PERFECT CATCH!». Промах = кулдаун 1 с.

### Что осталось от оригинального `GrabInputHandler` (вернётся позже)
`GrabInputHandler` перенесён в **урезанном** виде — ветки, зовущие ещё не
портированные механики, помечены в коде `STAGE 4:` и временно убраны:
- Spin / GroundPound (управление в полёте через Shift) — вернуть с группой Spin/GroundPound;
- [x] Kick (T когда свободен) — ВЕРНУТО (`KickClientHandler.handleKickTick`);
- [x] Clap (V когда не держишь) — ВЕРНУТО.
Ядро grab/throw/shield/escape/air/elytra перенесено полностью.

## ⚠️ Dap-ядро — крупный отдельный подпроект

`ChargedDapHandler` — **~180 КБ / ≈4000+ строк в одном файле** и центр всего
Dap-семейства (Fusion/Meteor/Combo/Facing/Heaven/Hold, а также примитивы
`PerfectDapFreezePayload` (заморозка) и завязка на `QTEManager`). От него зависят:
`SitHandler` (freeze), `MahitoTrollHandler.checkForMahitoTroll` (триггер),
остаток HighFive (Hug/Huddle/QTE). Переносить его нужно **по кускам** за несколько
итераций (заряд/тиры → QTE → комбо → fire → heaven), заглушая forward-ссылки.
Все самостоятельные механики уже перенесены и собираются; дальше — это ядро.

### Уже перенесено из Dap-кластера (фундамент)
- [x] **QTE-система** (`QTEManager` + `QTEClientHandler`, 3 сообщения) — стейджи,
  колбэки, окна нажатий. Тик — в `CoopServerTick` (безусловно, дёшев вхолостую).
  HUD-бар и `resolveKeyName` отложены (Этап 6). Триггеры придут с Dap-комбо / Hug.
- [x] **Mahito** (`MahitoTrollHandler` + client) — готов, триггер из Dap.
- [~] `ChargedDapHandler` — создан **shell**: `PerfectDapFreezePayload` (заморозка) +
  query-заглушки (`isCharging`/`isInComboCooldown`/`isInBlockingAnimation` → false).
  Ядро заряда/тиров/комбо/fire/heaven (~2400 строк) — по кускам далее.
- [x] `PerfectDapFreezePayload` (заморозка) — готов (+ `ChargedDapClientHandler.isPlayerFrozen`).
- [x] **Sit** (`SitHandler` + `SitClientHandler`) — `/sit`, поза+заморозка, помощь
  «зажми F» рядом с сидящим → анимация подъёма. Реальный лок движения — с
  `MovementFreezeMixin` (Этап 5); пока позицию удерживает серверный тик.
- [x] `DapSession` + `DapSessionManager` — позиционирование двух игроков перед
  дапом (плавное сближение лицом друг к другу). Тик — в `CoopServerTick`.
- [x] `HeavenDapPayloads` (4 S2C-сигнала) + `HeavenDapClientHandler` (stub, FX — Этап 6).
- [x] `FacingDapHandler` — кинематографичный дап лицом-к-лицу с аурой (тик под enableDap).
      Shell `ChargedDapHandler` дорос: `cooldowns`, `cooldownMs()`, `FacingDapImpactPayload`.
- [x] `NormalFacingDapHandler` — dap-loop (EntityInteract + LightningBolt + 2 пакета,
      ординалы 81/84/85). Тик/ивент под enableDap.
- [x] `MeteorStrikeHandler` (+ client stub) — метеор от perfect-legendary дапа
      (кратер/взрывы, 4 пакета, тик под enableDap). Grant зовётся ядром.
- [x] `DapComboChain` — QTE-комбо после perfect-дапа (extend1/both/myboy, ординалы
      43/44/45/47). Тик под enableDap+enableDapCombo. Триггер — ядро.
- [x] `FireSlapHandler` — при полном fire-заряде удар по мобу поджигает/отбрасывает
      (AttackEntityEvent, читает `fireLevel`). Shell дорос `fireLevel`.
- [x] `DivineFlamCombo` (+ client stub) — fire-комбо (J вдвоём) → вихрь пламени
      (2 пакета, тик под enableDap). Триггер — fire-dap ядра.
- [x] `PerfectDapComboHandler` — тайминг-бар комбо (x1..x∞) через
      `DapFusionHandler.FusionQTEPayload`. Тик под enableDap. Ординалы 69/74.
- [~] `DapFusionHandler` — создан **shell** с `FusionQTEPayload` (+ `FusionClientHandler`
      stub). Полный fusion (~1200 строк) — отдельным проходом.
- [x] `FallDapHandler` (+ client) — заряд дапа в падении (20+ блоков) → сквош-дайв,
      приземление на игрока = сквош на 25 с (2 пакета, тик под enableFallDap).
- [x] `DapHoldHandler` (+ client) — hold-J dapping loop + групповой дап (10 пакетов,
      armor-stand «руки», тик под enableDapHold). Триггер — tryDetect из ядра.
- [x] `DapFusionHandler` (полный, 51КБ) + `FusionClientHandler` — ритуал слияния
      (walk-QTE → 10-стадийный QTE → fusion → разблокировка Meteor). 6 пакетов,
      тик под enableDap. Заменил shell. Триггерится ядром/DapHold-интерплеем.
- [x] Заглушки `HighFiveQTEHugHandler`/`HuddleHandler`/`HighFiveHugHandler` +
      централизованная QTE-цепочка в `QTEManager` (PerfectDapCombo → Fusion →
      QTEHug → Huddle → DapCombo → QTEManager). Оживило QTE у dap-combo/fusion.
- [ ] **`ChargedDapHandler` ядро** — ПОСЛЕДНЕЕ: заряд G → тиры → комбо → fire →
      heaven (~2400 строк). Читаю по кускам (дошёл до ~строки 722), замена shell.
      Диспетчер QTE уже вынесен в QTEManager, поэтому в ядре его не будет.
- [ ] Остаток HighFive: Hug / QTEHug / Huddle.

## СЛЕДУЮЩИЙ ШАГ (для продолжения работы)

Порядок, в котором продолжать Этап 4 — брать класс из оригинального репо
`Anteryo/Dap-ur-homie` (ветка main) и портировать по уже отработанному шаблону:

1. ✅ ГОТОВО — `GrabInteractionHandler` + `GrabInputHandler` + `GrabClientEffects`.
   Первая играбельная механика (захват/бросок). Регистрация: сервер —
   `CoopMoves.commonSetup` (под `enableGrab`); клиент — `CoopMovesClient`
   (`onClientSetup` + `onRegisterKeyMappings`).
2. Группа HighFive — **база готова** (`HighFiveHandler` + `HighFiveClientHandler`,
   клавиша H). Остаток группы (перевести дальше): `HighFiveHugHandler`,
   `HighFiveQTEHugHandler`, `HuddleHandler`, `QTEManager` + QTE-пакеты.
3. Dap-семейство: `ChargedDapHandler`, `DapSessionManager`, `DapComboChain`,
   `DapFusionHandler`, `MeteorStrikeHandler`, `PerfectDapComboHandler`,
   `FacingDapHandler`, `NormalFacingDapHandler`, `DapHoldHandler`, `FallDapHandler`,
   `HeavenDapPayloads`, `SitHandler`.
4. `PushInteractionHandler` (заменить STUB), `FallCatchHandler`, `MarioJumpHandler`,
   `KickHandler`, `SlapHandler`, `ClapHandler`, `SpinHandler` (STUB),
   `GroundPoundHandler`.
5. Mahito + вспомогательные: `MahitoTrollHandler`, `MahitoCraftingHandler`,
   `AnimationTickHandler`, `LaunchedPlayerTracker`, `CarryingSlowdown`,
   `PlayerCleanupHandler`, `DebugQTECommand`.
6. Этап 5 — 11 миксинов (перенацелить на 1.20.1 + Mojmap, добавить в
   `coopmoves.mixins.json`, который сейчас с пустыми списками).
7. Этап 6 — keybinds (`RegisterKeyMappingsEvent`), HUD (`RegisterGuiOverlaysEvent`),
   `TrajectoryRenderer` (`RenderLevelStageEvent`), impact-frames, шейдеры,
   наполнить `FirstPersonAnimationTest`.

### Шаблон переноса (уже отработан)

- Регистрация пакета: добавить в `CoopNetwork.registerAll()` (порядок важен —
  id пакетов должны совпадать на клиенте и сервере).
- Сообщение: `record` + статические `encode/decode/handle`, серверная ветка через
  `c.getSender()`, клиентская — **только** через `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)`
  с вызовом класса из пакета `client` (иначе краш на выделенном сервере).
- Серверные тики добавлять в `CoopServerTick`.
- Клиентские `register()` — в `CoopMovesClient.onClientSetup`.
- Анимации: `CoopAnim.play(player, ID)` / `CoopAnim.stop(player)`.

## Заглушки (заполняются на следующих этапах)

Эти классы созданы с настоящими сигнатурами, но пустыми телами, чтобы ядро
компилировалось. Помечены в коде как `STAGE N STUB`:

- `client/FirstPersonAnimationTest` — анимации рук от первого лица (Этап 6)
- `client/ChargedDapClientHandler`, `client/DapHoldClientHandler` (Этап 4, Dap)
- `client/HighFiveClientHandler`, `client/PushClientHandler`,
  `client/MahitoClientHandler`, `client/FallDapClientHandler` (Этап 4)
- `HighFiveHandler` — серверная часть (Этап 4)

## Как собрать (важно — прочитай)

Проект использует ForgeGradle 6, которому нужен **Gradle 8.1.1** и **JDK 17**.

В папке пока **нет `gradle-wrapper.jar`** (бинарник нельзя создать текстом). Сгенерируй wrapper одним из способов:

**Вариант A — если установлен Gradle:**
```
cd "E:\Mod 1.20.1 anim"
gradle wrapper --gradle-version 8.1.1
```
После этого появятся `gradlew.bat` и `gradle/wrapper/gradle-wrapper.jar`.

**Вариант B — из Forge MDK:**
Скачай Forge 1.20.1 MDK (47.4.4) с https://files.minecraftforge.net/, распакуй и скопируй оттуда `gradlew`, `gradlew.bat` и папку `gradle/wrapper/` в эту папку.

### Сборка и запуск
```
gradlew build            # компиляция и сборка jar (build/libs)
gradlew runClient        # запуск клиента для теста в игре
```

Первый запуск долгий: ForgeGradle скачает и деобфусцирует Minecraft.

## Что мне присылать при ошибках

Полный вывод `gradlew build` (или `runClient`) с ошибками компиляции/загрузки — я правлю по ним. Для визуальных багов — короткое видео/скриншот и описание, как отличается от Fabric-версии.

## Требования к запуску мода (клиент/сервер)
- Minecraft 1.20.1, Forge 47.4.4
- KosmX Player Animation Library (Forge) — тянется автоматически как зависимость сборки; в рантайме на сервере/клиенте нужен установленный `playeranimator`.
