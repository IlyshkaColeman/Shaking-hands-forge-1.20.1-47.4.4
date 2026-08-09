# Одноразовое действие: скопировать бинарные ассеты

Java-код и текстовые ресурсы (`sounds.json`, `lang`) я переношу сам. Но **бинарные файлы** (звуки `.ogg`, иконка, текстуры) я скопировать не могу — их нужно перенести из оригинального Fabric-репозитория один раз.

## Что скопировать

Из оригинального репозитория **Anteryo/Dap-ur-homie**, папка:
`src/main/resources/assets/testcoop/`

перенеси в этот проект в:
`src/main/resources/assets/testcoop/`

Конкретно нужны (бинарные / которых у меня нет):

- папка `sounds/` — все `.ogg` (epic_dap, mahito, clap1..6, miss, dap1, dap_hit, fireimpact, snap, aura, heli, cooldap, slap, impact, explosion_impact, galactic_dap, true_friendship, mariojump и т.д.)
- `icon.png`
- папка `textures/` — если есть (частицы/оверлеи)
- папка `player_animations/` — все `.json` анимаций (нужны для Этапа 7)

`sounds.json` и `lang/en_us.json` уже добавлены мной — их можно не копировать (или скопировать поверх, они идентичны оригиналу).

## Как проще всего скачать оригинал

1. Открой https://github.com/Anteryo/Dap-ur-homie → кнопка **Code** → **Download ZIP**.
2. Распакуй, зайди в `src/main/resources/assets/testcoop/`.
3. Скопируй оттуда `sounds/`, `icon.png`, `player_animations/` (и `textures/`, если есть) в такую же папку этого проекта.

После этого верни строку логотипа в `mods.toml` (я её временно закомментировал):
```
logoFile="icon.png"
```

> Компиляции это не мешает — без ассетов проект всё равно собирается, просто звуки/иконка не отображаются. Так что можно сделать в любой момент до игрового теста.
