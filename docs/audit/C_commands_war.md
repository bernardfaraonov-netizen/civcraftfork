# Аудит C: команды, слушатели, война, осада, лагеря, арены, PvP-логгер, торговля, интерактив, вопросы, эндгейм, туториал, античит, perks/scores/reports, SLS

Область: `legacy/civcraft/src/com/avrgaming/` — `civcraft/listener`, `civcraft/command/**`, `civcraft/war`, `civcraft/siege`, `civcraft/camp`, `civcraft/arena`, `civcraft/pvplogger`, `civcraft/trade`, `civcraft/interactive`, `civcraft/questions`, `civcraft/endgame`, `civcraft/tutorial`, `anticheat`, `civcraft/nocheat`, `global/**`, `sls`. Конфиги: `legacy/civcraft/data/*.yml`.
Все пути ниже даны относительно `legacy/civcraft/src/com/avrgaming/`. Номера строк — по исходным файлам. Каждая находка проверена чтением кода; где вывод зависит от поведения Bukkit/CraftBukkit 1.7.10, это сказано явно.

---

## Баги и недочёты

### Критические

1. **Критический — `civcraft/command/town/TownCommand.java:134-172` (`/town movestructure`)**
   Не проверяется, что структура принадлежит выбранному городу или хотя бы цивилизации отправителя. Проверяется только, что отправитель — лидер своей цивилизации (стр. 159) и что `town` и `targetTown` принадлежат одной цивилизации (стр. 163). Затем `town.removeStructure(struct)`, `targetTown.addStructure(struct)`, `struct.setTown(targetTown)`, `struct.save()` (стр. 167-170). Итог: лидер любой цивилизации может по координате забрать **любую** структуру (кроме ратуши и капитолия) у чужого города и навсегда перенести её в свой. После рестарта структура окончательно принадлежит атакующему. До рестарта она ещё и остаётся в карте старого города, так что зарегистрирована дважды.
   *В переписке:* проверять `struct.getTown() == selectedTown`, права мэра или лидера именно этого города, запрет на время войны и перед ней, запрет для чудес; операцию делать транзакционно.

2. **Критический — `civcraft/command/town/TownCommand.java:101-132` (`/town enablestructure`)**
   Здесь тоже нет проверки владельца: `town.removeStructure(struct); town.addStructure(struct);` для структуры любого города. `Town.addStructure` (`object/Town.java:1689`) кладёт чужую структуру в карту `structures` выбранного города, и та начинает учитываться в его баффах и лимитах. Лидерство проверяется только в собственной цивилизации.
   *В переписке:* та же проверка владения; «переактивацию» делать методом самой структуры без её перерегистрации.

3. **Критический — `civcraft/command/town/TownSetCommand.java:194-225` (`/town set taxrate|flattax`)**
   Нет ни границ, ни проверки NaN и Infinity: `town.setTaxRate(Double.valueOf(args[1])/100)`, `town.setFlatTax(Integer.valueOf(args[1]))`. Налог списывается через `EconObject.payToCreditor` (`object/Town.java:1239-1242,1265`). Если суммы не хватает, эта функция **отдаёт городу весь баланс жителя** и записывает остаток в долг, а долг ведёт к выселению. Поэтому мэр или ассистент, выставив `flattax 2147483647` или `taxrate 1e12`, на следующем тике налогов забирает все деньги у всех жителей, кроме мэров и ассистентов. `taxrate NaN` даёт то же самое: `hasEnough(NaN)` = false, и весь баланс уходит городу. Кроме того, отрицательный налог молча превращается в 0.
   *В переписке:* диапазоны задавать конфигом (например, 0–`government.maximum_tax_rate`, flat-tax не больше N), `Double.isFinite`, а изменение налога применять с задержкой и уведомлять жителей.

4. **Критический — `civcraft/command/resident/ResidentCommand.java:187-261` (`/res exchange`)**
   Считается любой не-`LoreMaterial` предмет нужного ID (стр. 232-245), а удаляется через `player.getInventory().removeItem(new ItemStack(id, amount))` (стр. 256), результат при этом не проверяется. `removeItem` в CraftBukkit ищет похожий стак (`isSimilar`, мета должна совпадать). Трейд-гуди «Silver» — это железный слиток, а «Gems» — изумруд с лором и именем (`data/goods.yml:151-155, 210-214`). `LoreMaterial.isCustom` для них возвращает false, поэтому они входят в `total`, но не удаляются. Результат: при гуди в инвентаре каждая команда `/res exchange emerald 1` чеканит 500·0.3 = 150 монет, бесконечно. Подойдёт и любой слиток или камень с другой метой.
   *В переписке:* удалять ровно те слоты, которые посчитаны, в одной операции. Проверять, что удалено нужное количество, и только потом начислять. Предметы с PDC-метками или лором исключать.

5. **Критический — `civcraft/trade/TradeInventoryListener.java:184-187, 353-368` (торговое окно, дюп двойным кликом)**
   Во время торговли слоты 9-17 содержат **копии** предметов партнёра: их туда кладёт `SyncInventoryChange`, стр. 76. Клик по этим слотам отменяется, если `rawSlot <= 18`, но `ClickType.DOUBLE_CLICK` (COLLECT_TO_CURSOR) по своему инвентарю (`rawSlot >= 45`) проходит без отмены (стр. 360-368). Ванильный сбор на курсор собирает подходящие стаки со всех слотов открытого окна, включая слоты-копии. Сценарий: партнёр кладёт 32 алмаза, я держу алмаз на курсоре и дважды кликаю в своём инвентаре — копии уходят мне; партнёр закрывает окно и получает оригиналы обратно (стр. 573-584). *(Вывод основан на коде обработчика и ванильной семантике COLLECT_TO_CURSOR в 1.7.10.)*
   *В переписке:* показывать предложение другой стороны только как неинтерактивный GUI (Paper `InventoryView` с отменой **всех** кликов вне своей зоны, включая COLLECT_TO_CURSOR, NUMBER_KEY, SWAP_OFFHAND и drag). Сами предметы держать в серверном эскроу, а не в слотах окна.

6. **Критический — `civcraft/listener/CustomItemManager.java:99-137` (лазуритовая руда → вольфрам)**
   Обработчик `onBlockBreakSpawnItems` (NORMAL) **не проверяет `event.isCancelled()`**. `BlockListener` регистрируется раньше и отменяет ломание в чужом городе, в структуре или на арене, а этот обработчик всё равно ставит блок в AIR (стр. 108) и выбрасывает `mat_tungsten_ore`. Любой игрок может выкапывать лазуритовую руду в чужих городах, в защищённых структурах и на аренах.
   *В переписке:* `ignoreCancelled = true`. Кастомный дроп делать через `BlockDropItemEvent` или loot tables.

7. **Критический (гонка потоков) — вопросы и интерактивный режим выполняются вне главного потока**
   - `main/CivGlobal.java:831-832, 848-849, 1494-1495, 1511-1512` запускают `PlayerQuestionTask`, `CivLeaderQuestionTask` и `CivQuestionTask` через `TaskMaster.asyncTask`. Обработчик ответа вызывается прямо в этом async-потоке: `threading/tasks/PlayerQuestionTask.java:71-72`, `CivQuestionTask.java:89-90`, `CivLeaderQuestionTask` (`processResponse(response, responder)`). В результате вне главного потока выполняются `town.addResident` (`questions/JoinTownResponse.java:41`), `toCiv.mergeInCiv` и `town.changeCiv` (`questions/DiplomacyGiftResponse.java:48-63`), `capitulator.capitulate()` (`questions/CapitulateRequest.java`), `CivGlobal.setRelation` (`questions/ChangeRelationResponse.java:37`), `camp.addMember` (`questions/JoinCampResponse.java:40`), `ArenaTeam.addMember`, а также `Resident.startTradeWith` с `Bukkit.createInventory` и `openInventory` (`questions/TradeRequest.java:13-37`).
   - `civcraft/listener/ChatListener.java:67-69`: `AsyncPlayerChatEvent` вызывает `InteractiveResponse.respond` в чат-потоке. Без перехода в главный поток работают: `interactive/InteractiveConfirmWeatherChange.java:28-30` (`world.setStorm`), `interactive/InteractiveRepairItem.java:50` → `Barracks.repairItemInHand` (деньги + инвентарь), `interactive/InteractiveCustomTemplateConfirm.java:61-62`, `interactive/InteractiveRenameCivOrTown.java:84-101` (`rename` меняет глобальные карты), `InteractiveReportPlayer*`.
   *В переписке:* ответы обрабатывать только на главном потоке (или region-потоке Folia). Вопросы делать как `Map<UUID, PendingRequest>` с таймаутом через scheduler, без `wait()/notify()`. Всё состояние заново валидировать в момент ответа.

### Высокие

