# Аудит D — потоки, таймеры, предметы, юниты, мобы, случайные события, популяторы, рыбалка, модули moblib / civregister / civcraft_dynmap

Что проверено: `threading/` (включая `tasks`, `timers`, `sync`, `sync/request`), `items/` (включая `units` и `components`), `lorestorage/`, `loregui/`, `loreenhancements/`, `mobs/` (со всеми подпакетами), `randomevents/` (включая `components`), `populators/`, `event/`, `recover/`, `fishing/`, `tasks/`. Отдельные модули: `moblib`, `civregister`, `civcraft_dynmap`. Цифры взяты из `civcraft/data/*.yml`: materials, units, enchantments, fishing, espionage, randomevents, goods, civ, structures, war.

Все пути указаны от корня репозитория. Префикс `civcraft/src/com/avrgaming/civcraft/` везде написан полностью.
Каждая находка проверена по коду. Там, где вывод зависит от поведения CraftBukkit 1.7.10, это оговорено.

---

## Баги и недочёты

### A. Потоки, планировщик, календарные события (`threading/`, `event/`)

1. **Критический**. `civcraft/src/com/avrgaming/civcraft/event/DailyEvent.java:57-68`: поле `dayExecuted` выставляется при первом запуске и больше никогда не сбрасывается. Поэтому ежедневное событие выполняется **один раз за время жизни процесса**, а все следующие дни пишут в лог «TRIED TO EXECUTE DAILY EVENT TWICE». Налоги, содержание (upkeep), `onDailyEvent` у структур и чудес, проверка победы и счётчики долгов останавливаются, если сервер не перезапускается каждый день.
   *В переписанной версии:* хранить дату последнего выполнения в БД и выполнять событие ровно один раз на календарные сутки, идемпотентно, с навёрстыванием пропущенных дней.

2. **Критический**. `civcraft/src/com/avrgaming/civcraft/event/EventTimerTask.java:55` вызывает `process()` в **асинхронном** потоке (таймер зарегистрирован через `asyncTimer` в `main/CivCraft.java:189`). Из-за этого:
   - `event/WarEvent.java:38` вызывает `War.setWarTime(true)` асинхронно. Это рассылки, `repositionPlayers` (телепорт игроков) и кик.
   - `randomevents/RandomEventTimer.java:67-68` вызывает `RandomEvent.start()` асинхронно. Отсюда `pluginManager.registerEvents` из чужого потока (`randomevents/components/BlockBreak.java:27-28`, `KillMobs.java:24-25`) и `process()` компонентов.

   *В переписанной версии:* планировщик событий должен выполнять игровую логику только в основном потоке (или в region/global scheduler Folia/Paper). Асинхронными оставить только расчёты и ввод-вывод.

3. **Критический**. Вопросы игрокам (`civcraft/src/com/avrgaming/civcraft/threading/tasks/PlayerQuestionTask.java:57-80`, `CivLeaderQuestionTask.java:38-64`, `CivQuestionTask.java:65-111`) запускаются через `TaskMaster.asyncTask` (`main/CivGlobal.java:831,848,1494,1511`). Колбэк `finishedFunction.processResponse(...)` выполняется **в асинхронном потоке**. Например, `questions/TradeRequest.java:18` → `object/Resident.java:1407` вызывает `player.openInventory(inv)` асинхронно, а `JoinTownResponse` → `town.addResident` меняет состояние без синхронизации. Кроме того, каждый вопрос держит поток пула до 30 с (`wait(timeout)`), а `synchronized(responded)` синхронизируется на разделяемых `Boolean.TRUE/FALSE`.
   *В переписанной версии:* хранить неблокирующий реестр ожидающих вопросов с дедлайном и проверять таймаут таймером. Ответ обрабатывать в основном потоке.

4. **Критический**. `civcraft/src/com/avrgaming/civcraft/threading/tasks/PlayerLoginAsyncTask.java`:
   - `:93` вызывает `CivTutorial.showTutorialInventory(getPlayer())`, то есть `player.openInventory` (`tutorial/CivTutorial.java:139`) асинхронно.
   - `:160` и `:208` вызывают `resident.teleportHome()` и `getPlayer().teleport(...)` асинхронно.
   - `:204-210`: цикл по **всем** записям `global:respawnPlayer` игнорирует `split[0]` (имя игрока). Любой входящий игрок телепортируется к точке возрождения чужого отложенного респавна, а все записи удаляются.

   *В переписанной версии:* весь вход игрока обрабатывать в основном потоке. Отложенный респавн хранить с ключом по UUID.

5. **Высокий**. `civcraft/src/com/avrgaming/civcraft/threading/tasks/EspionageMissionTask.java:82-162`. Цикл шпионской миссии работает асинхронно и вызывает `player.getLocation()`, `CivMessage.global` и `Unit.removeUnit(player)` (изменение инвентаря, `:133`). При выходе за границы цивилизации (`:149-152`) выполняется `return` без `resident.setPerformingMission(false)`. Резидент навсегда остаётся «на миссии»: `ReduceExposureTimer` (`threading/timers/ReduceExposureTimer.java:17`) перестаёт снижать ему экспозицию.
   *В переписанной версии:* повторяющаяся синхронная задача раз в 1 с с гарантированной очисткой состояния (try/finally или отмена задачи).

6. **Высокий**. `civcraft/src/com/avrgaming/civcraft/threading/sync/SyncGetChestInventory.java:66-83`. Если чанк не загружен, `chest` остаётся `null` и строка `:81` бросает NPE. Запрос не завершается, и ожидающий поток висит 5 с (при `retry=true` — бесконечно, с предупреждением в логе каждые 5 с). Если блок не сундук (`:69-76`), код **принудительно ставит на его место сундук**. Разрушенный сундук структуры тихо «воскресает».
   *В переписанной версии:* возвращать «недоступно», не создавать блоки неявно, всегда завершать запрос.

7. **Высокий**. `civcraft/src/com/avrgaming/civcraft/threading/sync/SyncLoadChunk.java:57-60`. При неудачном `chunk.load()` выполняется `continue` без `finished=true` и `signalAll()`. `CivAsyncTask.syncLoadChunk` (`threading/CivAsyncTask.java:271-281`) крутится в `while(!finished)` **бесконечно**: утечка потока и livelock.
   *В переписанной версии:* использовать `CompletableFuture` с таймаутом и исключением (на Paper — `World#getChunkAtAsync`).

8. **Высокий**. `civcraft/src/com/avrgaming/civcraft/threading/sync/SyncGrowTask.java:58-60`. Когда очередь пуста, выполняется `return` изнутри цикла, и блок `:104-108` (учёт пропущенных ростов для незагруженных ферм) почти никогда не выполняется: только если за тик набралось ≥200 запросов. `request.result` может остаться `null`, а `CivAsyncTask.growBlocks` приводит его к `(Boolean)`.
   *В переписанной версии:* обрабатывать хвост после цикла через `break`.

9. **Высокий**. `civcraft/src/com/avrgaming/civcraft/event/GoodieRepoEvent.java:40-42` вызывает `town.removeGoodie(goodie)` внутри итерации по `town.getBonusGoodies()`. Это `values()` той же `HashMap` (`object/Town.java:1981,2167`). При двух и более гуди у города возникает `ConcurrentModificationException`, и вся `SyncTask` падает до `replenish()`. Гуди не возвращаются к аванпостам.
   *В переписанной версии:* итерировать по копии.

10. **Высокий**. `civcraft/src/com/avrgaming/civcraft/threading/timers/DailyTimer.java:169`: `taxesToCiv = total*taxrate`. Здесь `total` — накопитель цивилизации, изначально 0, а должен быть `townTotal`. В результате цивилизация **никогда не получает** налог с поимущественного и подушного сбора городов, а городу остаётся 100 %.
    *В переписанной версии:* `taxesToCiv = townTotal * rate`, с юнит-тестом.

11. **Высокий**. `civcraft/src/com/avrgaming/civcraft/threading/tasks/onLoadTask.java:62` и `:92`: при ошибке загрузки шаблона одной структуры или чуда выполняется `return`. Для **всех** следующих структур не вызываются `PostBuildSyncTask` и `onLoad()`: сундуки, знаки и тех-бар не регистрируются.
   *В переписанной версии:* `continue` и изоляция ошибок по объектам.

12. **Высокий**. `civcraft/src/com/avrgaming/civcraft/threading/tasks/BuildAsyncTask.java`:
    - `:328`: `build_tasks.remove(this)`, где `this` — внутренний `SyncTask`, а не `BuildAsyncTask`. После отмены чуда задача остаётся в `town.build_tasks`.
    - `:93,147,274,365`: `synchronized(aborted)` на поле `Boolean`, которое переприсваивается, поэтому синхронизации фактически нет.
    - `:104-131`: поток спит 30 или 10 минут внутри поблочного цикла.
    - `:220-234`: `setComplete`, `save`, `onComplete` и глобальные сообщения идут из асинхронного потока.

    *В переписанной версии:* машина состояний стройки, тикаемая синхронно. Прогресс хранить в БД, установку блоков выполнять пакетами с бюджетом на тик.

13. **Высокий**. `civcraft/src/com/avrgaming/civcraft/threading/timers/UpdateEventTimer.java:66-86` раз в секунду асинхронно вызывает `struct.onUpdate()` и `wonder.onUpdate()`, а для каждого треммеля ставит асинхронную задачу. `threading/timers/EffectEventTimer.java:73`, `threading/tasks/CultureProcessAsyncTask.java:225-235` и `threading/timers/BeakerTimer.java:42-74` меняют состояние городов и цивилизаций (обычные `HashMap` внутри `Town`) из пула без синхронизации. Параллельно с ними работает основной поток. Это гонки данных.
    *В переписанной версии:* единая модель: доменные мутации только в основном потоке. Асинхронно только чистые вычисления над снапшотами.

