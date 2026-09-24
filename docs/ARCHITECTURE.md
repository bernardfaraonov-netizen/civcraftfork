# CivCraft 2 — архитектура

Современная переработка CivCraft под **Paper 1.21.11 / Java 21**. Цель — механики сервера VimeWorld
CivCraft **Alcor** (CivilizationCraft 1.10.7). Спецификации: `docs/spec/*.md`, аудит старого кода
(баги, которые нельзя повторить): `docs/audit/*.md`.

## Принципы

1. **Весь игровой state меняется только в главном потоке.** Асинхронно — только IO (БД). Проверка:
   `Tasks.checkMain()`. Никаких `synchronized` на игровых объектах.
2. **Всё в памяти, персистентность — документы.** Каждая сущность — `Stored`-объект, сериализуемый Gson
   в JSON-колонку своей коллекции (`DocumentStore`). Изменение → `saves.save(collection, obj)`; раз в
   N секунд `SaveQueue.flush()` снимает снимок в главном потоке и пишет одним потоком-писателем.
   На выключении — синхронный flush. Не храните в документах ссылки на Bukkit-объекты (`World`,
   `Player`, `Location`) — только `BlockPos`, `ChunkKey`, UUID, строки id.
3. **Деньги — `long` в сотых долях монеты** (`Money`). Никогда `double`. Ввод — `Money.parsePositive`.
4. **Баланс — в YAML** (`src/main/resources/balance/<module>.yml`, читается `Balance`). В коде — только логика.
5. **Тексты — MiniMessage** в `src/main/resources/lang/ru_RU.yml` (ключи с префиксом модуля:
   `town.*`, `structure.*`). Ошибки правил — `throw new CivException("key", args...)`.
6. **Модули.** Каждая подсистема — класс `implements Module` в своём пакете; регистрируется в
   `CivCraftPlugin.createModules()`. Модули общаются через `civ.module(X.class)` (публичный API) и
   Bukkit-события `com.civcraft.event.*` (наследники `CivEvent`).
7. **Эффекты.** Любой бонус (здание, технология, строй, чудо, ресурс, талант, религия, нация) —
   это `Modifier(stat, op, value, scope, source)`. Модуль регистрирует `EffectProvider` в `StatService`
   и вызывает `stats.invalidate()` при изменении. Итог: `(base + ΣADD) × (1 + ΣPERCENT) × ΠMULTIPLY`.
   Общие id статов — `effect/Stats.java`.
8. **Периодика** — только через `GameClock` (`hourly/daily/everyMinute/everySecond` с порядком
   `PRODUCTION < INCOME < TAXES < UPKEEP < CONSEQUENCES`). Пропущенный ежедневный тик
   догоняется при старте.
9. **Защита мира** — `ProtectionService` + `Guard` с приоритетом. Модуль, владеющий блоками
   (приваты, постройки, лагеря, война), регистрирует свой Guard. Слушатель уже покрывает поршни,
   жидкости, взрывы, огонь, раздатчики, эндерменов, рамки, стойки.
10. **UI**: команды — Brigadier (`Cmd`), меню — `gui.Menu` (все клики отменены), формы ввода и
    подтверждения — нативные диалоги `core.ui.Prompts`, голограммы — `core.ui.Holograms` (TextDisplay).
11. **Предметы** идентифицируются только PDC-ключом `civcraft:item`, никогда по lore/имени.

## Пакеты

| Пакет | Что |
|---|---|
| `core.*` | утилиты, тексты, задачи, UI, `CivException` |
| `storage` | БД (SQLite/MySQL, HikariCP), документы, очередь сохранений |
| `model` | Resident, Town, Civilization, Claim, Camp, Relation |
| `state.GameState` | реестр и индексы всех сущностей ядра |
| `effect` | модификаторы, `StatService` |
| `culture` | карта культурных чанков |
| `protection` | защита мира |
| `clock` | игровые часы |
| `template` | загрузка .schem (Sponge v3), поворот, маркеры-таблички `/xxx` |
| модули | `camp`, `town`, `civ`, `structure`, `building`, `science`, `wonder`, `war`, `item`, `mob`, ... |

## Шаблоны построек

`src/main/resources/templates/<тема>/<id>.schem` — Sponge Schematic v3, редактируется WorldEdit.
Табличка с первой строкой `/<маркер> [арг]` превращается в функциональную точку (`Template.Marker`),
на её месте остаётся воздух; постройка сама решает, что поставить (контрольный блок, сундук, табличку).
Старые шаблоны сконвертированы `tools/convert_legacy_templates.py` (ориентация south = без поворота).