8. **Высокий — `civcraft/command/civ/CivCommand.java:246-268` (`/civ disbandtown <town>`)**
   Город берётся через `getNamedTown(1)`, то есть **любой в мире**; проверки `town.getCiv() == civ` нет. Лидер или **советник** любой цивилизации выставляет `leaderWantsToDisband` чужому городу; если мэр этого города когда-либо набрал `/town disbandtown`, город удаляется. Описание команды говорит «leader», а проверка `validLeaderAdvisor` пускает и советников.
   *В переписке:* проверять принадлежность, давать только лидеру, флаги согласия хранить с таймаутом, запрещать во время войны.

9. **Высокий — `civcraft/command/civ/CivCommand.java:129-216` (`/civ revolution`)**
   У команды нет **никакой** проверки роли. Любой житель, у которого выбран столичный город материнской цивилизации (достаточно группы residents), запускает революцию за счёт казны этого города (стр. 181, 211): все города с этой mother-civ возвращаются, а владельцам объявляется война (стр. 186-203). Кроме того, `setRelation(WAR)` ставится независимо от кулдауна и дипломатии.
   *В переписке:* только лидер материнской цивилизации (или мэр её столицы), с подтверждением и журналом.

10. **Высокий — `civcraft/war/WarRegen.java:51, 233-255` (кэш сохранённых блоков не очищается)**
    `blockCache` (`HashMap<Block,Boolean>`) заполняется при сохранении и не очищается никогда. После первой войны без рестарта сервера блоки с теми же координатами во второй войне **не сохраняются**, потому что `saved == TRUE`. Значит, в конце войны они не восстанавливаются, и разрушения остаются навсегда. Вдобавок файловый ввод-вывод на каждый блок идёт синхронно в главном потоке, а формат с разделителем `:` (стр. 127) ломается, если в тексте таблички есть `:`.
    *В переписке:* журнал регенерации вести на каждую войну (id войны), в БД или в бинарном формате, писать асинхронно батчами, после восстановления очищать; для табличек и контейнеров хранить snapshot `BlockState`.

11. **Высокий — `civcraft/siege/CannonProjectile.java:76-168` и `civcraft/siege/Cannon.java:179-243` (осадная пушка)**
    - Размещение (`checkBlockPermissionsAndRestrictions`) не проверяет TownChunk, права и состояние войны с владельцем территории. Проверяются только WarTime, отсутствие сундука (именно `CHEST`; trapped chest, печь, воронка и т. п. перезаписываются), а также structure/camp/road блоки. Пушку можно построить внутри **нейтрального** или союзного города.
    - Попадание: все не-структурные блоки в радиусе 7 (`cannon.yield`) удаляются **где угодно**: в нейтральных городах, у спавна, в стенах (`explodeBlock`, стр. 90-92).
    - Урон ратуше `th.onCannonDamage(...)` (стр. 113-119) наносится **без проверки войны**; проверка `atWarWith` есть только для прочих структур (стр. 128).
    - Всем игрокам в радиусе наносится 200 урона, включая нейтралов и своих (стр. 161-168).
    - `Cannon.validateUse` (стр. 518-528) падает с NPE, если у владельца или игрока нет цивилизации. Таймер кулдауна после `destroy()` продолжает обновлять табличку, которой уже нет, и получает ClassCastException (стр. 594-603).
    *В переписке:* ставить только в культуре цивилизации, с которой идёт война, или в дикой местности; урон — только объектам и игрокам противника; разрушения — только в зоне конфликта.

12. **Высокий — `civcraft/trade/TradeInventoryListener.java:372-513` (потеря предметов при сбое сделки)**
    `completeTransaction` сначала удаляет обе пары из `tradeInventories` (стр. 379-380), потом проверяет расхождения (`Inventory mismatch`) и хватает ли монет (стр. 461-475, там `return`), и в `finally` закрывает окна. `onInventoryClose` уже не находит пару (стр. 559-562) и **не возвращает предметы**: всё, что лежало в окне, пропадает. Кроме того, не хватает `throw` на стр. 454, слоты 19-26 и 38-42 не защищены (положенные туда предметы теряются), а дистанция и мир на момент завершения не проверяются.
    *В переписке:* эскроу с атомарным commit/rollback.

13. **Высокий — `civcraft/command/admin/AdminCivCommand.java:140-162` (`/ad civ bankrupt`)**
    После сообщения «Are you sure… use yes» нет `return` (стр. 143-146): казна цивилизации, всех её городов и **всех жителей** обнуляется сразу, без подтверждения.
    *В переписке:* подтверждение по токену.

14. **Высокий — `civcraft/listener/BlockListener.java:541-585` и `civcraft/listener/PlayerListener.java:346-369` (жидкости)**
    `BlockFromToEvent` обрабатывается только для запрета генераторов булыжника, перетекание через границу чанка города не проверяется. Лаву или воду, вылитую в дикой местности у границы, ничто не останавливает на пути в город. `PlayerBucketEmptyEvent` запрещает только лаву, только в чужой **культуре** и по координате `getBlockClicked()`, а не целевого блока; воду не проверяет вообще. Проверка ITEMUSE для вёдер в `OnPlayerUseItem` (`BlockListener.java:1141-1175`) тоже берёт чанк кликнутого блока, поэтому ведро, вылитое через грань блока на границе, попадает в соседний чанк без проверки прав. Побочный эффект антигенератора (стр. 559-575): соседний источник жидкости превращается в незерак **без какой-либо проверки территории**, в том числе в чужом городе.
    *В переписке:* блокировать `BlockFromToEvent` при переходе из чанка без прав в чанк с правами, проверять целевой блок в `PlayerBucketEmpty/Fill`, для генераторов использовать `BlockFormEvent`.

15. **Высокий — `civcraft/war/War.java:69-97` (`resaveAllDefeatedCivs`)**
    Функция вызывает `saveDefeatedCiv()` для каждой уже побеждённой цивилизации, а `saveDefeatedCiv` **заново выдаёт платину** всем жителям победителя (стр. 75-81) и повторно вызывает `EndGameCondition.onCivilizationWarDefeat` (стр. 83). Для Science это ещё раз удаляет технологию победы, для Diplomacy ещё раз сносит Council of Eight и голоса. `resaveAllDefeatedCivs` вызывается из `transferDefeated` при каждом захвате столицы.
    *В переписке:* разделить «записать победу» и «наградить»; наградные эффекты применять ровно один раз, в конце войны.

16. **Высокий — `civcraft/command/civ/CivDiplomacyGiftCommand.java:100-143` + `civcraft/questions/DiplomacyGiftResponse.java:34-73`**
    Для `town`, в отличие от `entireciv` (стр. 84-86), нет запрета на дни перед войной. При принятии через 30 секунд ничего не перепроверяется: город уже может не принадлежать `fromCiv`, может идти война, может быть другой владелец. Всё выполняется в async-потоке (см. п. 7).
    *В переписке:* повторная полная валидация при accept на главном потоке.

### Средние

17. **Средний — `civcraft/command/CommandBase.java:375-416, 418-557, 626-711` (поиск по имени через regex)**
    Ввод пользователя без экранирования превращается в регулярное выражение (`name.replace("%","(\\w*)")` + `String.matches`). Шаблон с катастрофическим бэктрекингом (например `(a+)+b`), применённый ко всем жителям, вешает главный поток — DoS через любую команду с именем (`/res show`, `/town show`, `/civ show`, `/plot setowner` и т. д.). Кроме того, `.` совпадает с любым символом, поэтому команды иногда выбирают не того игрока.
    *В переписке:* точное совпадение без учёта регистра плюс автодополнение через Brigadier/Paper; wildcard — только через `startsWith`.

18. **Средний — `civcraft/command/town/TownOutlawCommand.java:48-63, 94` + `threading/tasks/TownAddOutlawTask.java`**
    `addall` не проверяет, что в «вне закона» добавляется **чужой** город; `add` эту проверку делает (стр. 83). Оповещение отправляется `CivGlobal.getPlayer(args[1])` — по имени **города**, а не жителя (стр. 55). Сама установка статуса выполняется `TaskMaster.asyncTask(..., 1000)`: 1000 тиков — это 50 с при заявленных 60, и асинхронно меняются `town.outlaws` и вызывается Bukkit API.
    *В переписке:* синхронная задача по таймеру, валидация, хранение по UUID.

19. **Средний — `civcraft/command/town/TownEventCommand.java:68-70`**
    `permissionCheck()` пустой: любой житель, у которого выбран город, может выполнить `/town event activate` — активировать случайное событие города, в том числе с последствиями.
    *В переписке:* только мэр или ассистент.

20. **Средний — `civcraft/command/BuildCommand.java:120-174` + `civcraft/command/town/TownCommand.java:421-448`**
    `/build demolish`, `/build demolishnearest`, `/build undo` и `/town disbandtown` + `/civ disbandtown` **не запрещены во время WarTime**. Защитник может снести атакуемые постройки или распустить город под осадой, чтобы сорвать захват. `validatenearest` падает с NPE, если `getNearestBuildable` вернул null (стр. 63-65).
    *В переписке:* единый guard «идёт война / до войны N дней» для всех разрушительных операций.