14. **Высокий**. `civcraft/src/com/avrgaming/civcraft/threading/tasks/TrommelAsyncTask.java:132-181`:
    - Результат `updateInventory(Action.REMOVE, ...)` игнорируется. Если игрок забрал булыжник между снапшотом и запросом, продукт всё равно выдаётся. Это бесплатная генерация.
    - Содержимое сундуков (`tmp.getContents()`, `source_inv.getContents()`) читается асинхронно.
    - Логика `skippedCounter` недостижима, потому что `getChestInventory` никогда не возвращает `null` (см. п. 6).

    *В переписанной версии:* атомарная транзакция «снять вход, выдать выход» в основном потоке.

15. **Средний**. `civcraft/src/com/avrgaming/civcraft/threading/tasks/WindmillPostProcessSyncTask.java:54-143`. Посадка делается по асинхронному снапшоту без проверки, что блок всё ещё воздух (`setTypeId` может затереть поставленный игроком блок). Неудача `removeItem` семян игнорируется, а культура всё равно сажается (бесплатные посадки).
    *В переписанной версии:* перед посадкой перепроверять блок и успешность списания.

16. **Средний**. `civcraft/src/com/avrgaming/civcraft/threading/tasks/LoadPastureEntityTask.java:53-64`. Если лок не получен, задача перепланируется (`:56`), но затем сразу удаляет из SessionDB **все** оставшиеся записи (`:60-64`). Повторная попытка ничего не загрузит, и животные пастбища теряются.
    *В переписанной версии:* ставить `return` после перепланирования.

17. **Средний**. `civcraft/src/com/avrgaming/civcraft/threading/TaskMaster.java:131`: `cancelTimer` ищет в карте `tasks`, а не `timers`, и таймер не отменяется. `syncTimer` (`:159-166`) вообще не регистрирует задачу, её невозможно отменить по имени. `stopAll()` нигде не вызывается, `onDisable` (`main/CivCraft.java:327-329`) не останавливает потоки, которые спят в `Thread.sleep` или `wait` (вопросы, стройка, шпионаж, анонсы). После `/reload` они живут со старым classloader. `HashMap tasks/timers` модифицируются из разных потоков. Имя `""` у множества `asyncTask` затирает запись.
    *В переписанной версии:* реестр задач с владельцем, отмена при выключении, никаких спящих потоков.

18. **Средний**. `civcraft/src/com/avrgaming/civcraft/threading/timers/AnnouncementTimer.java:75-84`: асинхронный таймер раз в час спит по 5 мин на каждую подсказку. При более чем 12 строках в `tips.txt` запуски накладываются и потоки копятся (асинхронные повторяющиеся задачи Bukkit не ждут окончания предыдущего запуска).
    *В переписанной версии:* одна подсказка за тик таймера с периодом 5 мин.

19. **Средний**. `civcraft/src/com/avrgaming/civcraft/threading/timers/ChangeGovernmentTimer.java:44`: `return` вместо `continue`. Одна цивилизация в анархии без записи в SessionDB обрывает обработку остальных. Вызов асинхронный.

20. **Средний**. `civcraft/src/com/avrgaming/civcraft/threading/timers/EffectEventTimer.java:139`: `town.addAccumulatedCulture(unusedBeakers)` добавляет **неконвертированные** колбы вместо `cultureFromBeakers`. Сообщение (`:135-137`) показывает правильную цифру, а начисляется другая. `:145`: `return` при ошибке конфига прерывает часовой тик для всех городов.

21. **Средний**. `civcraft/src/com/avrgaming/civcraft/event/SpawnRegenEvent.java:79`: `cal.add(HOUR_OF_DAY, regen_hour)` вместо `set`. При `regen_spawn_hour != 0` регенерация сработает «через N часов», а не в час N. `:51`: мир захардкожен как `"world"`, а `regenerateChunk` в современном Paper не поддерживается.
    *В переписанной версии:* восстанавливать спавн из схемы или снапшота.

22. **Средний**. `civcraft/src/com/avrgaming/civcraft/threading/tasks/PlayerChunkNotifyAsyncTask.java:48,221`: статическая `HashMap cultureEnterTimes` пишется из множества параллельных асинхронных задач (по одной на каждое перемещение между чанками, `listener/PlayerListener.java:206`). Это гонка (порча HashMap) и неограниченный рост.
    *В переписанной версии:* синхронная обработка смены чанка и кэш с TTL.

23. **Средний**. `civcraft/src/com/avrgaming/civcraft/threading/tasks/BuildPreviewAsyncTask.java:85-99`: чтение блоков мира (`getRelative`, `ItemManager.getId(b)`) и `sendBlockChange` из асинхронного потока. Запись в `resident.previewUndo` без синхронизации.

24. **Средний**. `civcraft/src/com/avrgaming/civcraft/threading/sync/SyncBuildUpdateTask.java:37,69`: `UPDATE_LIMIT = Integer.MAX_VALUE`. За один тик выгружается вся очередь блоков, и при старте чудес или восстановлении случаются просадки TPS. `:87`: каст к `Sign` без проверки.
    *В переписанной версии:* бюджет блоков на тик.

25. **Средний**. `civcraft/src/com/avrgaming/civcraft/threading/tasks/CultureProcessAsyncTask.java:42`: `lock` — поле экземпляра, а каждый час создаётся новый экземпляр. Лок ничего не защищает. `:66`: `expandedAmount = expanded.size() - town.getCultureChunks().size()` не равно числу новых чанков, сообщение о расширении неверно.

26. **Низкий**. Прочее по этому блоку:
    - `civcraft/src/com/avrgaming/civcraft/threading/tasks/UpdateTechBar.java:50-56`: `return` при отсутствии ратуши у одного города пропускает остальные. `i <= blockCount` зажигает лишний блок при 0 %.
    - `threading/timers/BeakerTimer.java:56-58`: сообщение «not generating beakers», но колбы начисляются.
    - `threading/tasks/TradeGoodPostGenTask.java:53`: параметр `start` игнорируется. `:181-203`: обход соседних табличек проверяет только север и восток (N → S возвращает в центр).
    - `threading/timers/ReduceExposureTimer.java:34`: NPE при исчезнувшем резиденте.
    - Мёртвый код: `threading/sync/DamagedStructureTimer.java` (O(n²)), `DateEventTimer`, `SyncCheckForDuplicateGoodies`, `BonusGoodieRepoTimer`, `SyncBonusGoodieUpdateTimer`, `SyncUpdateChunks`, `CannonTowerTask`, `OnTechUpdateSync`, `TemplateSelectQuestionTask`.

27. **Низкий**. `civcraft/src/com/avrgaming/civcraft/main/CivCraft.java:164-170`: при ошибке конфига `arrow_tower.fire_rate` метод `startTimers()` делает `return` и **не запускает ни одного последующего таймера** (фермы, анонсы, правительство, счёт, события, PvP, мобы, арена).

### B. Кастомные предметы, крафт, GUI (`items/`, `lorestorage/`, `loregui/`, `loreenhancements/`)

28. **Высокий** (NMS и производительность). `civcraft/src/gpl/AttributeUtil.java:230-260`. Любая проверка «кастомный ли предмет» (`LoreMaterial.getMID`, `isGUIItem`, `hasEnhancement`) делает `CraftItemStack.asNMSCopy` и **добавляет** пустой тег `AttributeModifiers`. Это вызывается многократно на каждый клик в инвентаре и на каждый удар. Весь пакет жёстко завязан на `v1_7_R4`, а `getCivCraftProperty` парсит `NBTTagString.toString()`, снимая кавычки.
    *В переписанной версии:* `PersistentDataContainer` (namespaced keys) для id, свойств и улучшений. Нативные `AttributeModifier` и Data Components 1.21 (`item_model`, `custom_name`, `lore`, `attribute_modifiers`, `max_damage`, `unbreakable`).

29. **Высокий**. Идентификация по лору.
    - Бонусные гуди опознаются **по лору**: `lore[0] == "Bonus Goodie"` и `lore[1]` = координаты аванпоста (`civcraft/src/com/avrgaming/civcraft/items/BonusGoodie.java:628-648`).
    - Владелец юнита берётся из лора `"Town:<name> §0<id>"` (`civcraft/src/com/avrgaming/civcraft/items/units/UnitMaterial.java:176-205`).

    Лор легко подделать через creative, NBT-редакторы или другие плагины. У гуди нет уникального ID, поэтому дубликаты неразличимы: сверка в `CivGlobal.checkForDuplicateGoodies` лишь чистит лишние рамки.
    *В переписанной версии:* PDC с UUID экземпляра гуди и id города. Лор только косметический.

30. **Высокий**. `civcraft/src/com/avrgaming/civcraft/fishing/FishingListener.java:61-76` в сочетании с `civcraft/data/fishing.yml`. Для ванильных дропов `craftMatId: ''` (пустая строка, не `null`), поэтому ветка `d.craftMatId == null` не срабатывает, `getCraftMaterialFromId("")` возвращает `null`, и `stack` не переприсваивается. Выдаётся **повторно предыдущий предмет** (дубль награды) или `addItem(null)` (NPE). `type_id: 349:3` SnakeYAML (YAML 1.1) читает как шестидесятеричное число **20943**: неверный ID предмета. Обработчик стоит на `MONITOR` без `ignoreCancelled`, то есть выдаёт награду даже при отменённом событии и модифицирует событие на MONITOR.
    *В переписанной версии:* типизированный конфиг (`material: PUFFERFISH`, `custom: mat_mercury`), обработчик на `HIGH` с `ignoreCancelled=true`, замена улова через `event.getCaught()`.

