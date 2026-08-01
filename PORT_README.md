# Coop Moves — Forge 1.20.1 port (в процессе)

Порт мода «Dap ur Homies» (Fabric 1.21.1) на **Forge 47.4.4 / Minecraft 1.20.1**.
Анимации переведены на **KosmX Player Animation Library** (Forge).

## Статус

- [x] Этап 1 — каркас проекта
- [x] Этап 2 — реестры и ресурсы (звуки, эффекты, зелье, lang) — код готов; бинарные ассеты скопированы
- [x] Этап 3 — сеть (общий SimpleChannel-канал CoopNetwork)
- [x] Этап 7 (ядро) — анимации на KosmX: `CoopAnim` (слой) + `CoopAnimationHandler` (1914 строк, стейт-машина) + `PoseNetworking`
- [ ] Этап 4 — механики (портируются юнитами: пакеты + серверная + клиентская логика + регистрация)
- [ ] Этап 5 — миксины
- [ ] Этап 6 — клиент (ввод, HUD, рендер) + `FirstPersonAnimationTest`
- [ ] Этап 8 — сборка и тесты

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