21. **Средний — `civcraft/command/admin/AdminWarCommand.java:96-100`**
    `/ad war start` вызывает `War.setWarTime(true)`, но не планирует `WarEndCheckTask`, как это делает `event/WarEvent.java:43`. Война, запущенная админом, сама не заканчивается.
    *В переписке:* одна функция запуска войны.

22. **Средний — `civcraft/war/WarListener.java:115-161` (взрывы TNT в войну)**
    Для `PRIMED_TNT` и `MINECART_TNT` в **любой точке мира** во время WarTime все блоки в сфере радиусом `cannon.yield/2` (= 3) сохраняются и удаляются, включая обсидиан, бедрок и structure-блоки, которых нет в ванильном `blockList`. Состояние войны у места взрыва не проверяется. Если же ванильный `blockList` задел town-чанк или structure-блок, `BlockListener.OnEntityExplodeEvent` (NORMAL, `BlockListener.java:423-487`) отменяет взрыв целиком, и WarListener (HIGH, стр. 118) его пропускает. TNT в городах бесполезен даже в войну; одновременно у спавна и в нейтральных землях TNT разрушает всё до конца войны.
    *В переписке:* явная политика взрывов по зонам (война / мир / город / дикая местность), на основе `blockList`.

23. **Средний — `civcraft/pvplogger/PvPLogger.java:65-106`**
    Тег боя ставится только **защищающемуся** и только от прямого удара игрока или стрелы. Атакующий может выйти сразу; снежки, удочки, зелья и огонь тег не ставят. На стр. 108-139 NPC-зомби создаётся при любом выходе игрока с тегом, в том числе при кике античита, рестарте или `onlywarriors`. `/kill` (`civcraft/command/KillCommand.java`) не запрещён в бою.
    *В переписке:* тег обеим сторонам по любому урону от игрока (включая projectiles и DOT), блокировать команды в бою, NPC-заместитель — через отдельный плагин или собственную реализацию с сохранением инвентаря в БД.

24. **Средний — `civcraft/command/team/TeamCommand.java:38-71` + `civcraft/arena/Arena.java:248-258` + `civcraft/arena/ArenaManager.java:496-567`**
    `/team surrender` можно вызвать многократно за 10 секунд до уничтожения арены, и `onControlBlockDestroy` тоже не защищён флагом `ended`. Каждый вызов планирует отдельный `declareVictor`, и очки ладдера начисляются N раз. Две команды в сговоре фармят рейтинг. Вторая попытка `destroyArena` при этом бросает исключение.
    *В переписке:* машина состояний матча (RUNNING → ENDING → CLOSED), результат фиксировать один раз.

25. **Средний — `civcraft/endgame/EndGameCondition.java:153-187` + `civcraft/endgame/EndConditionScience.java:60-68` + `threading/timers/DailyTimer.java:81`**
    - `checkForWin`: если у условия уже есть запись для **другой** цивилизации, для текущей запись не создаётся никогда, то есть за каждый тип победы одновременно «бежит» только одна цивилизация. Обновление пишет в `entries.get(0)`, а не в текущую запись.
    - `getDaysLeft` (стр. 137-147) делает `Integer.valueOf("civId:days")` и получает NumberFormatException.
    - `EndConditionScience.finalWinCheck`: если ни у кого нет накопленных бикеров, `getMostAccumulatedBeakers()` возвращает null, и вызов `rival.getName()` падает с NPE.
    - `EndGameCheckTask` запускается **асинхронно** и оттуда меняет SessionDB и объявляет победителя.
    - `onFailure` на каждую цивилизацию каждый день пишет ERROR в лог.
    *В переписке:* отдельная сущность `VictoryProgress(civ, type, daysHeld)`, ежедневный пересчёт на главном потоке.

26. **Средний — `civcraft/listener/BonusGoodieManager.java:160-227`**
    Отслеживается только `InventoryClickEvent`. Перемещение клавишей хотбара (`NUMBER_KEY`: курсор пуст, поэтому goodie == null и проверка пропускается), перетаскивание (`InventoryDragEvent` — обработчика нет) и воронки (`InventoryMoveItemEvent` — обработчика нет во всём проекте) обходят запрет «только сундук или игрок». `holder instanceof Player` для эндер-сундука даёт true, поэтому гуди можно спрятать в эндер-сундук, и его не найдут и не вернут на аванпост.
    *В переписке:* гуди как PDC-предмет; отменять любые перемещения в недопустимые контейнеры (Click/Drag/Move/Pickup, hopper-minecart, эндер-сундук, shulker/bundle).

27. **Средний — `civcraft/camp/Camp.java:100, 167, 1141-1147` + `civcraft/command/camp/CampCommand.java:176-208`**
    Флаг `undoable` ставится в `true` при создании и **никогда не сбрасывается** (после рестарта он false только потому, что не сохраняется). До рестарта владелец в любой момент может сделать `/camp undo`: получить обратно предмет основания лагеря и откатить местность, например прямо во время рейда, до уничтожения контрольных точек.
    *В переписке:* окно undo ограничивать временем или отсутствием взаимодействий.