31. **Высокий**. `civcraft/src/com/avrgaming/civcraft/items/units/Unit.java:161-275`. Методы `isWearingFullComposite/Hardened/Refined/BasicLeather` требуют, чтобы **каждый** предмет брони одновременно совпадал со всеми четырьмя id (шлем == нагрудник == …). Всегда `false`. Бонусы скорости за полный кожаный комплект (T1..T4 `leather_speed` из `units.yml`) **никогда не применяются** (`listener/PlayerListener.java:132-146`), а штрафы за металл применяются.
    *В переписанной версии:* проверять слот → ожидаемый id.

32. **Средний** (эксплойт через GUI). `civcraft/src/com/avrgaming/civcraft/lorestorage/LoreGuiItemListener.java:41-60` и `LoreGuiItem.java:86-98`. Действие GUI выполняется для **любого** предмета с NBT `civcraft.GUI` в **любом** инвентаре. Класс действия берётся из NBT через `Class.forName("...loregui."+action)`. `civcraft/src/com/avrgaming/civcraft/loregui/SpawnItem.java:15-27` выдаёт предмет (шифт-клик — полный стак) **без проверки прав**. Игрок в creative (или с любым NBT-редактором) может собрать предмет с `GUI_ACTION=SpawnItem` и получить бесконечные кастомные предметы. GUI-инвентари опознаются по **заголовку** (`LoreGuiItemListener.java:75,89`). Сундук, переименованный на наковальне в заголовок GUI («Admin Item Spawn», «<Категория> Recipes»), считается GUI. Кроме того, `LoreEnhancementArenaItem` пропускает проверку таких инвентарей (`loreenhancements/LoreEnhancementArenaItem.java:60,109`), и в них можно хранить нелегальные или аренные предметы.
    *В переписанной версии:* GUI на `InventoryHolder`-классе, а не на заголовке. Действия привязывать к слоту на сервере (map slot → handler), не к NBT. Проверка прав в каждом действии.

33. **Средний**. `civcraft/src/com/avrgaming/civcraft/lorestorage/LoreCraftableMaterial.java:86-108,221-265`. Ключ формного рецепта строится по массиву длины 10 и совпадает с `event.getInventory().getMatrix()` только благодаря особенностям CB 1.7.10: `getMatrix()` возвращает 10 элементов, а пустые слоты приходят как AIR-mirror, а не `null`. Рецепты не смещаются внутри сетки (3×3 фиксированно) и не работают в сетке 2×2.
    *В переписанной версии:* `ShapedRecipe` с `RecipeChoice.ExactChoice` (сравнение PDC-id) или собственный матчер с нормализацией.

34. **Средний**. `civcraft/src/com/avrgaming/civcraft/lorestorage/LoreCraftableMaterial.java:372-378`: в `onBlockPlaced` переменная `allow` перезаписывается каждым компонентом. Решает последний в порядке `HashMap`, поэтому результат недетерминирован.
    *В переписанной версии:* OR (`AllowBlockPlace` в любом компоненте).

35. **Средний**. `civcraft/src/com/avrgaming/civcraft/items/units/UnitMaterial.java:238-242`: при подборе юнита предмет из слота 9 вытесняется через `inv.addItem(lastSlot)`, остаток при полном инвентаре **теряется**. `:461-481`: при закрытии инвентаря лишние юниты выбрасываются, но `inventory.remove(stack)` удаляет все равные стаки, включая первый, и один юнит пропадает. `:395-401`: после отмены стака обработка продолжается и планируется `DelayMoveInventoryItem`.

36. **Средний**. `civcraft/src/com/avrgaming/civcraft/items/components/Catalyst.java:57`: `enhance.variables.put("amount", ...)` мутирует **синглтон** улучшения из статической карты (`loreenhancements/LoreEnhancement.java:19-28`). `:101`: `n <= chance` даёт шанс `chance+1` %. `loreenhancements/LoreEnhancementDefense.java:73`: `getLevel` возвращает 1 без улучшения (у атаки 0). Из-за этого уровень брони для расчёта «бесплатных» катализаторов сдвинут на единицу.
    *В переписанной версии:* параметры улучшения передавать аргументами, без общего состояния.

37. **Средний**. `civcraft/src/com/avrgaming/civcraft/items/BonusGoodie.java`:
    - `:747`: `BlockCoord.equals(String)` всегда `false`, поэтому `equals` у гуди никогда не срабатывает.
    - `:285`: сравнение UUID через `!=`.
    - `:637-639`: NPE при битом лоре (`getLocationFromHash` может вернуть `null`).
    - `:683`: `inventory.remove(findStack())` удаляет все равные стаки.
    - `:597`: предмет на земле ищется по UUID при загрузке, до загрузки чанка.

38. **Низкий**.
    - `civcraft/src/com/avrgaming/civcraft/items/components/Tagged.java:50`: NPE, если у второго предмета нет тега.
    - `loregui/ShowRecipe.java:151-155`: NPE, если `backInventory` отсутствует в `guiInventories`. `:36-37`: NPE при несуществующем `custom_id` в конфиге.
    - `items/components/BuildCannon.java:20-36`: срабатывает на любое взаимодействие, включая левый клик, и обнуляет весь стак.
    - `lorestorage/LoreStoreage.java:43`: `lore.set(0, ...)` на пустом списке даёт IndexOutOfBounds (класс не используется).
    - `items/ItemDuraSyncTask.java` и `items/components/RegisterItemComponentAsync.java`: мёртвый код.
    - `LoreGuiItemListener.guiInventories` хранит `Inventory` с holder'ом первого игрока (`ShowRecipe.java:168`, `command/admin/AdminCommand.java:141-165`), и один объект инвентаря шарится между игроками.

39. **Средний** (зависит от листенеров вне этой области, но это механика предметов). `listener/CustomItemManager.java:400-489`: soulbound-предметы убираются из дропа и возвращаются в инвентарь задачей через тик после смерти. Если игрок вышел (PvP-лог) до этого тика, предметы **исчезают**. Для переписанной версии: хранить soulbound-предметы в PDC или БД до респавна (`PlayerRespawnEvent`) либо использовать `event.getItemsToKeep()` (Paper).

### C. Юниты и шпионаж (`items/units/`)

40. **Средний**. `civcraft/data/espionage.yml`: у `spy_steal_treasury` и `spy_incite_riots` одинаковый `slot: 6`. `Spy.giveMissionBooks` (`civcraft/src/com/avrgaming/civcraft/items/units/Spy.java:78-100`) кладёт обе книги в слот 6, и одна затирает другую (порядок зависит от `HashMap`). «Incite Riots» не реализована (`items/units/MissionBook.java:583-585`), поэтому игрок может остаться без «Steal Treasury».

41. **Низкий**.
    - `civcraft/src/com/avrgaming/civcraft/items/units/MissionBook.java:296-306`: `getNearestBuildable` может вернуть `null`, и `buildable.getCorner()` даёт NPE. `:387`: `outpost.getGood()` может быть `null` уже **после** выдачи предмета.
    - `:168`: книга удаляется у игрока, если он не шпион (ожидаемо, но без сообщения при `null`).
    - `MissionBook.getMissionFailChance` (`:71-86`): при `intel>0` и 0 онлайн-жителей в целевом городе шанс провала 100 %. Это особенность, её надо либо сохранить, либо осознанно изменить.
    - `civcraft/src/com/avrgaming/civcraft/threading/tasks/EspionageMissionTask.java:94`: в `getNearbyPlayers(..., 600)` передаётся **квадрат** радиуса. Фактический радиус «свидетелей» около 24.5 блока, вероятно, задумывалось 600 блоков. Кроме того, `playerCount--` вычитает самого шпиона, даже если кэш позиций его ещё не содержит (может дать −2 к экспозиции).

### D. Мобы (`mobs/`, модуль `moblib`)

42. **Высокий** (производительность спавна). `civcraft/src/com/avrgaming/civcraft/mobs/timers/MobSpawnerTimer.java`:
    - `:69` вызывает `world.getHighestBlockYAt(...)` **до** проверки `loc.getChunk().isLoaded()` (`:71`). Это синхронная загрузка или генерация чанков на расстоянии 20–69 блоков, 5 раз за вызов.
    - `:91`: `getNearbyEntities` через NMS AABB, радиус 32.
    - `:102-106`: блок `finally` **всегда** возвращает имя в очередь, даже для вышедших игроков (комментарий утверждает обратное), а `PlayerListener.java:115` добавляет имя при каждом входе. Очередь растёт без предела, заполняется оффлайн-именами, и до онлайн-игроков очередь доходит всё реже.
    - `:99`: `break` после первого же онлайн-игрока, то есть 1 игрок раз в 2 с на весь сервер.

    *В переписанной версии:* per-player спавн-тик с бюджетом, только загруженные чанки (`isChunkLoaded(x,z)` до любых обращений), спавн через `World.spawn` с `CreatureSpawnEvent`, лимит через `getNearbyEntitiesByType`.

43. **Высокий**. `civcraft/src/com/avrgaming/civcraft/mobs/MobSpawner.java:85`: `CommonCustomMob.customMobs.put(...)`. Удаление есть только в `command/admin/AdminMobCommand.java:46`. Убитые, деспавненные и выгруженные мобы **никогда не удаляются** из карты: утечка памяти, удерживаются ссылки на NMS-сущности и миры.
    *В переписанной версии:* PDC-метка на сущности, карта по UUID с очисткой по `EntityRemoveFromWorldEvent` (Paper).

