package com.civcraft.resident;

import static com.civcraft.resident.CmdKit.arg;
import static com.civcraft.resident.CmdKit.lit;
import static com.civcraft.resident.CmdKit.word;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.core.ui.Prompts;
import com.civcraft.core.util.Durations;
import com.civcraft.core.util.Money;
import com.civcraft.economy.Amounts;
import com.civcraft.economy.Ledger;
import com.civcraft.gui.Items;
import com.civcraft.gui.Menu;
import com.civcraft.item.ItemApi;
import com.civcraft.item.RecipeBookApi;
import com.civcraft.model.Camp;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.WrittenBookContent;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Player-level features (spec §3, §19.3, §19.6): /resident, /money, /pay, /accept, /deny, friends,
 * settings, starter kit, guide book, Russian keyboard layout conversion, respawn at the town hall.
 */
public final class ResidentModule implements Module, Listener {

    public static final String COOLDOWN_TOWN_TP = "town-teleport";
    public static final String COOLDOWN_CAMP_TP = "camp-teleport";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private CivCraft civ;
    private final Requests requests = new Requests();
    private Teleports teleports;
    private final Map<UUID, ResidentData> data = new HashMap<>();
    private final Map<UUID, Instant> lastPay = new HashMap<>();

    @Override
    public String id() {
        return "resident";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("resident");
        civ.store().createCollection(ResidentData.COLLECTION);
        for (ResidentData d : civ.store().loadAll(ResidentData.COLLECTION, ResidentData.class)) data.put(d.uuid(), d);
    }

    @Override
    public void enable(CivCraft civ) {
        teleports = new Teleports(civ);
        civ.listen(teleports);
        civ.listen(this);
        civ.clock().everyMinute("requests-purge", requests::purge);
        CmdKit.register(civ, this::residentCommand, "Resident commands", List.of("res"));
        CmdKit.register(civ, () -> lit("money").executes(Cmd.player((p, ctx) -> money(p))), "Balance", List.of("balance"));
        CmdKit.register(civ, this::payCommand, "Pay a player", List.of());
        CmdKit.register(civ, () -> answerCommand("accept", true), "Accept a request", List.of("yes"));
        CmdKit.register(civ, () -> answerCommand("deny", false), "Deny a request", List.of("no"));
    }

    public Requests requests() {
        return requests;
    }

    public Teleports teleports() {
        return teleports;
    }

    /** Module data of a player, created on demand. */
    public ResidentData data(UUID uuid) {
        return data.computeIfAbsent(uuid, ResidentData::new);
    }

    public void save(ResidentData d) {
        civ.saves().save(ResidentData.COLLECTION, d);
    }

    /** Whether {@code friend} is a friend of {@code owner} (personal, whole-town or whole-civ friendship). */
    public boolean isFriend(UUID owner, UUID friend) {
        Resident o = civ.state().resident(owner);
        if (o == null) return false;
        if (o.friends().contains(friend)) return true;
        ResidentData d = data.get(owner);
        if (d == null) return false;
        Town t = civ.state().townOf(civ.state().resident(friend));
        if (t == null) return false;
        return d.friendTowns().contains(t.id()) || (t.civId() != null && d.friendCivs().contains(t.civId()));
    }

    /** Checks and records a cooldown; throws with the remaining time. */
    public void checkCooldown(Player player, String key, Duration cooldown) throws CivException {
        Instant last = data(player.getUniqueId()).cooldown(key);
        if (last != null) {
            Duration left = Duration.between(Instant.now(), last.plus(cooldown));
            if (!left.isNegative() && !left.isZero()) {
                throw new CivException("resident.cooldown", Messages.arg("time", Durations.format(left)));
            }
        }
    }

    public void markCooldown(Player player, String key) {
        ResidentData d = data(player.getUniqueId());
        d.cooldown(key, Instant.now());
        save(d);
    }