28. **Средний — защита сущностей не реализована (`civcraft/listener/BlockListener.java:275-361`)**
    `onEntityDamageByEntityEvent` защищает только игроков и защищённые рамки. Животные, жители-NPC, лошади из конюшен, вагонетки и лодки в чужом городе не защищены. `VehicleDestroyEvent`, `HangingPlaceEvent`, `PlayerLeashEntity` и `InventoryMoveItemEvent` в проекте не обрабатываются вообще (проверено grep'ом).
    *В переписке:* флаги прав на животных, транспорт и hanging в модели прав участка.

29. **Средний — `civcraft/listener/DisableXPListener.java:58-66`**
    Весь опыт конвертируется в монеты 1:1 (`resident.getTreasury().deposit(event.getAmount())`). Мобофермы, печи и рыбалка становятся «печатным станком». Кроме того, NPE, если resident null.
    *В переписке:* явная экономическая модель с лимитами.

30. **Средний — античит `anticheat/ACManager.java:77-104, 241-260`**
    Ключ DES отправляется клиенту в открытом виде в самом challenge (`writeKey`, стр. 77-81, 100-104), так что ответ легко подделать. «Ловушка» `validTrap` (стр. 252-260) пишет игрока в файл и делает `return` — после этого вызывающий код **засчитывает валидацию** (стр. 223-227). Буфер 24 байта переполнится при ключе длиннее 8 символов. Пустое сообщение даёт NegativeArraySizeException (стр. 180).
    *В переписке:* от собственного клиентского античита отказаться.

31. **Средний — `civcraft/command/town/TownCommand.java:770-800` (`/town unclaim`)**
    Роль (мэр или ассистент) проверяется в **выбранном** городе, а чанк должен принадлежать **родному** городу (`tc.getTown() != resident.getTown()`, стр. 785). Советник цивилизации, который мэр в городе B, но живёт в городе A, выбирает B и отдаёт чанки A. Проверка «последний чанк» тоже смотрит на выбранный город (стр. 781).
    *В переписке:* права определять по городу-владельцу чанка.

32. **Средний — `civcraft/command/plot/PlotCommand.java:206-228` + `object/TownChunk.java:463-481`**
    `/plot fs` принимает отрицательную и любую другую цену. Владелец выставляет свой участок за −1e9 и покупает сам у себя (`buy_cmd` не проверяет, что покупатель — не текущий владелец). Затем `value = price` становится отрицательным, и налог на имущество `tc.getValue()*taxRate` отрицателен, то есть фактически обнуляется: так уходят от налога.
    *В переписке:* цена > 0, `value` не зависит от цены перепродажи.

33. **Средний — `global/perks/PlatinumManager.java:26-43, 128-130, 185-243`**
    `pendingPlatinum` хранит `LinkedList`, в который пишут асинхронные задачи, а читает таймер — без синхронизации, возможна потеря платины. В `giveManyPlatinumDaily` стоит `return` вместо `continue` (стр. 128-130): первый уже награждённый житель обрывает цикл для всех остальных. В `updatePendingPlatinum` при `resident == null` выполняется `continue` без `poll()`, что даёт бесконечный цикл (стр. 197-199).

### Низкие

34. **Низкий — `civcraft/command/AcceptCommand.java:55-64`**: `civTask` может быть null → NPE, если ни на один вопрос к лидерам ответа не ждут. **`DenyCommand.java:54`**: `resident.getCiv()` для игрока без города → NPE.
35. **Низкий — `civcraft/command/CommandBase.java:131`**: в catch выводится `e.getMessage()` не того исключения (`e` вместо `e1`).
36. **Низкий — `civcraft/command/CommandBase.java:267-290`**: `validMayorAssistantLeader` берёт группу лидеров **mother-civ** выбранного города, а `Town.validateResidentSelect` (`object/Town.java:2397-2407`) — текущей цивилизации. В захваченных городах права лидеров текущей и прежней цивилизаций не согласованы.
37. **Низкий — `civcraft/command/resident/ResidentCommand.java:64-73`**: после сообщения «You are not protected» нет `return`. **Стр. 106-111**: `/res refresh` без кулдауна порождает async-запросы в БД (DoS), а `resident.perks` меняется из async-потока (`object/Resident.java:1144-1177`). **Стр. 328-334**: сообщения о долге и выселении отправляются тому, кого смотрят, а не отправителю.
38. **Низкий — `civcraft/command/plot/PlotPermCommand.java:42`**: `(Player)sender` без проверки — ClassCastException из консоли.
39. **Низкий — `civcraft/command/civ/CivResearchCommand.java:71-77`**: прогресс текущего исследования сбрасывается до того, как `startTechnologyResearch` проверит `hasTech`; при исключении прогресс потерян.
40. **Низкий — `civcraft/command/civ/CivGovCommand.java:104-106`**: смену правительства (с анархией на 24 ч) может делать советник, а не только лидер. **`CivGroupCommand.java:45-81`**: любой лидер может снять всех остальных лидеров. Это дизайн, но поведение стоит зафиксировать в переписке.
41. **Низкий — `civcraft/command/admin/AdminLagCommand.java:101-109`**: `warning` переключает `growthEnabled`, а не `warningsEnabled`. **`AdminChatCommand.java:188`**: `banwordtoggle` без суффикса `_cmd` недостижим. **`AdminBuildCommand.java:133-139`**: проверяется `args.length<2`, а используется `args[2]` → AIOOBE. **`AdminCommand.java:288-322`**: `setfullmessage` и `unban` не зарегистрированы в `commands`, поэтому недостижимы, и `unban` падает с NPE при `r == null`. **`AdminTownCommand.java:106-109`**: `event` с неверным id → NPE. **`AdminTownCommand.java:435-457`**: параметр `town` в `unclaim` игнорируется. **`AdminCivCommand.java:295-319`**: `rmleader`/`rmadviser` не вызывают `grp.save()`. **`AdminMobCommand.java:95,120`**: опечатка `savagae`. **`AdminCampCommand.java:57-82`**: помощь говорит «[name] camp», а на деле ожидается имя жителя.
42. **Низкий — `civcraft/command/admin/AdminCommand.java:387-400`**: право `civ.admin` (MINI_ADMIN) даёт **весь** `/ad`, включая `/ad items` (выдача любых предметов через GUI `loregui/SpawnItem.java` без проверки прав в самом действии) и `/ad perm`. Подкоманды `/ad *` своих проверок не имеют. `/econ` требует OP **и** `civ.econ` одновременно (`EconCommand.java:127-131`). `/dbg` требует OP и содержит `dupe`, `heal` и т. п.; `DebugTestCommand.java:71-75` зашивает имя разработчика `netizen539`.
43. **Низкий — `civcraft/listener/BlockListener.java:661-713, 810-860`**: при отказе в установке или ломании (нет прав) обработчик не делает `return` и всё равно меняет `layerValidPercentages` у близлежащих построек. Счётчики «опор» дрейфуют при попытках гриферов. **Стр. 663-665**: исключения `FIRE`/`PORTAL` пропускают **все** проверки установки.
44. **Низкий — `civcraft/listener/BlockListener.java:134-188`**: цикл по соседям выходит на первом же structure-блоке, даже негорючем (стр. 143-147), и town-проверка `perms.isFire()` пропускается. `event.getPlayer()` бывает null (молния, лава), тогда `sendError(null, …)`.
45. **Низкий — `civcraft/listener/BlockListener.java:1060`**: у `OnPlayerBedEnterEvent` нет `@EventHandler`, и запрет спать в чужом лагере не работает.
46. **Низкий — `civcraft/listener/BlockListener.java:1558-1612`**: поршни работают только в farm-чанках (иначе отмена, стр. 1589-1594). Внутри farm-чанка перенос блоков через границу в чужой город не проверяется. `onBlockPistonRetractEvent` проверяет только `getRetractLocation`, без войны и farm.
47. **Низкий — `civcraft/listener/BlockListener.java:322-326`, `CustomItemManager.java:268`, `pvptimer/PvPListener.java:33`**: `(LivingEntity) projectile.getShooter()` даёт ClassCastException для раздатчика (`BlockProjectileSource`) — событие падает, защита не срабатывает. `playersCanPVPHere` (`BlockListener.java:1708-1758`) и `PlayerListener.java:502-526` падают с NPE для игроков без Resident (NPC-плагины).
48. **Низкий — `civcraft/listener/CustomItemManager.java:383-455`**: `processDurabilityChanges` для брони пишет в `getInventory().setItem(i, …)` с индексом брони 0-3, то есть в слоты **хотбара**. Для обычных слотов изменённый стак кладётся в инвентарь, а в дроп уходит исходный объект, так что штраф прочности при смерти не применяется. **Стр. 517-564**: при конвертации слизи и рыбы излишки при полном инвентаре теряются.
49. **Низкий — `civcraft/listener/PlayerListener.java:88-105`**: NPE, если resident null. **Стр. 108-116**: логика `PlayerLoginEvent@MONITOR` выполняется, даже если вход запрещён (бан, whitelist).
50. **Низкий — `civcraft/listener/MarkerPlacementManager.java:114-120`**: `setCancelled` на MONITOR.
51. **Низкий — `civcraft/war/War.java:229-230`**: NPE, если захваченный город удалён. **Стр. 413**: `time_declare_days*(1000*60*60*24)` в int переполняется при ≥ 25 днях. **`data/war.yml:9-10`**: комментарий «Number of hours», а код добавляет `Calendar.MINUTE`, то есть война длится 120 минут.
52. **Низкий — `civcraft/camp/WarCamp.java:540-543`**: `getRespawnPoints()` вызывает сам себя — StackOverflowError (сейчас метод никто не вызывает). **`Camp.java:1317-1327`**: `getNextRaidDate` сдвигает дату только на +24 ч за вызов; после долгого простоя сервера окно рейда «открыто» не по расписанию. **`Camp.java:913-915`**: при постройке защищается только `CHEST`; печи, воронки и trapped chest перезаписываются вместе с содержимым. **`CampCommand.java:81-96`**: `remove` не вызывает `camp.save()`.
53. **Низкий — `civcraft/arena/Arena.java:283-295`**: индекс выбирается по `respawnPoints.size()`, а применяется к `revivePoints` (при разных размерах возможен IOOBE). **Стр. 202**: `coord.setWorldname(...)` меняет **конфиг**. **`ArenaManager.java:193`**: `timeout2` получает очки из `points1`. **`ArenaControlBlock.java:33-37`**: игрок без команды (`getTeam()==null` не равно своей команде) проходит проверку и может бить контрольные блоки. **`ArenaListener.java:99`**: `getInventory().clear()` на `PlayerLoginEvent` — инвентарь ещё не загружен. **`TeamCommand.java:195-210`**: распущенная команда остаётся в `teamQueue` и `teamRankings`. **`ArenaTeam.java:179-196`**: рейтинги сортируются по возрастанию, и `top5` показывает худших.
54. **Низкий — `global/scores/CalculateScoreTimer.java:46-84`**: `TreeMap<Integer,Civ>` по очкам — равные очки затирают друг друга. Коллекции обходятся асинхронно, а `synchronized` стоит на поле, которое тут же переприсваивается.
55. **Низкий — `sls/SLSManager.java:76-102`**: `DatagramSocket` не закрывается (утечка раз в минуту), сервис `atlas.civcraft.net` мёртв. **`global/reports/ReportManager.java`**: жалобы уходят в «глобальную» БД — в переписке заменить Discord-вебхуком или таблицей.
56. **Низкий — `civcraft/tutorial/CivTutorial.java:143-160`**: `LoreMaterial.spawn(loreMat)` вызывается до проверки на null. Статические общие GUI-инвентари разделяются между всеми игроками; защита держится только на совпадении имени инвентаря (`LoreGuiItemListener.guiInventories`).
57. **Низкий — `civcraft/questions/SurrenderRequest.java` + `main/CivGlobal.java:1498`**: механика «сдачи» написана, но **ни одна команда её не вызывает** (мёртвый код).
58. **Низкий — `civcraft/interactive/InteractiveRenameCivOrTown.java:86-88, 95-97`**: глобальное объявление о переименовании отправляется **до** проверки валидности имени. **`ChatListener.java:42-70`**: если включены town/civ-чат и одновременно интерактивный режим, ответ «yes» уходит в чат города.
59. **Низкий — `civcraft/nocheat/NoCheatPlusSurvialFlyHandler.java:30-44`**: глобально гасит нарушения SurvivalFly с `addedVL < violation_grace`.
60. **Низкий (смежное, вне области) — `object/EconObject.java:44-66`**: `synchronized(coins)` на изменяемом `Double` ничего не защищает. **`structure/Capitol.java:95-148`**: табличку возрождения в «военной комнате» может использовать любой, включая врага, — нет проверки цивилизации.

---

## Механики

### 1. Модель прав и ролей

- **Resident** — игрок. Состоит максимум в одном городе **или** одном лагере, а также может быть в одной арена-команде. Есть `selectedTown` — «переключение контекста» для командной работы с другими городами своей цивилизации.
- **Группы города** (`PermissionGroup`): защищённые `mayors`, `assistants`, `residents` (default) плюс произвольные пользовательские группы, которые используются в правах участков. Защищённые группы могут содержать только жителей этого города.
- **Группы цивилизации**: `leaders`, `advisers`; в них могут быть только члены этой цивилизации.
- **Выбор города** (`/town select`) разрешён мэрам, ассистентам и жителям (default) этого города, а также лидерам и советникам его цивилизации (`Town.validateResidentSelect`).
- **Права сервера**: OP; `civ.admin` (MINI_ADMIN) — весь `/ad`; `civ.econ` (вместе с OP) — `/econ` add/set/sub; `civ.moderator`; `civ.freeperks`; `civ.ac_exempt` — не кикается античитом в войну. `/dbg` — только OP или консоль.
- **Захваченные города**: у города есть `motherCiv` (исходная цивилизация) и текущая `civ` (завоеватель).

### 2. Дерево команд

Проверка прав у большинства команд встроена в конкретную подкоманду. В скобках указано, кто может выполнять команду по текущему коду.

**/town (t)**
- `claim` (мэр/ассистент выбранного города) — занять текущий чанк. Условия (`TownChunk.claim`): чанк ещё не занят; город может оплатить `getNextPlotCost`; чанк в культуре своей цивилизации; для не-аванпоста — граничит с землёй города и есть лимит участков по уровню города; от чужих town-чанков не ближе `civ.min_distance` = 15 чанков. Если в чанке лагерь, лагерь распускается.
- `unclaim` (мэр/ассистент) — отдать чанк, без возврата денег; нельзя отдать последний чанк и чужой приватный участок.
- `group new|delete|add|remove|info` (мэр/ассистент/лидер) — управление группами. В mayors добавляют только мэры и лидеры цивилизации; убирать из mayors могут только мэры; последнего мэра убрать нельзя; удалять можно только пустые незащищённые группы.
- `upgrade list [category]|purchased|buy <name>` (мэр/ассистент/лидер) — покупка апгрейдов за казну (с требованиями по апгрейдам и структурам).
- `info [upkeep|cottage|structures|culture|trade|mine|hammers|goodies|rates|growth|buffs|online|happiness|beakers|area|disabled]` — информация о выбранном городе.
- `add <res>` (мэр/ассистент/лидер) — приглашение (вопрос на 30 с). Запрещено в WarTime и в `time_declare_days` до войны, если цивилизация в войне; запрещено, если игрок в лагере или в другом городе.
- `members`, `list`, `top5`, `show <town>` — публичная информация. `show` дополнительно считает для отправителя стоимость дистанционного апкипа при владении этим городом. Казну видят только мэр, ассистенты, лидеры и советники этой цивилизации.
- `deposit <n>` (любой член; n ≥ 1, округление вниз) — сначала гасит долг города. `withdraw <n>` — только мэр.
- `set taxrate|flattax|bankfee|storefee|grocerfee|libraryfee|blacksmithfee|stablefee|scoutrate` (мэр/ассистент). Комиссии 5–15 % (у конюшни — `Stable.FEE_MIN..MAX`); scoutrate — 10, 30 или 60 с.
- `leave` — выйти из своего города (единственный мэр выйти не может; отключает town/civ-чат).
- `evict <res>` (мэр/ассистент) — мэров и ассистентов выселить нельзя. Без земли — немедленно; с землёй — через `GRACE_DAYS` с предупреждением.
- `reset library|store` (мэр/ассистент/лидер) — сброс апгрейдов зачарований библиотеки или материалов магазина.
- `disbandtown` (мэр) — переключатель согласия мэра; город распускается, когда согласны и мэр, и лидер (`/civ disbandtown`). Нельзя для столицы и для захваченных городов.
- `outlaw add|remove|list|addall|removeall` (мэр/ассистент/лидер) — объявить вне закона: через ~50 с (заявлено 60) по таким игрокам стреляют башни, и PvP с ними в городе разрешено. Своих жителей через `add` объявить нельзя.
- `leavegroup <town> <group>` — выйти из группы (последний мэр и последний лидер выйти не могут).
- `select <town>` — сменить контекст.
- `capitulate [yes]` (мэр захваченного не-столичного города) — снять `motherCiv`: город окончательно переходит к завоевателю и больше не может восстать.
- `survey` — оценка биомов вокруг: hammers, growth, happiness, beakers в радиусе культуры 1-го уровня.
- `templates` — GUI перк-шаблонов, привязанных к городу.
- `event show|activate` — текущее случайное событие.
- `claimmayor` — стать мэром, если все мэры неактивны (только в родном городе).
- `movestructure <coord> <town>`, `enablestructure <coord>` (лидер; не в войну) — см. баги 1–2.

**/civ**
- `townlist`, `list [civ]`, `show <civ>`, `top5`, `info [upkeep|taxes|beakers|online]`, `victory`, `votes`, `time` (таймеры: апкип, почасовой тик, репо гуди, война; для админов также регенерация спавна и случайное событие).
- `deposit <n>` (любой член), `withdraw <n>` (лидер).
- `research list|progress|on <tech>|change <tech>|finished` (лидер/советник). Старт исследования списывает `tech.cost` из казны цивилизации; требуется достроенная ратуша столицы; `change` сбрасывает прогресс.
- `gov info|list|change <gov>` (лидер/советник). Смена правительства вводит `gov_anarchy` на 24 ч, если в цивилизации нет баффа `buff_noanarchy`; при нём смена мгновенная.
- `set taxes <0-100>` (не выше `government.maximum_tax_rate`), `science <0-100>`, `color <hex>` (кроме `FF0000`) — лидер/советник.
- `group add|remove|info leaders|advisers` (лидер/советник; в leaders добавляют и убирают только лидеры, себя из leaders убрать нельзя).
- `dip …` — см. раздел «Дипломатия».
- `disbandtown <town>` (лидер/советник) — согласие лидера на роспуск города.
- `revolution [yes]` — см. «Война».
- `claimleader` — стать лидером, если все лидеры неактивны `leader_inactive_days` = 7 дней.

**/resident (res)**: `info`, `show <res>`, `paydebt`, `friend add|remove|list`, `toggle map|info|showtown|showciv|showscout|combatinfo|itemdrops`, `resetspawn` (кровать на спавн мира), `exchange iron|gold|diamond|emerald <n>` — сдача слитков за `ore_rate × exchange_rate`: железо 20, золото 200, алмаз 400, изумруд 500, всё × 0.3; `book` (учебная книга), `perks`, `refresh`, `timezone [tz|list]`, `pvptimer` (досрочно снять PvP-защиту новичка).

**/plot (p)**: `info`; `toggle mobs|fire`, `fs <price>`, `nfs`, `addgroup|removegroup|cleargroups <group>`, `setowner <res|none>`, `perm set <build|destroy|interact|itemuse> <owner|group|others> <on|off>` — владелец участка; для ничейного участка — мэр, ассистент или лидер, и только в родном городе. `buy` — только житель этого города и только если участок выставлен на продажу (аванпосты не продаются). `farminfo` — отладочная информация farm-чанка.

**/build**: `<имя структуры>` — предпросмотр и начало постройки с подтверждением «yes» в чате (`InteractiveBuildCommand`), `list`, `progress`, `repairnearest [yes]` (не в войну; за `getRepairCost`), `demolish [coord]`, `demolishnearest [yes]`, `refreshnearest` (только мэр, кулдаун, не в войну), `validatenearest` (не в войну), `undo` (только для последней стены или дороги). Всё — мэр, ассистент или лидер (`validMayorAssistantLeader`).

**/camp**: `info`, `leave`, `add <res>` (владелец; приглашение 30 с; нельзя, если игрок в городе или лагере), `remove <res>`, `setowner <res>`, `disband` (откат шаблона и удаление), `undo` (возврат предмета основания, если лагерь «undoable»), `upgrade list|purchased|buy` (владелец; платит владелец): `camp_upgrade_sifter` / `longhouse` / `garden` по 500.

**/market (m) buy towns|civs [name]** (лидер/советник): купить город (не столицу), выставленный на продажу, или целую цивилизацию; не в войну и не за `time_declare_days` до неё; нельзя, если своя цивилизация сама на продаже.

**/team**: `create <name>` (не под PvP-защитой), `add <res>` (лидер; приглашение), `remove <res>`, `leave`, `disband`, `changeleader <res>`, `arena` (лидер; встать в очередь или выйти из неё), `surrender` (лидер во время матча), `info`, `show`, `list`, `top5`, `top10`.

**Прочие**
- `/trade <res>` — предложение обмена. Условия: дистанция ≤ `max_trade_distance` = 10, никто из двоих не на арене, у цели нет активной торговли.
- `/pay <res> <n>` — перевод денег, n ≥ 1.
- `/econ` (money) — показать свой баланс. С OP и `civ.econ` доступны `add|set|sub`, `addtown|settown|subtown`, `addciv|setciv|subciv`, `setdebt`, `setdebttown`, `setdebtciv`, `clearalldebt`.
- `/accept (yes)`, `/deny (no)` — ответ на личный вопрос или вопрос лидерам. `/select <n>` — выбор шаблона в вопросе.
- `/tc [msg]`, `/cc [msg]` — переключить или отправить сообщение в чат города или цивилизации. `/gc` — выход в глобальный чат (сам глобальный чат отдан HeroChat).
- `/here` — чья культура и чей город под игроком.
- `/vote <civ>` — голос за дипломатическую победу (раз в `vote_cooldown_hours` = 24; только если в мире есть активный Council of Eight; голосующий должен быть в городе).
- `/report player <name>` — жалоба: категория → описание → глобальная таблица REPORTS.
- `/kill` — самоубийство.
- **/ad** (OP или `civ.admin`; все вызовы пишутся в adminlog):
  - `perm` (игнор прав участков), `sbperm` (ломать structure-блоки), `cbinstantbreak` (контрольный блок за один удар), `server`, `spawnunit <unit> <town>`, `chestreport <r>`, `playerreport`, `items` (GUI выдачи кастом-предметов), `clearendgame <key> <civ>`, `endworld`;
  - `town`: disband, claim, unclaim, hammerrate, addmayor, addassistant, rmmayor, rmassistant, tp, culture, info, setciv, select, claimradius, chestreport, rebuildgroups, capture, setmotherciv, sethappy, setunhappy, event, rename;
  - `civ`: disband, addleader, addadviser, rmleader, rmadviser, givetech, beakerrate, toggleadminciv, alltech, setrelation, info, merge, setgov, bankrupt, conquered, unconquer, liberate, setvotes, rename;
  - `war start|stop|resetstart|onlywarriors`; `lag trommels|towers|growth|trade|score|warning|blockupdate`; `camp destroy|setraidtime|rebuild`;
  - `chat tc|cc|cclisten|tclisten|listenoff|cclistenall|tclistenall|banwordon|banwordoff|banwordadd|banwordremove`;
  - `res settown|setcamp|cleartown|enchant|giveplat|givereward|rename`; `build demolish|repair|destroywonder|destroynearest|validatenearest|validateall|listinvalid|showbuildable`;
  - `item enhance|give`; `timer set|run`; `road setraidtime`; `arena list|end|messageall|message|enable|disable`; `perk give|remove|list`; `mob count|disable|enable|killall`;
  - `recover structures|listbroken|listorphantowns|listorphancivs|listorphanleaders|fixleaders|listorphanmayors|fixmayors|forcesaveresidents|forcesavetowns|forcesavecivs|listdefunctcivs|killdefunctcivs|listdefuncttowns|killdefuncttowns|listnocaptials|cleannocapitols|fixtownresidents`.
- **/dbg** (OP) — ~80 отладочных подкоманд (dupe, heal, setspeed, givebuff, fakeresidents, cannon и т. д.); не переносить.

### 3. Экономика, связанная с командами
- Налоги: flat tax (фикс в монетах) и property tax (`value` участка × rate). Мэры и ассистенты налогов не платят. Недостающая сумма забирается целиком, остальное становится долгом; при долге начинается отсчёт до выселения.
- Депозит в город сначала погашает долг города.
- Цены и настройки (`civ.yml`): `starting_coins` 250; `civ.cost` 100000; `town_upkeep` 500; дистанционный апкип 100 × 0.3 (0.9 вне культуры, максимум 500000); `gift_cost_per_town` 150000, `min_gift_age` 14 дней, `gift_cooldown_hours` 48.
- Опыт конвертируется в монеты 1:1 (`DisableXPListener`); XP-бутылки дают 0; столы зачарования и наковальни запрещены.

### 4. Дипломатия (`/civ dip`, лидер/советник; ничего не работает в WarTime)
- Статусы: NEUTRAL, PEACE, ALLY, HOSTILE, WAR.
- `declare <civ> hostile|war` — одностороннее объявление. HOSTILE нельзя, пока идёт война. WAR нельзя: в casual-режиме; в WarTime; за `time_declare_days` = 3 до войны (исключение — союзник помогает союзнику, если цель — агрессор против этого союзника, и до войны больше `ally_declare_hours` = 24 ч); при долге цивилизации; против admin-цивилизации. Объявивший записывается агрессором.
- `request <civ> neutral|peace|ally|war` — двусторонний запрос с таймаутом 30 с; WAR — только в casual. ALLY нельзя за 3 дня до войны, если одна из сторон воюет. Отвечают `respond yes|no` (лидер или советник адресата). Одновременно у цивилизации может быть только один входящий запрос.
- `gift entireciv <civ>` (лидер): влить свою цивилизацию в другую; принимающая платит `getMergeCost`; обе цивилизации не в войне; не в WarTime и не за 3 дня до неё; `validateGift` (возраст, кулдаун). `gift town <town> <civ>` (лидер): передать город (не столицу; захваченный — только его mother-civ); принимающая платит `getGiftCost`.
- `liberate <town>` (лидер владельца): вернуть захваченный город. Если это столица mother-civ, восстанавливается вся цивилизация со всеми её городами у этого владельца.
- `capitulate <town> [yes]` (лидер mother-civ): предложить владельцу окончательно принять город или, если город — столица, всю цивилизацию.
- `show [civ]`, `global`, `wars`.
- Сдача (SurrenderRequest) реализована, но не подключена к командам.

### 5. Война
- **Расписание**: `WarEvent` — раз в неделю, `war.time_day` = 7 (Calendar.SATURDAY), `time_hour` = 16 по часовому поясу сервера. Длительность `time_length` = 120 **минут** (комментарий в конфиге ошибочно говорит о часах). Конец отслеживает `WarEndCheckTask` раз в секунду. Во время войны создаётся файл-флаг `wartime` против cron-перезагрузки. Админ: `/ad war start|stop`.
- **Старт**: глобальное сообщение; игроки на чужой территории телепортируются в свою ратушу (`civ.repositionPlayers`); сбрасываются флаги `claimed` и `defeated` у городов; кикаются участники войны без CivCraft-античита (кроме OP и `civ.ac_exempt`); отключаются рост ферм, тромели и торговля; `onlywarriors` кикает всех, кто не в войне.
- **Пока идёт WarTime**:
  - Спавн мобов запрещён, кроме размножения.
  - Весь редстоун глобально гасится (`BlockRedstoneEvent` → 0); поршни отменяются.
  - Члены воюющих цивилизаций возрождаются в «военной комнате» столицы. Табличками там выбирается точка возрождения: ратуши не-`defeated` городов и War Camps. Время ожидания: 30 с + 30 с за каждую разрушенную контрольную точку + 10 мин, если структура невалидна, минус бафф medicine (минимум 1 с).
  - PvP (в городских чанках): разрешён, если цивилизация защищающегося воюет с цивилизацией атакующего — **в любое время, не только в WarTime** и в любой точке; в WarTime в городе воюющей цивилизации можно бить «нейтралов», а нейтрал ответить не может.
- **Блоки** — всегда при WarTime, если культура-владелец воюет с кем-либо:
  - В чужой **культуре**, вне town-чанка, ломать можно только dirt, grass, sand, gravel, факелы, редстоун, TNT, лестницы, лианы и не-solid блоки. Ставить можно только их же; блок над воздухом превращается в падающий. Правило действует на всех, включая собственных жителей.
  - В **town-чанке** цивилизации, с которой ты воюешь: блок без права destroy при ломании мгновенно исчезает (сохраняется в WarRegen, содержимое контейнера очищается); ставить можно всё, кроме лавы и воды (сохраняется как «воздух» и потом удаляется); двери открываются всегда; любой switch-блок без права interact при клике уничтожается.
  - Взрыв TNT или TNT-вагонетки даёт сферу радиусом 3 (`cannon.yield`/2), которая сохраняется и удаляется. Если ванильный взрыв задевает town-чанк или защищённый объект, взрыв отменяется целиком.
- **Структуры**: structure-блок получает урон при ударе, если WarTime, у здания `maxHitPoints` > 0, цивилизация атакующего воюет с владельцем и город атакующего не `defeated`. Урон 1 за удар (плюс бонусы от «Punchout»-зачарований предмета). Мгновенно ломающиеся блоки и неуязвимые structure-блоки урона не получают.
- **Ратуша и капитолий**: уязвимы только контрольные точки (забор + обсидиан). HP: ратуша 20, капитолий 100, лагерь 100 (`war.control_block_hitpoints_*`), War Camp 20. Бить контрольные блоки нельзя, если структура капитолия собственной цивилизации атакующего невалидна.
- **Захват**:
  - Все контрольные точки ратуши уничтожены → город «захвачен»: `defeated = true`, запись в SessionDB `capturedTown`.
  - Уничтожены все точки капитолия → захвачена вся цивилизация: у всех её городов `defeated`, ранее захваченное ею переходит победителю (`transferDefeated`), победителю — платина (`winningWar` 25 каждому жителю), сбрасываются условия победы проигравшего (`onCivilizationWarDefeat`).
  - Переход собственности происходит **в конце** войны (`processDefeated`): `Town.onDefeat` запоминает `motherCiv` и меняет цивилизацию; если город отбила его mother-civ, `motherCiv` снимается. `Civ.onDefeat` передаёт все города, удаляет все отношения, помечает цивилизацию conquered и убирает её в список завоёванных.
  - В casual-режиме захват городов не происходит; поражение цивилизации сбрасывает отношения в NEUTRAL, победитель получает голову лидера.
- **Конец**: восстанавливаются все блоки (по городам, WarCamps, Cannons), регенерируются контрольные блоки ратуш, игроки на вражеской территории телепортируются домой, объявляются лучший убийца и захваченные цивилизации, War Camps очищаются, рост, тромели и торговля включаются обратно.
- **Защита от манипуляций**: во время войны запрещены приглашения в город (и за 3 дня до войны для воюющих), перенос и активация структур, ремонт, refresh и validate зданий, вся дипломатия, покупки на рынке, подарки, революция.
- **После захвата**:
  - Захваченный город платит завоевателю (см. `show`: 0 апкипа, пока не капитулировал).
  - `/town capitulate` или `/civ dip capitulate` делает переход окончательным.
  - **Революция** (`/civ revolution yes`): только из столицы mother-civ; не в WarTime и не за 3 дня до неё; не раньше `revolution_cooldown` = 7 дней с завоевания; плата из казны столичного города = 50000 + 20000 за город + 0.1 за очко счёта (максимум 50 млн, `war.yml revolution.*`). Все города этой mother-civ возвращаются, а владельцам объявляется война, где агрессорами записаны владельцы.
- **Прочее (war.yml)**: `cooldown_time` 168 ч, `vassal_*` (не используется в коде этой области), `upkeep_per_war` 3000, `captured_penalty` 0.75, `invalid_hourly_penalty` 0.1, `logout_time` 120 с, `zombie_time` 30 с; башни: arrow — урон 7, дальность 100; cannon tower — урон 8, дальность 130; scout — дальность 400; стены — сегмент 50 монет, высота 6, максимальная высота 200.

### 6. Осада: War Camp и Cannon
- **War Camp** (предмет с компонентом `FoundWarCamp` → подтверждение в чате): лидер или советник; только в WarTime; максимум `warcamp.max` = 1 на цивилизацию; кулдаун повторной постройки 30 мин (`rebuild_timeout`, отсчёт и от постройки, и от уничтожения). Нельзя ставить в чанке без права destroy, на protected-, structure-, camp- и road-блоках, в farm- и wall-чанках, над сундуком, выше Y=200, далеко от поверхности (±10) и близко к спавну. Шаблон `warcamp`, изменённые блоки сохраняются в WarRegen. Точки возрождения `/respawn`; контрольные точки по 20 HP; когда все уничтожены, лагерь разрушается. Исчезает в конце войны.
- **Cannon** (предмет `BuildCannon`): только в WarTime, та же проверка рельефа и спавна (прав на землю **нет**). Управление табличками: YAW −35..35, шаг 1 (ЛКМ/ПКМ); PITCH 0..50; FIRE — загрузка TNT по 1 ЛКМ, нужно `tnt_cost` = 10; кулдаун 30 с; использовать могут только члены цивилизации владельца. Снаряд: шаг 1 блок/тик, гравитация −0.008, дальность до 300. При попадании блоки в радиусе 7 удаляются (кроме бедрока и защищённых structure-блоков). Structure-блоки получают `structure_damage` = 100; у ратуш урон идёт в HP, и при HP = 0 её блоки тоже ломаются. Игроки в радиусе получают 200 урона. HP пушки 10 — это удары от врагов, воюющих с цивилизацией владельца.

### 7. Лагеря (Camp, `camp.yml`)
- **Создание**: предмет `mat_found_camp` → имя в чате (только буквы) → шаблон `camp` (есть перк-шаблоны). Условия: не в культуре цивилизации; нет защищённых, structure- и camp-блоков; нет farm- и wall-чанков в зоне; дорожные блоки под лагерем удаляются; права destroy в town-чанках; Y < 200; поверхность ±10; не у спавна; не над сундуком. Создатель становится владельцем и членом. Состоять в лагере и в городе одновременно нельзя.
- **Защита**: блоки лагеря неразрушимы; члены могут ломать только «friendly» блоки (грядки сада). Не-члены не могут пользоваться switch-блоками и restricted-предметами в чанке лагеря. Двери лагеря не открываются редстоуном. Огонь, взрывы, горение и мобы (`EntityChangeBlock`/`BreakDoor`) лагерь не повреждают.
- **Огонь**: `firepoints` до 48 ч. Каждый час из печей «firefurnace» забирается 4 угля, это +1 час (не больше максимума); без угля −1 час, при < 30 % предупреждение, при < 0 лагерь уничтожается («fancy»: гравий и огонь).
- **Апгрейды** (по 500 монет с владельца):
  - **Sifter** — булыжник из входного сундука превращается с шансом 10 % в самородок, 2.5 % в железный слиток, иначе в гравий;
  - **Longhouse** — ежечасно ест хлеб (297) по уровням: 1 шт./5 циклов → 15 монет, 2/10 → 40, 4/15 → 80, 7/24 → 150. Монеты идут владельцу, плюс жетон `mat_token_of_leadership` с тегом владельца (из жетонов крафтится основание цивилизации);
  - **Garden** — ванильный рост на грядках.
- **Рейд**: контрольные точки по 100 HP (`war.control_block_hitpoints_camp`). Бить можно только в окне рейда: первое окно через 24 ч после создания, длительность `raid_length` = 2 ч, затем каждые 24 ч. Защищённые новички (pvp timer) бить не могут. Когда все точки уничтожены, лагерь разрушается.
- Если город заклеймит чанк с лагерем, лагерь распускается.

### 8. Арены (`arena.yml`)
- Команда: до 5 игроков. У всех онлайн-участников должен быть античит. Лидер ставит команду в очередь (`/team arena`).
- `ArenaManager` каждые 30 с берёт две команды из очереди, если свободна единственная инстанция (`MAX_INSTANCES` = 1), и выбирает случайную арену (desert, aztec, roman). Мир копируется из `arenas/<world_source>` в `<source>_instance_<id>`, FLAT, без автосохранения.
- Через 10 с игроки телепортируются на revive-точки; инвентарь сохраняется и очищается; ставится флаг `insideArena`; каждая команда видит свой scoreboard.
- Снаряжение лежит в «сундуках команды» — это эндер-сундуки, для каждого игрока свой набор: 3 комплекта вольфрамовой брони и мечей, marksmen bow, 3 комплекта composite leather, алмазная кирка, 6×64 стрел, 2×64 пирогов; всё soulbound + arena item.
- 4 контрольных блока на команду по 10 HP, 1 удар = 1 HP; свои бить нельзя. Когда уничтожены все блоки команды, она проигрывает. Таймаут 1800 с даёт ничью. Строить и ломать нельзя (кроме админов с sbperm), огонь запрещён.
- Смерть: возрождение на respawn-точках арены; через 30 с табличка «Respawn At Arena» телепортирует на revive-точку.
- **Рейтинг**: база 100 очков. Если победитель сильнее проигравшего на > 1000 очков — ×0.2; на > 400 — ×0.5; если проигравший сильнее на > 1000 — +80 %, на > 400 — +50 %. Победитель получает очки, проигравший теряет столько же.
- **Выход** (конец матча, вход в игру после окончания арены, отсутствие античита): восстановление инвентаря, телепорт домой.

### 9. Торговля между игроками и трейд-гуди
- **/trade**: окно 5×9 — ряд 0 кнопки чужой стороны, ряд 1 предложение партнёра (копия), ряды 2 и 4 служебные, ряд 3 своё предложение (9 слотов), в ряду 4 кнопки ±100 / ±1000 монет (shift) и кнопка подтверждения. Любое изменение снимает подтверждения обеих сторон. Сделка проходит, когда подтвердили оба, с проверкой, что зеркальные слоты совпадают и монет хватает; предметы и монеты обмениваются. Закрытие окна возвращает предметы из своего ряда.
- **Трейд-гуди (BonusGoodie)**: ванильный предмет с лором и именем (`goods.yml`: Silver = железный слиток, Gems = изумруд и т. д.).
  - Появляются на трейд-аванпостах.
  - Можно носить, класть в сундуки и двойные сундуки, вставлять в защищённые рамки ратуши (только мэр или ассистент этой цивилизации) — тогда город получает баффы гуди. Использовать, крафтить и класть в другие контейнеры нельзя.
  - При выходе игрока выпадают на землю; при деспавне, горении и выгрузке чанка возвращаются на аванпост; при входе удаляются из инвентаря.
  - Репо на аванпосты происходит по таймеру (`/civ time`: Next Trade Good Repo).
  - Защищённые рамки ломать нельзя; соседние блоки защищены от поршней.

### 10. Условия победы (`civ.yml end_conditions`; не в casual-режиме)
- Ежедневная проверка. Выполнил условие — пошёл счётчик `days_held`; до победы 21 день; ежечасное глобальное оповещение. Потерял условие — счётчик сбрасывается. Победитель записывается в `endgame:winningCiv`, после чего очки больше не считаются.
- **Cultural**: ≥ 3 города (не захваченных) с уровнем культуры ≥ 10 и ≥ 1 чудо у цивилизации.
- **Conquest**: не раньше 28 дней от старта игры; доля цивилизаций, чьи столицы принадлежат тебе, ≥ 75 % (50 % при активном Chichen Itza); сама цивилизация не завоёвана.
- **Scientific**: технология `tech_enlightenment` и активная Great Library; финальная проверка — больше всех накопленных «extra beakers». При военном поражении бикеры сгорают, а технология снимается.
- **Diplomatic**: активный Council of Eight; финальная проверка — больше всех голосов (`/vote`, 1 голос на жителя в 24 ч). При военном поражении Council of Eight сносится, голоса обнуляются.
- Захват столицы сбрасывает прогресс всех побед проигравшего.

### 11. Туториал
- Предмет `mat_tutorial_book` (`/res book`, стартовый кит). GUI «CivCraft Information» содержит:
  - «CivCraft Tutorial»: описание, квесты «Build a Camp» → «Found a Civ» с рецептами `mat_found_camp` и `mat_found_civ`;
  - «CivCraft Custom Item Recipes»: категории материалов и рецепты;
  - «Build Structure»: GUI-список построек.
- Стартовый кит (`civ.yml global.start_kit`): деревянные инструменты, 10 брёвен, 2 удочки, 10 печенек, компас, книга. Новичку 120 мин PvP-защиты (`pvp_timer`); досрочно снимается `/res pvptimer`.

### 12. Перки и платина (`perks.yml`; по умолчанию `system.enabled: false`)
- **Платина** — внешняя валюта в глобальной БД (`USER_PLATINUM`). Награды: `loginDaily` 5 (раз в 2 дня по коду), `inTownDuringUpkeep` 5, `winningWar` 25, `craft100Items` 1, `randomEventSuccess` 25, `loginFirstVerified` 50 (один раз), `buildCamp` 150 (один раз), `buildCiv` 200 (один раз).
- **Перки** (`USER_PERKS`, помечаются использованными в пределах «фазы» сервера):
  - `perk_rename_civ_town` — переименовать свой город (мэр или лидер) или цивилизацию (лидер);
  - `perk_sunny_weather` — ясная погода на 20 мин;
  - 5 тем (arctic, aztec, egyptian, hell, roman) × 23 шаблона. `CustomTemplate` навсегда привязывается к городу (по одному на тип) и доступен всем в городе через GUI постройки; `CustomPersonalTemplate` (лагерь, капитолий, ратуша) всегда активен у владельца.
- Выдача через `/ad perk give|remove`, `/ad res giveplat|givereward`.

### 13. Правила защиты (`BlockListener`, `PlayerListener`, `CustomItemManager`, `WarListener`, `ArenaListener`)
- **Модель участка** (`PlotPermissions`): 4 права — build, destroy, interact, itemuse — для owner, group и others; плюс флаги mobs и fire. Действует в town-чанках; дикая местность и культура вне town-чанков не защищены (кроме правил войны и лавы).
- **Всегда неразрушимы** игроками, огнём, горением, взрывами, поршнями, эндерменами и мобами: structure-блоки (в войну их можно повреждать по правилам выше), дорожные блоки (в войну «разрушаются» с регенерацией, в мире удар наносит урон дороге), camp-блоки, таблички и сундуки структур, protected-блоки, защищённые участки стен (не-члены цивилизации наносят урон стене как структуре), блоки под защищёнными рамками.
- Нельзя ставить блоки над дорогой чужой цивилизации (до `Road.HEIGHT−1`).
- **Установка и ломание в town-чанке** — по правам build и destroy; исключения — FIRE и PORTAL. Нельзя ломать блоки, если после этого слой под постройкой станет ниже `validPercentRequirement` поддержки.
- **Interact** (switch-блоки: сундуки, печи, двери, люки, калитки, кнопки кроме деревянной, рычаги, плиты, воронки, раздатчики, повторители, компараторы, наковальни, маяки, варочные стойки, котлы, торты, TNT, jukebox) — по праву interact. Не-игроки (мобы, стрелы) не могут активировать switch-блоки, если у «others» нет права interact. Двойные сундуки при открытии проверяются по обеим половинам.
- **Itemuse** (огниво, вёдра, торт, котёл, повторитель, краска и костная мука, рамки, картины, ножницы, TNT) — по праву itemuse, по координате кликнутого блока.
- Рамки и картины: поворот — interact, ломание — destroy.
- **Огонь**: поджог в town-чанке с `fire = false` запрещён; распространение огня запрещено, если fire выключен.
- **Мобы**: при `mobs = false` враждебные мобы в town-чанке не спавнятся (кроме CUSTOM). Глобально запрещены ванильные зомби, скелеты, пауки, криперы, волки, сильверфиши, оцелоты, ведьмы, эндермены, летучие мыши (кроме MobLib), спавнеры, железные големы из блоков, куры из яиц, лошади вне конюшен.
- **Взрывы**: если взрыв задевает town-чанк, structure-, road- или camp-блок, стену, табличку или сундук структуры, он отменяется целиком. Эндер-дракон блоки не ломает.
- **Лава**: вне своей культуры не ставится (проверка по кликнутому блоку). Генераторы булыжника подавляются: соседний источник жидкости превращается в незерак, `BlockForm` булыжника превращается в гравий. Порталы в Nether и End отключены, создание порталов запрещено.
- **Предметы**: запрещены notch-яблоки и золотые яблоки, зелья невидимости (в т. ч. из раздатчиков), раздатчики с краской. Сплэш-зелья разрешены только скорость, огнестойкость и лечение. Пить можно только зелья из технологического списка при наличии технологии. Варка с паучьим глазом, золотой морковью, слезой гаста, ферментированным глазом, огненным порошком и порохом, а также из mundane/thick заблокирована. Столы зачарований и наковальни отключены. Ванильное оружие наносит 0.5 урона — полноценно бьют только кастом-предметы. Лазуритовая руда (без шёлкового касания) даёт `mat_tungsten_ore`. Нельзя вытаптывать грядки. Нельзя использовать костную муку на пшенице, моркови и картофеле. Разводить животных можно только в Pasture. Лошади — только из конюшни.
- **PvP**:
  - в дикой местности разрешён всегда;
  - в town-чанке разрешён, если один из двоих объявлен вне закона в этом городе; или цивилизации в войне (в любое время); или в WarTime жертва — нейтрал в городе воюющей цивилизации; иначе запрещён. Та же логика применяется к «вредным» сплэш-зельям;
  - новички под PvP-защитой не бьют и не получают урон от ударов и стрел.
- **PvP-логгер**: игрок, получивший удар или стрелу от игрока, 120 с «помечен». Выход в это время создаёт NPC-зомби с его именем на 30 с. Убийство зомби выбрасывает весь не-soulbound инвентарь и броню из offline-файла игрока, а при следующем входе игрок умирает (`pvplogger:death`). Вход в игру удаляет зомби.
- **Смерть**: soulbound-предметы остаются у игрока; unit-предметы не выпадают.
- **Арена-мир**: ломать и ставить нельзя, огонь запрещён.

### 14. Прочее
- **Античит CivCraft** (`nocheat.yml`): клиентский мод отвечает на DES-challenge списком модов и контрольных сумм, сверяемым с `validMods`. Требуется для участия в войне и на арене. NCP-хук гасит мелкие нарушения SurvivalFly (`violation_grace`).
- **Очки** (`score.yml`): раз в минуту пересчёт очков городов и цивилизаций (для top5) и отправка в глобальную БД (`SCORES_TOWNS` / `SCORES_CIVS`).
- **SLS**: UDP-heartbeat на `atlas.civcraft.net:25580` раз в минуту — не переносить.
- **Жалобы**: `/report` пишет в глобальную таблицу `REPORTS` (LANGUAGE, EXPLOITING, HARASSMENT, CHEATING, OTHER).
- **Чат**: town-чат, civ-чат, прослушка чатов админами, бан-слова (зашиты в код), интеграции HeroChat ([Far]-дублирование для allchatters) и TagAPI.