44. **Средний**. `civcraft/src/com/avrgaming/civcraft/mobs/CommonCustomMob.java:351`: `biomes.get(biome)` (ключ-энум) при хранении по `biome.name()` всегда даёт `null`. Каждая регистрация создаёт новый список, и остаётся только **последняя** пара (тип, уровень) на биом. Затронуты `BIRCH_FOREST_HILLS_MOUNTAINS`, `ROOFED_FOREST_MOUNTAINS`, `MEGA_TAIGA`, `MEGA_SPRUCE_TAIGA_HILLS` (4 регистрации). См. таблицу биомов в разделе «Механики».

45. **Средний**. `civcraft/src/com/avrgaming/civcraft/mobs/MobSpawner.java:95-104`: после удаления отключённых мобов (`disabledMobs`) размер не перепроверяется, и `random.nextInt(0)` бросает `IllegalArgumentException`. `CommonCustomMob.java:451`: `nextInt(coinMax - coinMin)` упадёт при `min == max`.

46. **Средний**. `civcraft/src/com/avrgaming/civcraft/mobs/Ruffian.java:238-243`: `EntityDamageByEntityEvent` вызывается вручную, но `isCancelled()` игнорируется, и затем идёт `damageEntity(DamageSource.GENERIC, ...)`. Урон по игрокам проходит даже в защищённых зонах и при отменённом событии. `GENERIC` в 1.7 игнорирует броню. Снаряд каждый тик создаёт `createExplosion(power 0)` (`:188`) и летит к **зафиксированной** точке, а не к цели.

47. **Средний**. `civcraft/src/com/avrgaming/civcraft/mobs/listeners/MobListener.java:25-35`: на каждый `ChunkLoadEvent` удаляется **первый** найденный `Monster` или `IronGolem`, после чего `return`. Остальные в том же чанке не трогаются. Удаляются также деревенские големы и именные или питомцевые монстры других плагинов.

48. **Средний**. `moblib/src/com/avrgaming/mob/MobBaseIronGolem.java`: не переопределён тик `e()`, поэтому `customMob.onTick()` **никогда** не вызывается для Бегемота. Проверки «застрял — телепорт к цели», «вошёл в город или лагерь — удалить», «война — удалить» (`mobs/CommonCustomMob.java:226-238`) у Бегемота не работают.

49. **Средний** (NMS-хрупкость). `moblib`:
    - `moblib/src/com/avrgaming/moblib/MobLib.java:196-211` модифицирует приватные карты `EntityTypes.c/d/e/f/g` через рефлексию (метод нигде не вызывается, `onEnable:43` закомментирован).
    - `moblib/src/com/avrgaming/nms/NMSUtil.java` и `civcraft/src/com/avrgaming/civcraft/mobs/CommonCustomMob.java:74-112` используют рефлексию по обфусцированным полям `goalSelector`, `b`, `c` и `GenericAttributes.b/c/d/e`.
    - `moblib/src/com/avrgaming/mob/*.java`: `a(NBTTagCompound)` делает `Class.forName` по строке из NBT, а загруженный `customMob` не получает `setEntity`. Моб «без мозгов» после перезагрузки чанка (на практике не сохраняется, потому что тип не зарегистрирован).
    - `moblib/src/com/avrgaming/mob/MobBaseZombieGiant.java:63-65`: `getCustomMobInterface()` возвращает `null`. `:78-88`: дроп в `die()` срабатывает на любое удаление (despawn, unload).
    - `moblib/src/com/avrgaming/mob/MobBaseWither.java:58`: пустой `e()`, сущность не тикает вообще.
    - `moblib/src/com/avrgaming/moblib/MobLibCommand.java:18`: `args[0]` без проверки даёт AIOOBE.

    *В переписанной версии:* никаких NMS-подклассов. Ванильные сущности плюс PDC-метка «тип/уровень», атрибуты через `Attribute`, поведение через Paper `MobGoals` API. Дропы в `EntityDeathEvent#getDrops()`.

50. **Низкий**.
    - `civcraft/src/com/avrgaming/civcraft/mobs/CommonCustomMob.java:187-203`: антизастревание **телепортирует моба к игроку-цели** (может перенести моба в город или за стену).
    - `mobs/Yobo.java:148-158`: при первом ударе спавнятся 4 «Angry Yobo» того же уровня со своими дропами и монетами. После смерти цели флаг сбрасывается, и цикл можно повторять: ферма лута.
    - `CommonCustomMob.java:486-490`: `setType(null)` повторно добавляет общие дропы.
    - `MobComponentDefense` обнуляет урон, если он меньше `defense` (`mobs/components/MobComponentDefense.java:22-25`): слабое оружие вообще не ранит.

### E. Случайные события (`randomevents/`)

51. **Высокий**. `civcraft/src/com/avrgaming/civcraft/randomevents/RandomEventSweeper.java:8-66`: асинхронный таймер раз в 10 с (`main/CivCraft.java:154`) проверяет требования и выполняет success/failure-компоненты **асинхронно** (`sendMessage`, `deposit`, SessionDB, `HandlerList.unregisterAll`). Статический `LinkedList events` пишется из разных потоков (`register` из асинхронного таймера событий и из `load`).

52. **Средний**. `civcraft/src/com/avrgaming/civcraft/randomevents/components/KillMobs.java:36-43` считает убийства нужного типа **любым игроком на всём сервере** (а не жителями города и не в городе). `getLastDamageCause()` может быть `null` (NPE). Счётчик не сохраняется в `componentVars` и обнуляется при рестарте. `BlockBreak.java:37-66`: `MONITOR` без `ignoreCancelled` засчитывает отменённую ломку. Состояние `blockBroken/brokenByTown` не сохраняется. Если блок сломал чужак, событие становится невыполнимым. NPE, если `resident == null`. В `data/randomevents.yml` у Slime Plague сообщение «Slaughter 20», а требование `amount: '4'`.

53. **Средний**. `civcraft/src/com/avrgaming/civcraft/randomevents/RandomEventTimer.java:47`: `rand.nextInt(1) == 0` **всегда** истина, «подбрасывание монеты» всегда выбирает последнее из равновероятных событий (порядок `HashMap`). `:39`: `r <= chance` при `nextInt(1000)` даёт (chance+1)/1000.

54. **Низкий**.
    - `civcraft/src/com/avrgaming/civcraft/randomevents/RandomEvent.java:131-138`: NPE, если `saved_messages` равен `null`.
    - `randomevents/components/SpawnMobs.java:30`: `nextInt(0)` у города без чанков.
    - `PickRandomBlock.java:63-75`: `break` выходит только из цикла по Y, и выбирается последний подходящий столбец. До 10 чанков грузятся и генерируются синхронно (`coord.getChunk()`).
    - `PickRandomLocation.java:43-44`: мир захардкожен, `getHighestBlockYAt` синхронно генерирует чанк.
    - `getUnhappiness/getHappiness/getHammerRate` (`RandomEvent.java:344-437`) делают запрос в SessionDB на каждый вызов.

### F. Популяторы, торговые товары, `tasks/`, `recover/`

55. **Высокий** (админ-инструмент с разрушительным эффектом). `civcraft/src/com/avrgaming/civcraft/tasks/TradeGoodSignCleanupTask.java:66-118`:
    - `tg.getCoord()` мутируется на месте (смещение X/Z, `Y=0…256`), и в памяти портятся координаты всех торговых товаров.
    - Столбец **от Y=0 до 255 заливается воздухом**, включая бедрок, что даёт дыру в пустоту.
    - Соседний с севера столбец (`:92`) обнуляется **безусловно**.

    Запускается `/dbg` синхронно на всех товарах сразу.
    *В переписанной версии:* не переносить. Если нужен, то работать с копией координат и ограниченным диапазоном Y.

56. **Средний**. `civcraft/src/com/avrgaming/civcraft/populators/TradeGoodPreGenerate.java`:
    - `:173,189`: выбор типа товара использует **несидированный** `Random`. Позиции детерминированы (seed), а типы меняются при каждом рестарте для ещё не сгенерированных чанков. Комментарий «save results to a file» не реализован.
    - `:222-231`: копипаст. Проверка дубликатов водных товаров использует `validLandGoods` и перевыбирает `landPick`, поэтому соседние одинаковые водные товары не исключаются.
    - `:175`: при пустом наборе (или если ни один товар не прошёл порог редкости) `nextInt(0)` падает.
    - `:199-200`: окно проверки дублей −4..3 несимметрично.
    - `config/ConfigTradeGood.java:101-109`: `compareTo` сравнивает `Double` через `==` (ссылки). `TreeSet` работает «случайно правильно»: все rarity в `goods.yml` не заданы и равны 1.0, но это разные объекты. При исправлении на сравнение значений `TreeSet` схлопнет все товары одинаковой редкости в один.

57. **Средний**. `civcraft/src/com/avrgaming/civcraft/populators/TradeGoodPopulator.java:50-128`: в `BlockPopulator` меняются соседние блоки (табличка на соседней позиции может оказаться в соседнем чанке, что вызывает каскадную генерацию). Выполняются `CivGlobal.addTradeGood`, `addProtectedBlock` и SQL-сохранения. `:103-110`: `data.setFacingDirection(direction)` без `state.setData(data)`, и направление таблички не применяется. `:69-74`: весь бедрок выше точки вырезается «от дублей». Популятор регистрируется до `CivSettings.init` (`main/CivCraft.java:254`).
    *В переписанной версии:* `BlockPopulator` с `LimitedRegion` (только внутри региона). Регистрацию товара выполнять после генерации (`ChunkLoadEvent` с флагом `isNewChunk` или отложенная очередь).