    // --- commands -------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> answerCommand(String name, boolean accept) {
        return lit(name)
                .executes(Cmd.player((p, ctx) -> requests.answer(p, -1, accept)))
                .then(Cmd.arg("id", LongArgumentType.longArg(1))
                        .executes(Cmd.player((p, ctx) -> requests.answer(p, LongArgumentType.getLong(ctx, "id"), accept))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> payCommand() {
        return lit("pay").then(word("player").suggests(CmdKit.players()).then(word("amount")
                .executes(Cmd.player((p, ctx) -> pay(p, arg(ctx, "player"), arg(ctx, "amount"))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> residentCommand() {
        LiteralArgumentBuilder<CommandSourceStack> root = lit("resident")
                .executes(Cmd.player((p, ctx) -> info(p, resident(p))))
                .then(lit("help").executes(CmdKit.help("resident.help")))
                .then(lit("info").executes(Cmd.player((p, ctx) -> info(p, resident(p)))))
                .then(lit("show").then(word("player").suggests(CmdKit.players())
                        .executes(Cmd.player((p, ctx) -> info(p, Lookup.resident(arg(ctx, "player")))))))
                .then(lit("book").executes(Cmd.player((p, ctx) -> giveBook(p))))
                .then(lit("recipes").executes(Cmd.player((p, ctx) -> recipes(p, null))))
                .then(lit("cmat").then(CmdKit.text("material").executes(Cmd.player((p, ctx) -> recipes(p, arg(ctx, "material"))))))
                .then(lit("exchange").then(word("material").suggests(CmdKit.values(exchangeMaterials()))
                        .then(word("count").executes(Cmd.player((p, ctx) -> exchange(p, arg(ctx, "material"), arg(ctx, "count")))))))
                .then(lit("paydebt").executes(Cmd.player((p, ctx) -> payDebt(p))))
                .then(lit("pvptimer").executes(Cmd.player((p, ctx) -> pvpTimer(p))))
                .then(lit("resetspawn").executes(Cmd.player((p, ctx) -> resetSpawn(p))))
                .then(friends())
                .then(lit("outlawed").executes(Cmd.player((p, ctx) -> outlawed(p))))
                .then(lit("outlaws").executes(Cmd.player((p, ctx) -> outlawed(p))))
                .then(lit("set").executes(Cmd.player((p, ctx) -> new SettingsMenu().open(p)))
                        .then(word("setting").suggests(CmdKit.values(settingKeys()))
                                .executes(Cmd.player((p, ctx) -> toggle(p, arg(ctx, "setting"))))))
                .then(lit("toggle").executes(Cmd.player((p, ctx) -> new SettingsMenu().open(p)))
                        .then(lit("chat").then(word("channel").suggests(CmdKit.values(List.of("civ", "town", "camp", "ally")))
                                .executes(Cmd.player((p, ctx) -> toggle(p, "hide-chat-" + arg(ctx, "channel").toLowerCase(Locale.ROOT))))))
                        .then(word("setting").suggests(CmdKit.values(settingKeys()))
                                .executes(Cmd.player((p, ctx) -> toggle(p, arg(ctx, "setting"))))))
                .then(lit("settings").executes(Cmd.player((p, ctx) -> new SettingsMenu().open(p))));
        for (com.civcraft.Module m : civ.modules()) {
            if (m instanceof ResidentCommandExtension ext) ext.extendResident(root);
        }
        return root;
    }

    private LiteralArgumentBuilder<CommandSourceStack> friends() {
        return lit("friends")
                .executes(Cmd.player((p, ctx) -> listFriends(p)))
                .then(lit("list").executes(Cmd.player((p, ctx) -> listFriends(p))))
                .then(lit("add")
                        .then(lit("civ").then(word("civ").suggests(CmdKit.civs())
                                .executes(Cmd.player((p, ctx) -> friendGroup(p, true, true, arg(ctx, "civ"))))))
                        .then(lit("town").then(word("town").suggests(CmdKit.towns())
                                .executes(Cmd.player((p, ctx) -> friendGroup(p, false, true, arg(ctx, "town"))))))
                        .then(word("player").suggests(CmdKit.players())
                                .executes(Cmd.player((p, ctx) -> friend(p, arg(ctx, "player"), true)))))
                .then(lit("remove")
                        .then(lit("civ").then(word("civ").suggests(CmdKit.civs())
                                .executes(Cmd.player((p, ctx) -> friendGroup(p, true, false, arg(ctx, "civ"))))))
                        .then(lit("town").then(word("town").suggests(CmdKit.towns())
                                .executes(Cmd.player((p, ctx) -> friendGroup(p, false, false, arg(ctx, "town"))))))
                        .then(word("player").suggests(CmdKit.players())
                                .executes(Cmd.player((p, ctx) -> friend(p, arg(ctx, "player"), false)))));
    }

    private Resident resident(Player p) throws CivException {
        Resident r = civ.state().resident(p);
        if (r == null) throw new CivException("error.internal");
        return r;
    }

    private void info(Player viewer, Resident r) {
        Messages m = civ.messages();
        ZoneId zone = civ.clock().zone();
        Town town = civ.state().townOf(r);
        Civilization c = civ.state().civOf(town);
        Camp camp = civ.state().camp(r.campId());
        boolean self = viewer.getUniqueId().equals(r.uuid());
        boolean online = org.bukkit.Bukkit.getPlayer(r.uuid()) != null;
        m.sendRaw(viewer, "resident.info.header", Messages.arg("name", r.name()));
        m.sendRaw(viewer, "resident.info.registered", Messages.arg("date", DATE.format(r.firstJoin().atZone(zone))));
        m.sendRaw(viewer, online ? "resident.info.online" : "resident.info.last-seen",
                Messages.arg("date", DATE.format(r.lastSeen().atZone(zone))));
        m.sendRaw(viewer, "resident.info.town", Messages.arg("town", town == null ? m.plain("resident.none") : town.name()),
                Messages.arg("civ", c == null ? m.plain("resident.none") : c.name()));
        if (camp != null) m.sendRaw(viewer, "resident.info.camp", Messages.arg("camp", camp.name()));
        if (self || viewer.hasPermission("civcraft.admin")) {
            m.sendRaw(viewer, "resident.info.balance", Messages.money("amount", r.balance()));
            if (r.debt() > 0) m.sendRaw(viewer, "resident.info.debt", Messages.money("amount", r.debt()));
            if (r.isPvpProtected()) {
                m.sendRaw(viewer, "resident.info.pvp", Messages.arg("time", Durations.format(Duration.ofSeconds(r.pvpProtectionSeconds()))));
            }
            m.sendRaw(viewer, "resident.info.friends", Messages.arg("count", r.friends().size()));
        }
    }

    private void money(Player p) throws CivException {
        Resident r = resident(p);
        civ.messages().send(p, "resident.money", Messages.money("amount", r.balance()));
        if (r.debt() > 0) civ.messages().send(p, "resident.money-debt", Messages.money("amount", r.debt()));
    }

    private void pay(Player p, String targetName, String amountText) throws CivException {
        Resident from = resident(p);
        Resident to = Lookup.resident(targetName);
        long cents = Amounts.require(amountText);
        Duration cooldown = Duration.ofSeconds(civ.balance().getInt("core", "resident.pay-cooldown-seconds", 5));
        Instant last = lastPay.get(p.getUniqueId());
        if (last != null && last.plus(cooldown).isAfter(Instant.now())) {
            throw new CivException("resident.cooldown", Messages.arg("time",
                    Durations.format(Duration.between(Instant.now(), last.plus(cooldown)))));
        }
        Ledger.pay(from, to, cents);
        lastPay.put(p.getUniqueId(), Instant.now());
        civ.messages().send(p, "resident.pay.sent", Messages.money("amount", cents), Messages.arg("name", to.name()));
        Player target = org.bukkit.Bukkit.getPlayer(to.uuid());
        if (target != null) {
            civ.messages().send(target, "resident.pay.received", Messages.money("amount", cents), Messages.arg("name", from.name()));
        }
    }

    // --- book & recipes -------------------------------------------------------------------------

    private void giveBook(Player p) {
        p.getInventory().addItem(book()).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        civ.messages().send(p, "resident.book.given");
    }

    /** The guide book (spec §1 starter kit, §19.3 /res book). */
    public ItemStack book() {
        Messages m = civ.messages();
        ItemStack book = ItemStack.of(Material.WRITTEN_BOOK);
        List<Component> pages = new ArrayList<>(m.lines("resident.book.pages"));
        // Every "<page>" marker in the list starts a new page; lines in between are joined.
        List<Component> joined = new ArrayList<>();
        Component current = Component.empty();
        boolean empty = true;
        for (Component line : pages) {
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line);
            if (plain.equals("---")) {
                joined.add(current);
                current = Component.empty();
                empty = true;
                continue;
            }
            current = empty ? line : current.append(Component.newline()).append(line);
            empty = false;
        }
        if (!empty) joined.add(current);
        book.setData(DataComponentTypes.WRITTEN_BOOK_CONTENT, WrittenBookContent
                .writtenBookContent(m.plain("resident.book.title"), m.plain("resident.book.author"))
                .addPages(joined).build());
        return book;
    }

    private void recipes(Player p, String query) throws CivException {
        RecipeBookApi api = civ.apiOrNull(RecipeBookApi.class);
        if (api == null) throw new CivException("resident.recipes-unavailable");
        api.openRecipeBook(p);
    }

    // --- exchange -------------------------------------------------------------------------------

    private List<String> exchangeMaterials() {
        return new ArrayList<>(civ.balance().section("resident", "exchange.prices").getKeys(false));
    }

    private void exchange(Player p, String materialName, String countText) throws CivException {
        ConfigurationSection prices = civ.balance().section("resident", "exchange.prices");
        String key = materialName.toLowerCase(Locale.ROOT);
        if (!prices.isConfigurationSection(key)) throw new CivException("resident.exchange.unknown");
        Material material = Material.matchMaterial(prices.getString(key + ".material", ""));
        double base = prices.getDouble(key + ".price");
        if (material == null || base <= 0) throw new CivException("resident.exchange.unknown");
        ItemApi items = civ.apiOrNull(ItemApi.class);
        PlayerInventory inv = p.getInventory();
        ItemStack plain = ItemStack.of(material);
        // Only plain vanilla stacks count: custom items, trade goods and renamed items are never eaten.
        int available = 0;
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getStorageContents().length; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || !s.isSimilar(plain)) continue;
            if (items != null && items.id(s) != null) continue;
            available += s.getAmount();
            slots.add(i);
        }
        int count = countText.equalsIgnoreCase("all") || countText.equalsIgnoreCase("все") ? available
                : Amounts.count(countText, 64 * 36);
        if (count <= 0) throw new CivException("error.invalid-amount");
        if (available < count) throw new CivException("resident.exchange.not-enough", Messages.arg("count", count));
        double rate = civ.balance().getDouble("core", "resident.exchange-rate", 0.30);
        long cents = Money.ofCoins(base * rate * count);
        if (cents <= 0) throw new CivException("error.invalid-amount");
        int left = count;
        for (int slot : slots) {
            if (left == 0) break;
            ItemStack s = inv.getItem(slot);
            int take = Math.min(left, s.getAmount());
            if (take == s.getAmount()) inv.setItem(slot, null);
            else s.setAmount(s.getAmount() - take);
            left -= take;
        }
        Ledger.credit(resident(p), cents);
        civ.messages().send(p, "resident.exchange.done", Messages.arg("count", count),
                Messages.arg("item", key), Messages.money("amount", cents));
    }

    // --- debt, pvp, spawn -----------------------------------------------------------------------

    private void payDebt(Player p) throws CivException {
        Resident r = resident(p);
        if (r.debt() <= 0) throw new CivException("resident.debt.none");
        long pay = Math.min(r.debt(), r.balance());
        if (pay <= 0) throw new CivException("error.not-enough-money", Messages.money("amount", r.debt()));
        Town town = civ.state().townOf(r);
        Ledger.charge(r, pay);
        r.debt(r.debt() - pay);
        civ.state().save(r);
        if (town != null) Ledger.creditTown(town, pay);
        civ.messages().send(p, "resident.debt.paid", Messages.money("amount", pay), Messages.money("left", r.debt()));
    }

    private void pvpTimer(Player p) throws CivException {
        Resident r = resident(p);
        if (!r.isPvpProtected()) throw new CivException("resident.pvp.none");
        Messages m = civ.messages();
        Prompts.confirm(p, m.component("resident.pvp.confirm-title"), m.lines("resident.pvp.confirm-body"),
                m.component("prompt.yes"), m.component("prompt.no"), player -> {
                    Resident res = civ.state().resident(player);
                    if (res == null || !res.isPvpProtected()) return;
                    res.pvpProtectionSeconds(0);
                    civ.state().save(res);
                    m.send(player, "resident.pvp.removed");
                });
    }

    private void resetSpawn(Player p) {
        p.setRespawnLocation(null);
        civ.messages().send(p, "resident.spawn-reset");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onRespawn(PlayerRespawnEvent e) {
        if (!civ.balance().file("resident").getBoolean("respawn-at-town-hall", true)) return;
        if (e.isBedSpawn() || e.isAnchorSpawn()) return;
        Town town = civ.state().townOf(e.getPlayer());
        if (town == null || town.center() == null || town.center().bukkitWorld() == null) return;
        Location c = town.center().center();
        int y = c.getWorld().getHighestBlockYAt(c.getBlockX(), c.getBlockZ()) + 1;
        e.setRespawnLocation(new Location(c.getWorld(), c.getX(), y, c.getZ()));
    }

    // --- friends --------------------------------------------------------------------------------

    private void listFriends(Player p) throws CivException {
        Resident r = resident(p);
        ResidentData d = data(p.getUniqueId());
        List<String> names = new ArrayList<>();
        for (UUID id : r.friends()) {
            Resident f = civ.state().resident(id);
            if (f != null) names.add(f.name());
        }
        for (String id : d.friendTowns()) {
            Town t = civ.state().town(id);
            if (t != null) names.add(civ.messages().plain("resident.friends.town-entry", Messages.arg("name", t.name())));
        }
        for (String id : d.friendCivs()) {
            Civilization c = civ.state().civ(id);
            if (c != null) names.add(civ.messages().plain("resident.friends.civ-entry", Messages.arg("name", c.name())));
        }
        civ.messages().send(p, "resident.friends.list", Messages.arg("list", names.isEmpty()
                ? civ.messages().plain("resident.none") : String.join(", ", names)));
    }

    private void friend(Player p, String name, boolean add) throws CivException {
        Resident r = resident(p);
        Resident f = Lookup.resident(name);
        if (f.uuid().equals(r.uuid())) throw new CivException("resident.friends.self");
        if (add) {
            int max = civ.balance().getInt("resident", "friends.max", 50);
            if (r.friends().size() >= max) throw new CivException("resident.friends.limit", Messages.arg("max", max));
            if (!r.friends().add(f.uuid())) throw new CivException("resident.friends.already", Messages.arg("name", f.name()));
        } else if (!r.friends().remove(f.uuid())) {
            throw new CivException("resident.friends.not-friend", Messages.arg("name", f.name()));
        }
        civ.state().save(r);
        civ.messages().send(p, add ? "resident.friends.added" : "resident.friends.removed", Messages.arg("name", f.name()));
    }

    private void friendGroup(Player p, boolean isCiv, boolean add, String name) throws CivException {
        ResidentData d = data(p.getUniqueId());
        String id;
        String display;
        Set<String> set;
        if (isCiv) {
            Civilization c = Lookup.civ(name);
            id = c.id();
            display = c.name();
            set = d.friendCivs();
        } else {
            Town t = Lookup.town(name);
            id = t.id();
            display = t.name();
            set = d.friendTowns();
        }
        boolean changed = add ? set.add(id) : set.remove(id);
        if (!changed) throw new CivException(add ? "resident.friends.already" : "resident.friends.not-friend",
                Messages.arg("name", display));
        save(d);
        civ.messages().send(p, add ? "resident.friends.added" : "resident.friends.removed", Messages.arg("name", display));
    }

    private void outlawed(Player p) {
        List<String> towns = new ArrayList<>();
        for (Town t : civ.state().towns()) if (t.outlaws().contains(p.getUniqueId())) towns.add(t.name());
        if (towns.isEmpty()) civ.messages().send(p, "resident.outlawed.none");
        else civ.messages().send(p, "resident.outlawed.list", Messages.arg("list", String.join(", ", towns)));
    }

    // --- settings -------------------------------------------------------------------------------

    private List<String> settingKeys() {
        return civ.balance().file("resident").getStringList("settings");
    }

    private void toggle(Player p, String key) throws CivException {
        String k = key.toLowerCase(Locale.ROOT);
        boolean chat = k.startsWith("hide-chat-") && List.of("civ", "town", "camp", "ally").contains(k.substring(10));
        if (!chat && !settingKeys().contains(k)) throw new CivException("resident.settings.unknown");
        Resident r = resident(p);
        boolean value = !r.setting(k);
        r.setting(k, value);
        civ.state().save(r);
        civ.messages().send(p, value ? "resident.settings.on" : "resident.settings.off",
                Messages.arg("setting", settingName(k)));
    }

    private String settingName(String key) {
        String k = "resident.settings.name." + key;
        return civ.messages().has(k) ? civ.messages().plain(k) : key;
    }

    /** GUI with all toggles (spec §19.3 /res settings). */
    private final class SettingsMenu extends Menu {
        SettingsMenu() {
            super(3, Messages.get().component("resident.settings.title"));
        }

        @Override
        protected void render(Player viewer) {
            Resident r = civ.state().resident(viewer);
            if (r == null) return;
            List<String> keys = new ArrayList<>(settingKeys());
            for (String ch : List.of("civ", "town", "camp", "ally")) keys.add("hide-chat-" + ch);
            int slot = 0;
            for (String key : keys) {
                if (slot >= size()) break;
                boolean on = r.setting(key);
                String k = key;
                set(slot++, Items.of(on ? Material.LIME_DYE : Material.GRAY_DYE)
                        .name(Messages.get().component(on ? "resident.settings.item-on" : "resident.settings.item-off",
                                Messages.arg("setting", settingName(key)))).build(), e -> {
                    try {
                        toggle(viewer, k);
                    } catch (CivException ex) {
                        Messages.get().send(viewer, ex.key(), ex.args());
                    }
                    refresh(viewer);
                });
            }
        }
    }

    // --- listeners ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        ResidentData d = data(p.getUniqueId());
        if (d.kitGiven()) return;
        d.kitGiven(true);
        save(d);
        // Players that existed before this module was installed do not get a starter kit.
        Resident r = civ.state().resident(p);
        if (r != null && r.firstJoin().plus(Duration.ofMinutes(5)).isBefore(Instant.now())) return;
        ItemApi items = civ.apiOrNull(ItemApi.class);
        for (Map<?, ?> entry : civ.balance().file("resident").getMapList("starter-kit")) {
            Material mat = Material.matchMaterial(String.valueOf(entry.get("material")));
            if (mat == null) continue;
            int amount = entry.get("amount") instanceof Number n ? n.intValue() : 1;
            ItemStack stack = ItemStack.of(mat, Math.max(1, amount));
            if (Boolean.TRUE.equals(entry.get("soulbound")) && items != null) items.soulbind(stack, true);
            p.getInventory().addItem(stack).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        }
        ItemStack guide = book();
        if (items != null) items.soulbind(guide, true);
        p.getInventory().addItem(guide);
    }

    /**
     * Converts commands typed in the Russian keyboard layout ({@code /сшм} → {@code /civ}). Arguments
     * are converted only when the result is a known literal or an existing town/civ/player/camp name,
     * so free text (MOTD, chat) is never mangled.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage();
        if (msg.length() < 2 || !Layout.hasCyrillic(msg)) return;
        String[] parts = msg.substring(1).split(" ", -1);
        boolean changed = false;
        if (Layout.hasCyrillic(parts[0])) {
            String label = Layout.toLatin(parts[0]).toLowerCase(Locale.ROOT);
            if (CmdKit.isLiteral(label) || org.bukkit.Bukkit.getCommandMap().getKnownCommands().containsKey(label)) {
                parts[0] = label;
                changed = true;
            }
        }
        if (!changed && !CmdKit.isLiteral(parts[0].toLowerCase(Locale.ROOT))) return;
        for (int i = 1; i < parts.length; i++) {
            String token = parts[i];
            if (token.isEmpty() || !Layout.hasCyrillic(token)) continue;
            String latin = Layout.toLatin(token);
            String lower = latin.toLowerCase(Locale.ROOT);
            if (CmdKit.isLiteral(lower)) {
                parts[i] = lower;
                changed = true;
            } else if (civ.state().townByName(latin) != null || civ.state().civByName(latin) != null
                    || civ.state().residentByName(latin) != null || civ.state().campByName(latin) != null) {
                parts[i] = latin;
                changed = true;
            }
        }
        if (changed) e.setMessage("/" + String.join(" ", parts));
    }
}