58. **Средний**. `civcraft/src/com/avrgaming/civcraft/recover/RecoverStructuresAsyncTask.java`: несмотря на имя, запускается **синхронно** (`command/admin/AdminRecoverCommand.java:308,313`) и в одном тике сравнивает с шаблоном блоки всех структур сервера, а это миллионы `getBlock` и загрузки чанков. `recover/RecoverStructureSyncTask.java:62-96` чинит всё за один тик.
    *В переписанной версии:* пошаговое восстановление с бюджетом на тик.

### G. civregister

59. **Критический** (безопасность). `civregister/src/com/avrgaming/civregister/ResetPasswordCommand.java:45-47`: если аргумент не «yes», выводится ошибка, но `return` нет, и **пароль всё равно сбрасывается** (`/resetpassword no` сбросит пароль). Новый пароль генерируется `java.util.Random` (6–9 символов) и хешируется как `SHA1(salt + pwd)` с глобальной солью (`SQL.java:84-86`). Консоль может сбросить пароль любого ника.

60. **Высокий** (безопасность). `civregister/src/config.yml`: в репозитории лежат учётные данные MySQL (`cake` / пароль) и соль CakePHP. `civregister/src/com/avrgaming/civregister/RegCommand.java` + `SQL.java:47-64`: код верификации не ограничен ни попытками, ни временем жизни. `UPDATE ... WHERE verifyCode=?` без проверки `verified=false` позволяет перепривязать уже верифицированный аккаунт перебором кодов. JDBC выполняется в **основном потоке**, а одно статическое соединение `context` делится без синхронизации.
    *В переписанной версии:* веб-регистрация через одноразовый короткоживущий токен (HMAC) и асинхронный JDBC-пул (Hikari). Пароли не показывать в игре, сброс делать ссылкой на e-mail. Хэш — bcrypt или argon2 (на стороне сайта). Модуль собран против `bukkit-1.6.4`.

### H. civcraft_dynmap

61. **Средний**. `civcraft_dynmap/src/com/avrgaming/dynmap/civcraft/CivCraftUpdateTask.java:284-285`: старые `AreaMarker` из прошлых карт не удаляются (`deleteMarker()`) при замене на новые карты. Полигоны удалённых или переименованных городов и старые фрагменты культуры остаются на карте до рестарта. `:618-621`: `return` при `displayName == null` обрывает обновление остальных структур. Полное перестроение всех полигонов и описаний всех структур каждые 2 с **в основном потоке** (`DynmapCivcraftPlugin.java:53-54`). Для культуры с «дырками» трассировка контура рисует неверную фигуру (прямо отмечено автором, `:487-492`). Плагин напрямую читает статический `CivGlobal`.
    *В переписанной версии:* событийная инвалидация (на изменение границ), расчёт полигонов асинхронно по снапшоту, удаление отсутствующих маркеров, поддержка BlueMap/Dynmap/squaremap через адаптер.

---

## Механики

### 1. Планировщик (все таймеры из `civcraft/src/com/avrgaming/civcraft/main/CivCraft.java:128-207`)

| Имя | Поток | Период | Что делает |
|---|---|---|---|
| SQLUpdate | async, однократно | — | фоновая очередь сохранений SQL |
| SyncBuildUpdateTask | sync | 1 тик | ставит блоки из очереди строек (`SimpleBlock`). Спец-типы: COMMAND → воздух, LITERAL → табличка с 4 строками. Увеличивает `savedBlockCount` |
| SyncLoadChunk / SyncGetChestInventory / SyncUpdateInventory / SyncGrowTask | sync | 1 тик | «мост» async → sync: лимиты 2048, 20, 200 и 200 запросов за тик; `ReentrantLock` + `Condition`, таймаут ожидания 5 с |
| PlayerLocationCacheUpdate | sync | 10 тиков | обновляет кэш позиций, до 20 игроков за проход (для асинхронных proximity-проверок) |
| RandomEventSweeper | async | 10 с | проверка требований и истечения случайных событий |
| UpdateEventTimer | async | 1 с | `onUpdate()` всех активных структур и чудес; для треммелей — `TrommelAsyncTask`; для лагерей — `CampUpdateTick` |
| RegenTimer | async | 5 с | `processRegen()` структур и чудес |
| BeakerTimer | async | 60 с | каждой цивилизации `addBeakers(civ.getBeakers()/60)` при активном исследовании, иначе `processUnusedBeakers()` |
| UnitTrainTimer | sync | 1 с | `Barracks.updateTraining()` |
| ReduceExposureTimer | async → sync | 5 с | снижает шпионскую экспозицию на 5 (минимум 0) у всех не на миссии |
| arrowTower (ProjectileComponentTimer) | sync | `war.yml arrow_tower.fire_rate` (1.0 с) | выстрелы башен (если `towersEnabled`) |
| ScoutTowerTask | async | 1 с | `ScoutTower.process` |
| arrowhomingtask | sync | 5 тиков | самонаведение стрел башен: пересчёт вектора до цели, пока дистанция больше `homing_stop_distance` = 10 |
| FarmCropCache / FarmGrowthTimer | sync / async | 30 с / `farm.grow_tick_rate` | рост ферм |
| announcer | async | 1 ч | подсказки из `tips.txt` каждые 5 мин |
| ChangeGovernmentTimer | async | 60 с | выход из анархии через `anarchy_duration` = 24 ч (`governments.yml`); без записи в SessionDB → `gov_tribalism` |
| CalculateScoreTimer | async | 60 с | подсчёт очков |
| PlayerProximityComponentTimer | async | 1 с | обновление proximity-компонентов по кэшу позиций |
| EventTimerTask | async | 5 с | календарные события (см. п. 2) |
| PlatinumManager | async | 5 с | платина (если включена) |
| PvPLogger | sync | 5 с | — |
| WindmillTimer | sync | 60 с | `Windmill.processWindmill()` вне войны |
| EndGameNotification | async | 1 ч | — |
| StructureValidationChecker / Punisher | async | однократно через 120 с / 1 ч | — |
| SessionDBAsyncTimer | async | 10 тиков | — |
| pvptimer | async | 30 с | — |
| MobSpawner | sync | 2 с | спавн кастомных мобов (п. 10) |
| ArenaTimer / ArenaTimeoutTimer | sync | 30 с / 1 с | — |

Для переписанной версии: мост async → sync (`CivAsyncTask` + `Sync*`) заменить на `CompletableFuture` и `BukkitScheduler#runTask`. Ни одна операция с миром или инвентарём не должна выполняться вне основного (регионного) потока.

### 2. Календарные события (`event/EventTimer.java`, таблица `TIMERS`: name, nextEvent, lastEvent)
- `EventTimerTask` раз в 5 с: если сейчас позже `next`, выставляет `last = now` и `next = getNextDate()`, сохраняет, вызывает `process()`. После простоя сервера пропущенное событие выполняется **один раз** сразу после старта.
- **daily** — в `civ.yml global.daily_upkeep_hour` = 20:00 (серверное время). Сначала ждёт первого завершения обработки культуры после старта (опрос раз в 10 с), затем синхронно запускает `DailyTimer`.
- **hourly** — следующий «ровный час» плюс `global.hourly_tick` = 3600 с. Асинхронно `CultureProcessAsyncTask` и `EffectEventTimer`, синхронно `SyncTradeTimer` и `CampHourlyTick`.
- **spawn-regen** — `global.regen_spawn_hour` = 0 (полночь): `regenerateChunk` всех чанков культуры админ-цивилизаций, которые не являются городскими чанками. Мир `world`, 1 чанк за тик.
- **war** — день недели `war.time_day` = 7 (суббота, `Calendar.SATURDAY`), час `war.time_hour` = 16. `War.setWarTime(true)`, затем `WarEndCheckTask` раз в 1 с до `War.getEnd()`.
- **repo-goodies** — каждые `trade_goodie_repo_days` = 5 дней (от момента расчёта, минуты и секунды обнуляются). У всех городов снимаются гуди, каждый гуди возвращается в рамку своего аванпоста, глобальное сообщение.
- **random** — каждые 12–24 ч (случайно, `rand.nextInt(12)+12`) случайные события для городов (п. 11).

### 3. Ежедневный расчёт (`threading/timers/DailyTimer.java`, sync, под `ReentrantLock`)
1. **Сбор налогов**. Для каждой не-админ цивилизации и каждого её города: `collectPlotTax()` + `collectFlatTax()` = `townTotal`. Задуманная доля цивилизации — `townTotal × incomeTaxRate` цивилизации-получателя (`getDepositCiv`), но из-за бага A.10 цивилизация получает 0. Город получает `depositTaxed(townTotal − taxesToCiv)`. Цивилизация «на продаже» (`isForSale`) снимает агрессивные войны.
2. **Содержание городов**: `t.payUpkeep()`; при долге `incrementDaysInDebt()`.
3. **Содержание цивилизаций**: сначала чудо Колосс (`processCoinsFromCulture`) и Нотр-Дам (`processPeaceTownCoins`), затем `civ.payUpkeep()`; при долге `incrementDaysInDebt()`.
4. **Льготные счётчики выселения** у резидентов (`decrementGraceCounters`). Всем жителям городов выдаётся платина `inTownDuringUpkeep` = 5 (раз в день).
5. `onDailyEvent()` всех структур и чудес.
6. Асинхронно `EndGameCheckTask` (проверка условий победы).

### 4. Часовой тик (`EffectEventTimer`, `CultureProcessAsyncTask`, `SyncTradeTimer`)
- Сброс `lastTaxesPaidMap` у цивилизаций. Для каждой активной структуры в городе с ратушей вызывается `onEffectEvent()`. Эффекты `generate_coins` → `Cottage.generateCoins`, `process_mine` → `Mine.process_mine`.
- **Культура**: город без ратуши получает только предупреждение. Иначе `addAccumulatedCulture(round(town.getCulture().total))`. Неиспользованные колбы (если ничего не исследуется) конвертируются в культуру по курсу `culture.yml beakers_per_culture` (из-за бага A.20 начисляются сами колбы), после чего колбы обнуляются.
- **Торговля**: `payment = getTownBaseGoodPaymentViaGoodie(town) × town.getTradeRate()`. Налог цивилизации — `payment × incomeTaxRate`, городу остаётся остальное. Перед расчётом чистятся дубли гуди в рамках.
- **Расширение культуры** (`CultureProcessAsyncTask`). BFS от «культурного центра» города по 4 соседям в пределах манхэттенского расстояния `cultureLevels[level].chunks`. Новые чанки захватываются, если `dist+1 < chunks`. Чужие городские чанки не трогаются никогда. Спорный культурный чанк переходит к узлу с большей «силой» (`getPower()`), при равенстве — к городу с большей накопленной культурой. Для своих городских чанков дистанция 0. Потом «лишние» чанки обрезаются (`trimCultureChunks`), пересчитывается список соседних городов (`townTouchList`) и выполняется «переворот» структур, центр которых оказался в чужой культуре (`processStructureFlipping`). Затем проверка истёкших вассальных отношений.

### 5. Стройка (`BuildAsyncTask`, `PostBuildSyncTask`)
- Блоки идут «снизу вверх, слой за слоем»: `y = n / (sx·sz)`, `z = (n / sx) mod sz`, `x = n mod sx`. За «тик стройки» ставится `blocks_per_tick` блоков плюс «лишние» блоки от сверхнормативных молотков (`extra_blocks = blocksPerHammer × extra_hammers`). Затем пауза `getBuildSpeed()` мс, которая пересчитывается каждые ≤10 с при изменении скорости молотков.
- Двери откладываются до пост-обработки. Блоки с `y == 0` регистрируются как неразрушаемые, остальные как разрушаемые. Прогресс сохраняется каждые 5 с. Каждые 10 % прогресса — сообщение (для чуда глобальное).
- **Чудо** приостанавливается (сообщение раз в 30 или 10 мин), если город завоёван (`motherCiv != null`), строится другая структура или нет ратуши. Если чудо уже построено кем-то ещё, стройка отменяется с возвратом 50 % стоимости. Разрушенное во время стройки чудо удаляется.
- **PostBuild**: командные блоки шаблона `/tradeoutpost`, `/techbar id=N`, `/techname`, `/techdata`, `/itemframe id=N` (рамки гуди в ратуше), `/respawn`, `/revive`, `/control` (контрольные точки), `/towerfire` (дуло башни), `/sign` (табличка-действие), `/chest id=N` (сундуки структуры: ID 0 = семена мельницы, 1 и 2 = вход и выход треммеля). Для ратуши обновляется тех-бар (зелёная и чёрная шерсть по проценту исследования, таблички «Researching» и «Percent Complete»).

### 6. Кастомные материалы (`data/materials.yml`, 186 записей)
- **Идентификация**: NBT-компаунд `civcraft` с полем `mid` (id материала) и `name`. Дополнительные строки: первая — курсивом категория, далее лор компонентов. GUI-предметы помечаются `civcraft.GUI`, `GUI_ACTION`, `GUI_ACTION_DATA:<k>`. Улучшения хранятся в `item_enhancements.<Name>{name, level}`.
- **Крафт**: для каждого `craftable` регистрируется Bukkit-рецепт на базовые материалы, а в `PrepareItemCraftEvent` строится ключ сетки (формный — по позициям, бесформенный — отсортированные `id:count`). Совпадение с кастомным рецептом даёт кастомный результат в количестве `amount`. Кастомный ингредиент в ванильном рецепте или ванильные ингредиенты в кастомном рецепте дают пустой результат. Технология (`required_techs`) проверяется в `CraftItemEvent`. **Золотые яблоки не крафтятся вообще.** Ванильные предметы из `techItems` требуют технологию. `removed_recipes` (43 шт.: ванильные мечи, броня, лук, инструменты, хоппер, TNT, эндер-сундук, зачарованный стол 116 и т. п.) удаляются, а соответствующие ванильные предметы вырезаются из дропа мобов. Каждые 100 скрафченных предметов дают платину `craft100Items` = 1. Первый лагерь даёт `buildCamp` = 150, первая цивилизация — `buildCiv` = 200.
- **Тиры материалов** (почти все бесформенные):
  - **T1 (сжатия 9→1)**: Carved Leather (9 кожи), Refined Stone (9 булыжника), Packed Feathers, Crafted String, Refined Slime (9 × T1 SlimeBall), Crafted Reeds, Crafted Sticks, Refined Sulphur, Compacted Sand, Forged Clay, Refined Sugar, Refined Wart, Refined Wood.
  - **Руды**: Forged Chromium (9 Chromium Ore), Forged Tungsten (9 Tungsten Ore). Mercury (1 рыба-фугу), T1 SlimeBall (3 Refined Sugar + 2 костной муки).
  - **T2**: Crafted Leather, Decorative Jewels (2 алмазных блока), Crushed Stone, Jewelry Gold (9 золотых блоков), Milled Lumber, Woven Mesh Patch, Steel Ingot (3 железных блока + 3 угольных + Sulphur), Varnish, Sticky Resin, Clay Molding, Chromium Ingot, Aged Wood Stave, Woven Threading, Steel Sword Blade и Hilt, Steel Plate, Mercury Bath (9 ртути), Industrial Diamond (3 алмазных блока).
  - **T3**: Leather Straps, Clay Steel Cast, Reinforced Braid, Carbide Steel Ingot, Longbow Stave, Feathered Lining, Carbide Molding, Smithy Resin, Tungsten Ingot, Clay Tungsten Casting, Masonry Mortar, Compacted Stone, Carbide Sword Hilt, Blade и Plate.
  - **T4**: Artisan Leather, Tungsten Plate, Tungsten Sword Blade и Hilt, Composite Bow Stave и String, Cannon Wheel, Cannon Barrel Part.
  - **Кристаллы** (Common, Uncommon, Rare, Legendary; «Metallic» и «Ionic»): 9 фрагментов дают кристалл. Фрагменты не крафтятся, выпадают только с мобов.
- **Снаряжение** (атака и защита — абсолютные значения, `NoVanillaDurability`; `DurabilityOnDeath` 10 %: при смерти предмет теряет 10 % max-прочности, на нуле уничтожается):

  | Тир | Меч (Attack) | Лук (Ranged) | Металл: шлем/нагр/поножи/ботинки (Defense) | Кожа: шлем/нагр/поножи/ботинки | Технологии |
  |---|---|---|---|---|---|
  | 1 | Training 4, Stone Shank 5, Iron Shortsword 12 (`tech_metal_casting`) | Hunting 18 (`tech_archery`) | Iron 2.5/3.5/2.5/1.5 (`tech_blacksmithing`) | Leather 2/3/2/1 | — |
  | 2 | Steel Longsword 18 (`tech_sword_smithing`) | Recurve 24 (`tech_fletching`) | Steel 4/5/4/3 (`tech_alloys`) | Refined 3.5/4.5/3.5/2.5 (`tech_leather_refinement`) | — |
  | 3 | Carbide Steel 24 (`tech_folded_steel`) | Longbow 30 (`tech_tillering`) | Carbide 5.5/6.5/5.5/4.5 (`tech_chemical_bonding`) | Hardened 5/6/5/4 (`tech_tanning`) | — |
  | 4 | Tungsten Broadsword 30 (`tech_tempering`) | Marksmen 36 (`tech_laminates`) | Tungsten 7/8.5/7/5.5 (`tech_forging`) | Composite 6.5/8/6.5/5 (`tech_composites`) | — |

  Кожаная броня T2–T4 окрашена (AB8618, 339933, FF0000) и не отмывается в котле (`NoCauldronWash`).
  Инструменты ванильные с `Attack 0.5` и `RepairCost` (100–2000; компонент пустой).
  Особые предметы:
  - Royal Crown (3 Jewelry Gold, 2 Jewels, 1 Proof of Leadership).
  - Proof ← 4 Badge ← 9 Token of Leadership. Все три помечены `Tagged`: токены получают метку лагеря, и крафт требует одинаковую метку у всех ингредиентов.
  - Chieftain's Headdress.
  - Флаг основания цивилизации (Mortar + Crown + Compacted Stone; soulbound).
  - Camp (Milled Lumber + угольный блок + Headdress).
  - War Camp (Mortar + Lumber + Compacted Stone; только лидер или советник и только во время войны).
  - War Cannon (Barrel Part + Wheel; только во время войны).

  «Ванильные» переопределения: Hopper (`tech_machinery`, Carbide Ingot + сундук), Sticky Piston, Magma Cream, Lead ×2, Dispenser, TNT (2 Sulphur + Sand), Ender Chest. Кастомные предметы ставить нельзя, кроме помеченных `AllowBlockPlace` (Hopper, TNT, Ender Chest).
- **Боевые формулы** (компоненты):
  - `Attack`: урон = value + уровень улучшения атаки × `attack_catalyst_multiplier` (1.0). Без технологии у игрока урон делится на 2, минимум 0.5. Ванильный урон и чары игнорируются, урон выставляется абсолютно.
  - `RangedAttack`: урон = value × min(1, |v|²/6), где v — скорость стрелы. Без технологии ÷2, минимум 0.5. Лук нельзя натянуть и выстрелить в любой металлической броне.
  - `Defense`: урон −= (value + уровень × `defense_catalyst_multiplier`), без технологии защита ÷2, минимум 0.5. Применяется по каждому элементу брони.
  - `MaxHealth` и `MoveSpeed` задаются через NBT-атрибуты.
  - Скорость от брони (`units.yml`): полный комплект кожи T1–T4 ×1.05/1.10/1.15/1.20 (из-за бага B.31 не работает). Любая железная, кольчужная, золотая или алмазная часть ×0.95/0.90/0.85/0.80.

### 7. Улучшения и катализаторы (`loreenhancements/`, компонент `Catalyst`)
- Катализаторы по тирам:
  - Attack: +1 уровень, шанс 50.
  - Defense: +0.25 уровня, шанс 66. Работают на броне своего тира: кожаной и металлической.
  - Рецепт: кристалл своего тира + алмаз (T1) или Industrial Diamond + Mercury Bath.

  Первые `free_catalyst_amount` = 3 уровня проходят гарантированно, дальше успех при `rand(0..99) ≤ chance` (до `extra_catalyst_amount` = 3 бонус `extra_catalyst_percent` = 0). Каждое улучшение меняет имя на «(+N)» и строку лора.
- `Punchout` (покупка за 50 000, `enchantments.yml`): при ударе по блоку структуры с базовым уроном ≤1 с шансом 51 % добавляет 1–5 урона.
- `SoulBound`: предмет не выпадает при смерти и возвращается в тот же слот.
- `ArenaItem`: при открытии и закрытии инвентаря вне арены такие предметы удаляются. «Нелегальные» предметы (их технологию ещё никто на сервере не изучал) удаляются у всех, кроме OP.
- Покупные ванильные чары: Fire Aspect 10 000, Fire Protection 20 000, Flame 10 000.

### 8. Юниты (`units.yml`, `items/units/`)
- Юнитов всего два:
  - **Spy**: `tech_nationalism`, стоимость 5 000, 500 молотков, лимит 1 на город, предмет 381.
  - **Settler**: 25 000, 500 молотков, без лимита, предмет 330.

  Обучаются в казармах (`UnitTrainTimer`). Готовый юнит кладётся в сундук казарм без стака. Параметры `archer_damage` и `warrior_damage` в `units.yml` не используются кодом (классы archer, knight и т. п. в этой версии отсутствуют).
- Правила предмета юнита: только один юнит у игрока, он всегда перемещается в 9-й слот хотбара. Юнит привязан к городу и цивилизации (лор «Town:…»), чужая цивилизация использовать его не может. Юнит нельзя крафтить, ставить или использовать правой кнопкой.
- **Settler**: soulbound, NBT `owner_civ_id`. ПКМ проверяет, что до ратуш всех городов не меньше `town.min_town_distance` = 150 блоков, затем валидирует позицию `s_townhall` и запрашивает имя нового города.
- **Spy**: при получении выдаются книги миссий в заданные слоты, при потере книги удаляются. При смерти шпиона выпадает книга «Mission Reports» с логом миссий его города. Нельзя использовать во время войны, раньше чем через `spy_register_time` = 1440 мин после регистрации, раньше чем через `spy_online_time` = 10 мин онлайна. Цель — культура чужой (не админской) цивилизации, в casual-режиме только враждебной или воюющей. Миссия длится `length` секунд (асинхронно раз в 1 с). Экспозиция растёт на `exposure_per_second` = 1, на `exposure_per_player` = 2 за каждого другого игрока в радиусе ≈24.5 блока (в `getNearbyPlayers` передаётся 600 как квадрат радиуса), на `exposure_per_scout` = 5 за каждую скаут-башню цели в `scout_tower.range` = 400. Город цели проверяет пороги 0.4, 0.8, 0.9 и 1.95 (`processSpyExposure`). Провал раскрывает шпиона («INTERNATIONAL INCIDENT»), и юнит уничтожается. Выход из границ проваливает миссию. После завершения синхронно `performMission`, оплата из казны города.
  - Шанс провала = 1 − (1 − fail) × min(1, онлайн цели / intel); компрометации — аналогично с `compromise`.
  - Миссии (стоимость / fail / compromise / intel / длительность в секундах / радиус в блоках / слот):
    - Investigate Town: 10 000 / 0.1 / 0.01 / 0 / 30 / – / 7. Книга-отчёт: казна, молотки, культура, рост, колбы, исследование, содержание.
    - Steal Treasury: 20 000 / 0.3 / 0.25 / 2 / 140 / 50 от угла ратуши / 6. Крадёт 20 % казны, бафф `buff_dirty_money` снижает fail.
    - Incite Riots: не реализована.
    - Poison Granary: 20 000 / 0.2 / 0.1 / 0 / 60 / 50 / 4. Отравление на 3–50 «тиков» (часов), с шансом 5 % все коттеджи теряют уровень, бафф `buff_espionage`.
    - Pirate: 1 000 / 0 / 0 / 0 / 60 / 10 от башни аванпоста / 3. Предмет-гуди выпадает под ноги.
    - Sabotage: 250 000 / 0.4 / 0.5 / 4 / 300 / 50 / 2. Уничтожает ближайшую завершённую структуру (не ратушу); для чуда 500 000 и fail 0; бафф `buff_sabotage`.
- Прочие боевые предметы: War Camp (точка спавна цивилизации во время войны, повторная постройка не раньше `warcamp.rebuild_timeout`), War Cannon.

### 9. Бонусные гуди и торговые товары
- **Генерация** (`TradeGoodPreGenerate`, `goods.yml`): сетка по чанкам от −625 до 625 с шагом `chunks_min` = 15 и случайным смещением в пределах `chunks_max − chunks_min` = 25 по обеим осям (seed 12345). Для каждой точки выбирается сухопутный и водный кандидат среди товаров, допустимых по полусфере. Полусферы задаются в **координатах чанков**, порог ±200 чанков (±3200 блоков): north z < −200, south z > 200, east x > 200, west x < −200, equator |z| ≤ 200 и диагонали. В радиусе ±4 чанка одинаковые товары запрещены. Редкость (`rarity`, по умолчанию 1.0): выбирается минимальная прошедшая порог `rand(0..99) < rarity·100`, среди неё случайный товар.
- **Товары**:
  - Суша 125: Papyrus, Silver, Grapes, Olives, Cotton, Corn, Copper, Spice.
  - Суша 250: Pelts, Horses, Ivory, Incense, Herbs, Limestone, Marble.
  - Суша 375 (с полусферой): Oil (N), Poison Ivy (NE), Hemlock (SW), Guarana (EQ), Gems (SE), Coffee (NW), Tobacco (S).
  - Вода: Crabs 250, Salmon 250, Shrimp 250, Tuna 250, Pearls 375 (N), Whale 500 (S).
  - Каждый товар даёт набор баффов (`buff_*`) из `buffs.yml`. Не больше `trade_good_multiplier_max` = 3 одинаковых.
- **Популятор**: при генерации чанка из выбранной точки в центре чанка (x·16+8, z·16+8) на поверхности строится столб бедрока высотой 3. Если поверхность — вода, ставится водный товар. К верхнему блоку крепится табличка «Trade Resource / ---- / <имя>». Блоки защищены (`TRADE_MARKER`).
- **Гуди** (`BonusGoodie`): предмет товара (ванильный материал из конфига) с лором «Bonus Goodie», координатами аванпоста, названиями и описаниями баффов. Живёт в одном из мест: рамка аванпоста, рамка ратуши (даёт баффы городу), инвентарь (сундук или игрок), предмет на земле. Положение сохраняется в `GOODIE_ITEMS`. Раз в 5 дней все гуди возвращаются. Пиратская миссия крадёт гуди.

### 10. Кастомные мобы (`mobs/`, `moblib`)
- **Спавн**: каждые 2 с берётся один игрок из очереди. Пять попыток в случайной точке ±(20..69) блоков по X и Z на высоте поверхности +3. Условия: мир с монстрами, чанк загружен, не городской чанк, под точкой не вода и не лава, в радиусе 32 не больше 5 существ (`EntityCreature`). Тип и уровень выбираются по биому (таблица ниже), отключённые мобы исключаются.
- **Общее поведение**:
  - Имя «<Уровень> <Тип>». Иммунитет к удушью (моб выталкивается наверх +4), контакту, падению, огню, лаве, утоплению, взрывам, молнии, магии. Не горит на солнце, нельзя привязать поводком.
  - Каждые 90 тиков: если моб за это время сдвинулся меньше чем на 0.5 при наличии цели, он **телепортируется к цели**. Если оказался в городе или лагере, удаляется. Во время войны удаляется.
  - Ванильный лут отключён. Общий дроп: кость 10 %, сахар 10 %, порох 25 %, картофель 10 %, морковь 10 %, уголь 10 %, нить 10 %, слизь 2 %.
  - Монеты выпадают опытом: сфера опыта с min..max, при подборе опыт = монеты (`DisableXPListener`).
  - `MobComponentDefense` вычитает защиту из урона; если остаётся < 0.5, урон 0 и сообщение «Our attack was ineffective».
- **Типы** (HP / атака / защита / скорость × / монеты / основные дропы 5–15 %):
  - **Yobo** (зомби):
    - Lesser: 20 / 8 / 3.5 / 1.3 / 1–25.
    - Greater: 25 / 13 / 10 / 1.4 / 10–50.
    - Elite: 30 / 15 / 16 / 1.5 / 20–80.
    - Brutal: 40 / 20 / 16 / 1.5 / 20–150.
    - Дропы: Metallic crystal fragment своего тира (5 %) и материалы тира. Откидывание 0.99. При первом уроне становится агрессивным и вызывает 4 «Angry Yobo» того же уровня.
  - **Angry Yobo** (зомби-ребёнок): 10/5/3.5, 15/8/10, 20/13/16, 30/18/20. Исчезает, когда теряет цель.
  - **Behemoth** (железный голем): HP 75/125/150/160, защита 3.5/10/16/20, базовая скорость 0.15 × 1.3…1.6, отбрасывание 1.0. Ionic-фрагменты и пластины. Атака ванильная.
  - **Savage / «Cannibal»** (зомби-свиночеловек): 10/5, 20/10, 40/15, 80/25, защита 3.5/10/16/20, скорость 0.2 × 1.8…2.0, радиус преследования 10 (в `MobBasePigZombie` дополнительно переопределён ванильный `findTarget` на 3 блока с проверкой видимости).
  - **Ruffian** (ведьма, дальний бой): HP 10/15/20/30, урон снаряда 15/20/25/32 в радиусе 6. Снаряд — «огненный шар», летит 1 блок за тик в точку, где был игрок, с фейерверками. Дропает кожу 40 % (Lesser) и `mat_refined_sulphur` 15/25/35/50 %.
  - **Yobo Boss** (гигант, HP 5000, атака 200, защита 9): в биомах не спавнится. При уроне вызывает 6 Angry Yobo.
  - Заглушки без логики: LoboZombie (используется PvP-логгером), Grendal, AngryBird.
- **Биомы → моб** (фактически, с учётом бага D.44):
  - Yobo L: PLAINS, FOREST, BIRCH_FOREST, BIRCH_FOREST_HILLS.
  - Yobo G: SUNFLOWER_PLAINS, FLOWER_FOREST, BIRCH_FOREST_MOUNTAINS, FOREST_HILLS.
  - Yobo E: EXTREME_HILLS, EXTREME_HILLS_PLUS, ROOFED_FOREST.
  - Yobo B: EXTREME_HILLS_MOUNTAINS, EXTREME_HILLS_PLUS_MOUNTAINS.
  - Behemoth L: FROZEN_RIVER, FROZEN_OCEAN, COLD_BEACH, COLD_TAIGA.
  - Behemoth G: COLD_TAIGA_HILLS, COLD_TAIGA_MOUNTAINS, ICE_MOUNTAINS.
  - Behemoth E: ICE_PLAINS. Behemoth B: ICE_PLAINS_SPIKES.
  - Savage L: DESERT, DESERT_HILLS, DESERT_MOUNTAINS.
  - Savage G: SAVANNA и её варианты.
  - Savage E: MESA, MESA_PLATEAU, MESA_PLATEAU_FOREST, MEGA_SPRUCE_TAIGA, MEGA_TAIGA_HILLS.
  - Savage B: MESA_BRYCE, MESA_PLATEAU_MOUNTAINS, MESA_PLATEAU_FOREST_MOUNTAINS.
  - Ruffian L: JUNGLE, JUNGLE_EDGE, JUNGLE_EDGE_MOUNTAINS, SWAMPLAND, MEGA_TAIGA.
  - Ruffian G: JUNGLE_HILLS, MEGA_SPRUCE_TAIGA_HILLS.
  - Ruffian E: BIRCH_FOREST_HILLS_MOUNTAINS, ROOFED_FOREST_MOUNTAINS.
  - Ruffian B: JUNGLE_MOUNTAINS, SWAMPLAND_MOUNTAINS.

  В 1.21 имена биомов нужно смапить заново (например, EXTREME_HILLS → WINDSWEPT_HILLS, MEGA_TAIGA → OLD_GROWTH_PINE_TAIGA).
- При загрузке чанка удаляется один монстр или железный голем (задумано как «чистка ванильных мобов»).

### 11. Случайные события (`randomevents.yml`, раз в 12–24 ч для каждого города без активного события)
- **Выбор события**: для каждого события `rand(0..999) ≤ chance` (у всех 50, то есть ≈5 %). Из прошедших выбирается событие с меньшим `chance`, при равенстве последнее. Жизненный цикл: сообщения городу → `actions` (если компонент требует активации, нужно `/town event activate`) → раз в 10 с проверка `requirements` (все должны пройти; событие без требований никогда не завершается успехом) → `success`, иначе по истечении `length` часов → `failure`. Сохраняется в таблице `RANDOMEVENTS` (переменные и сообщения в Base64).
- **Компоненты**:
  - SpawnMobs: N ванильных мобов в случайном городском чанке, требует активации.
  - KillMobs: N убийств типа.
  - PickRandomLocation: случайная точка в ±6000 и LocationCheck (житель города в радиусе 50).
  - PickRandomBlock: камень или гравий (поиск от случайного Y 4–23 до 49) в случайном нецивилизованном чанке; подсказка «чанк X,Z, Y между …». Связан с BlockBreak: блок должен сломать житель города.
  - PayPlayer, GivePlatinum (`randomEventSuccess` = 25, раз в день).
  - Happiness и Unhappiness: +/−N на D часов через SessionDB.
  - HammerRate: множитель молотков на D часов.
  - MessageTown.
- **События**:
  - Slime Plague (48 ч): 40 слизней. Убить 4 → платина, иначе −3 счастья на 48 ч.
  - Truffles (72 ч): +5 счастья на 72 ч.
  - Productivity (72 ч): молотки ×1.5 на 72 ч.
  - Herbs (8 ч): дойти до точки → +5 счастья на 120 ч и платина.
  - Gold Rush (12 ч): сломать спрятанный блок → 75 000 монет сломавшему и платина.

### 12. Рыбалка (`fishing.yml`)
Пойманная рыба удаляется. Каждый дроп катается независимо (шанс × 10000), выпасть может несколько. Если не выпало ничего, выдаётся сырая рыба. Дропы:
- Mercury 5 %;
- Mercury Bath 0.5 %;
- Crafted Reeds 6 %;
- T1 SlimeBall 3 %;
- Lead 7 %;
- кожа 4 %;
- палка 3 %;
- фугу 1 % (сломано, см. B.30).

Избыток падает на землю. Бафф `buff_fishing` от товаров в этом листенере не используется.

### 13. Структуры, которыми управляют эти таймеры
- **Треммель** (`s_trommel`, `tech_mining`): раз в 1 с, если выходные сундуки (id 2, два) не полностью заняты, забирает один булыжник из входных (id 1, два) и выдаёт результат по каскаду одного броска d10000 против порогов шансов:
  - хром 0.0008;
  - изумруд 0.002;
  - алмаз 0.005;
  - золотой слиток 0.02;
  - железный слиток 0.04;
  - иначе `gravel_rate` = 1 гравий.

  Пороги **кумулятивные**: реальные вероятности — разности соседних порогов. Шанс увеличивается баффом `EXTRACTION` и умножается на ×2.0 при деспотизме, иначе на ×0.8.
- **Мельница** (`ti_windmill`, `tech_automation`): раз в 60 с (не во время войны) по 8 соседним фермерским чанкам ищет пашню с воздухом над ней. Сажает до `plant_max` = 16 (×2 с `tech_machinery`) культур из семян сундука id 0: пшеница, морковь или картофель случайно из доступного.
- **Самонаведение стрел** башен и **cannon** (FireWorkTask) — визуальные эффекты.

### 14. Восстановление (`recover/`)
Админ-команда сравнивает типы блоков структур с шаблоном, игнорируя обсидиан, забор и лестницу. Выводит список «сломанных» или перестраивает несовпадающие неспециальные блоки (включая замену бедрока в местах, где в шаблоне воздух).

### 15. Интеграция с Dynmap (`civcraft_dynmap`)
Три набора маркеров: «Town Borders» (приоритет 10), «Culture» (15), «Structures» (20). Раз в 2 с (синхронно):
- контуры городских чанков, красная линия и заливка 0.4, описание `town.getDynmapDescription()`;
- контуры культуры в цвете цивилизации (заливка 0.4, без линии), описание `getCultureDescriptionString()`;
- маркеры структур в центре шаблона с иконкой `getMarkerIconName()`; маркеры удалённых структур убираются.

Контуры строятся flood fill и обходом по часовой стрелке (алгоритм из Dynmap-Towny).

### 16. civregister
Команды:
- `/reg <код>` (консоль: `/reg <ник> <код>`) ищет в таблице `users` (CakePHP-сайт) запись с `verifyCode = <код>`, записывает `game_name`, `verified = 1`, `returnCode`. Даёт возможность получать платину и перки.
- `/resetpassword yes` генерирует пароль из 6–9 символов, записывает `SHA1(salt+pwd)` в `users.password` для `game_name` и показывает пароль в чате.

Право `civregister.reg`.

### 17. moblib
Библиотека NMS-мобов:
- Базовые классы `MobBase{Zombie, ZombieGiant, PigZombie, Witch, IronGolem, Wither, Villager, WitherSkull}` очищают ванильные цели AI и делегируют тик, урон, смерть и дальнюю атаку интерфейсу `ICustomMob`.
- В NBT сохраняются `customMobClass` и `customMobData` (тип:уровень).
- `MobLib.spawnCustom(className, loc)` создаёт моба через рефлексию. `isMobLibEntity` определяет моба по `instanceof ISpawnable`. Горение отменено.
- Команда `/moblib <class>` (OP) — тестовый спавн.

В переписанной версии заменить на Paper API: PDC, `Mob#getPathfinder`, `MobGoals`.
