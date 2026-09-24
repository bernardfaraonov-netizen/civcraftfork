package com.civcraft.space;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.BlockPos;
import com.civcraft.effect.Stats;
import com.civcraft.event.SpaceMissionCompletedEvent;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import com.civcraft.model.TownStatus;
import com.civcraft.science.CivCommandGraft;
import com.civcraft.science.CivItems;
import com.civcraft.science.CivPerms;
import com.civcraft.science.WonderIndex;
import com.civcraft.structure.StructureApi;
import com.civcraft.town.TownApi;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Space missions and the Space Shuttle launch flow (spec 03 §9). */
public final class SpaceModule implements Module, SpaceApi {

    public static final String COLLECTION = "space";
    public static final String REQUIREMENTS_STAT = "space_requirements";

    record Mission(int number, String name, String rocket, double hammers, double beakers,
                   Map<String, Integer> components, Map<String, Integer> rewards) {
    }

    private CivCraft civ;
    private WonderIndex wonders;
    private final List<Mission> missions = new ArrayList<>();
    private final Map<String, String> itemNames = new HashMap<>();
    private final Map<String, Map<String, Integer>> recipes = new LinkedHashMap<>();
    private final Map<String, SpaceState> states = new HashMap<>();

    @Override
    public String id() {
        return "space";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("space");
        ConfigurationSection ms = civ.balance().section("space", "missions");
        for (String key : ms.getKeys(false)) {
            ConfigurationSection s = ms.getConfigurationSection(key);
            if (s == null) continue;
            missions.add(new Mission(Integer.parseInt(key), s.getString("name", key), s.getString("rocket", ""),
                    Math.max(0, s.getDouble("hammers")), Math.max(0, s.getDouble("beakers")),
                    intMap(s.getConfigurationSection("components")), intMap(s.getConfigurationSection("rewards"))));
        }
        missions.sort((a, b) -> Integer.compare(a.number(), b.number()));
        ConfigurationSection rs = civ.balance().section("space", "recipes");
        for (String key : rs.getKeys(false)) recipes.put(key, Collections.unmodifiableMap(intMap(rs.getConfigurationSection(key))));
        civ.store().createCollection(COLLECTION);
        for (SpaceState st : civ.store().loadAll(COLLECTION, SpaceState.class)) {
            if (st.civId() == null || civ.state().civ(st.civId()) == null) {
                if (st.civId() != null) civ.saves().delete(COLLECTION, st.civId());
                continue;
            }
            st.repair();
            states.put(st.civId(), st);
        }
    }

    private static Map<String, Integer> intMap(ConfigurationSection s) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (s == null) return map;
        for (String k : s.getKeys(false)) {
            int v = s.getInt(k);
            if (v > 0) map.put(k, v);
        }
        return map;
    }

    @Override
    public void enable(CivCraft civ) {
        wonders = new WonderIndex(civ);
        CivItems items = CivItems.get(civ);
        ConfigurationSection is = civ.balance().section("space", "items");
        for (String id : is.getKeys(false)) {
            ConfigurationSection s = is.getConfigurationSection(id);
            if (s == null) continue;
            Material m = Material.matchMaterial(s.getString("material", "PAPER"));
            String name = s.getString("name", id);
            itemNames.put(id, name);
            items.define(id, m == null ? Material.PAPER : m, Component.text(name),
                    List.of(civ.messages().component("space.item-lore")), 64);
        }
        civ.clock().everyMinute("space-missions", this::tick);
        CivCommandGraft.add(civ, this::command);
    }

    // --- state ------------------------------------------------------------------------------------

    SpaceState state(Civilization c) {
        return states.computeIfAbsent(c.id(), id -> {
            SpaceState st = new SpaceState(id);
            civ.saves().save(COLLECTION, st);
            return st;
        });
    }

    private void save(SpaceState st) {
        civ.saves().save(COLLECTION, st);
    }

    CivCraft civ() {
        return civ;
    }

    List<Mission> missions() {
        return missions;
    }

    Mission mission(int number) {
        for (Mission m : missions) if (m.number() == number) return m;
        return null;
    }

    String itemName(String id) {
        return itemNames.getOrDefault(id, id);
    }

    /** Requirement multiplier (talent 5.2 and other modifiers of {@code space_requirements}). */
    double requirementFactor(Civilization c) {
        return Math.max(0, 1 + civ.stats().civ(c).percent(REQUIREMENTS_STAT));
    }

    double requiredHammers(Civilization c, Mission m) {
        return m.hammers() * requirementFactor(c);
    }

    double requiredBeakers(Civilization c, Mission m) {
        return m.beakers() * requirementFactor(c);
    }

    // --- SpaceApi ---------------------------------------------------------------------------------

    @Override
    public int completedMissions(Civilization c) {
        return state(c).completed();
    }

    @Override
    public int missionCount() {
        return missions.size();
    }

    @Override
    public boolean missionActive(Civilization c) {
        return state(c).current() > 0 && hasShuttleInCapital(c);
    }

    @Override
    public void addMissionBeakers(Civilization c, double beakers) {
        if (!Double.isFinite(beakers) || beakers <= 0) return;
        SpaceState st = state(c);
        if (st.current() == 0) return;
        st.addBeakers(beakers);
        checkComplete(c, st);
        save(st);
    }

    @Override
    public boolean hasShuttleInCapital(Civilization c) {
        return shuttle(c) != null;
    }

    @Override
    public Map<String, Map<String, Integer>> componentRecipes() {
        return Collections.unmodifiableMap(recipes);
    }

    /** The completed shuttle standing in the capital, or null. */
    StructureApi.Placed shuttle(Civilization c) {
        Town capital = civ.state().capital(c);
        if (capital == null) return null;
        String type = wonders.type(civ.balance().file("space").getString("shuttle-wonder", "space_shuttle"));
        List<? extends StructureApi.Placed> list = wonders.completed(capital, type);
        return list.isEmpty() ? null : list.getFirst();
    }

    // --- progress ---------------------------------------------------------------------------------

    private double hammersPerHour(Town t) {
        TownApi towns = civ.apiOrNull(TownApi.class);
        double v = towns != null ? towns.hammersPerHour(t) : civ.stats().town(t, Stats.HAMMERS, 0);
        return Double.isFinite(v) && v > 0 ? v : 0;
    }

    private void tick() {
        for (Civilization c : List.copyOf(civ.state().civs())) {
            SpaceState st = states.get(c.id());
            if (st == null) continue;
            boolean conquered = c.isConquered();
            if (conquered && !st.wasConquered()) {
                st.wasConquered(true);
                loseOnCapture(c, st);
            } else if (!conquered && st.wasConquered()) {
                st.wasConquered(false);
            }
            if (st.current() > 0 && hasShuttleInCapital(c)) {
                double hammers = 0;
                for (Town t : civ.state().towns(c)) {
                    if (t.status() == TownStatus.NATIVE && c.id().equals(t.nativeCivId())) hammers += hammersPerHour(t);
                }
                st.addHammers(hammers / 60.0);
                checkComplete(c, st);
            }
            save(st);
        }
    }

    private void loseOnCapture(Civilization c, SpaceState st) {
        int lost = civ.balance().getInt("space", "missions-lost-on-capture", 1);
        int before = st.completed();
        st.completed(before - lost);
        st.launch(0);
        if (before > 0 || lost > 0) {
            CivPerms.tellCiv(civ, c, "space.lost-on-capture", Messages.arg("missions", st.completed()));
        }
    }

    private void checkComplete(Civilization c, SpaceState st) {
        Mission m = mission(st.current());
        if (m == null) {
            st.launch(0);
            return;
        }
        if (st.hammers() + 1e-9 < requiredHammers(c, m) || st.beakers() + 1e-9 < requiredBeakers(c, m)) return;
        st.completed(Math.max(st.completed(), m.number()));
        st.launch(0);
        boolean reward = st.rewarded().add(m.number());
        if (reward) deliverRewards(c, m);
        save(st);
        CivPerms.broadcast(civ, "space.completed", Messages.arg("civ", c.name()), Messages.arg("mission", m.name()),
                Messages.arg("n", m.number()));
        new SpaceMissionCompletedEvent(c.id(), m.number(), reward).call();
    }

    private void deliverRewards(Civilization c, Mission m) {
        StructureApi.Placed shuttle = shuttle(c);
        List<Inventory> inventories = shuttle == null ? List.of() : containers(shuttle);
        CivItems items = CivItems.get(civ);
        List<String> given = new ArrayList<>();
        for (Map.Entry<String, Integer> e : m.rewards().entrySet()) {
            given.add(e.getValue() + " × " + itemName(e.getKey()));
            int remaining = e.getValue();
            while (remaining > 0) {
                ItemStack stack = items.create(e.getKey(), Math.min(remaining, 64));
                int amount = stack.getAmount();
                remaining -= amount;
                ItemStack rest = stack;
                for (Inventory inv : inventories) {
                    Map<Integer, ItemStack> left = inv.addItem(rest);
                    rest = left.isEmpty() ? null : left.values().iterator().next();
                    if (rest == null) break;
                }
                if (rest != null && shuttle != null) {
                    Location at = shuttle.center().location();
                    if (at.getWorld() != null) at.getWorld().dropItemNaturally(at, rest);
                }
            }
        }
        CivPerms.tellCiv(civ, c, "space.rewards", Messages.arg("items", String.join(", ", given)));
    }

    /** Inventories of all containers inside the shuttle structure. */
    private List<Inventory> containers(StructureApi.Placed shuttle) {
        List<Inventory> result = new ArrayList<>();
        BlockPos o = shuttle.origin();
        World world = o.bukkitWorld();
        if (world == null) return result;
        int reach = Math.max(shuttle.sizeX(), shuttle.sizeZ());
        int minCx = (o.x() - reach) >> 4;
        int maxCx = (o.x() + reach) >> 4;
        int minCz = (o.z() - reach) >> 4;
        int maxCz = (o.z() + reach) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz);
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof Container container && shuttle.contains(BlockPos.of(state.getLocation()))) {
                        result.add(container.getInventory());
                    }
                }
            }
        }
        return result;
    }

    // --- launch -----------------------------------------------------------------------------------

    void launch(Player p) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        CivPerms.check(civ, p, c, CivPerms.SPACE);
        SpaceState st = state(c);
        StructureApi.Placed shuttle = shuttle(c);
        if (shuttle == null) throw new CivException("space.error.no-shuttle");
        if (st.current() > 0) throw new CivException("space.error.in-progress", Messages.arg("mission", mission(st.current()).name()));
        Mission m = mission(st.completed() + 1);
        if (m == null) throw new CivException("space.error.all-done");
        List<Inventory> inventories = containers(shuttle);
        Map<String, Integer> have = new HashMap<>();
        for (Inventory inv : inventories) {
            for (ItemStack s : inv.getContents()) {
                String id = CivItems.id(s);
                if (id != null && m.components().containsKey(id)) have.merge(id, s.getAmount(), Integer::sum);
            }
        }
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Integer> e : m.components().entrySet()) {
            int lack = e.getValue() - have.getOrDefault(e.getKey(), 0);
            if (lack > 0) missing.add(lack + " × " + itemName(e.getKey()));
        }
        if (!missing.isEmpty()) {
            throw new CivException("space.error.missing", Messages.arg("items", String.join(", ", missing)));
        }
        for (Map.Entry<String, Integer> e : m.components().entrySet()) {
            int left = e.getValue();
            for (Inventory inv : inventories) {
                ItemStack[] contents = inv.getContents();
                for (int i = 0; i < contents.length && left > 0; i++) {
                    ItemStack s = contents[i];
                    if (!e.getKey().equals(CivItems.id(s))) continue;
                    int take = Math.min(left, s.getAmount());
                    s.setAmount(s.getAmount() - take);
                    inv.setItem(i, s.getAmount() <= 0 ? null : s);
                    left -= take;
                }
                if (left <= 0) break;
            }
        }
        st.launch(m.number());
        save(st);
        CivPerms.broadcast(civ, "space.launched", Messages.arg("civ", c.name()), Messages.arg("mission", m.name()),
                Messages.arg("rocket", m.rocket()));
    }

    // --- commands ---------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> command() {
        return Cmd.literal("space")
                .executes(Cmd.player((p, ctx) -> new SpaceMenu(this).open(p)))
                .then(Cmd.literal("progress").executes(Cmd.player((p, ctx) -> progress(p))))
                .then(Cmd.literal("missions").executes(Cmd.player((p, ctx) -> list(p))))
                .then(Cmd.literal("launch").executes(Cmd.player((p, ctx) -> launch(p))))
                .then(Cmd.literal("admin").requires(Cmd.perm("civcraft.admin"))
                        .then(Cmd.literal("set").then(Cmd.arg("civ", StringArgumentType.word())
                                .suggests(Cmd.suggest(() -> civ.state().civs().stream().map(Civilization::name).toList()))
                                .then(Cmd.arg("completed", IntegerArgumentType.integer(0, 99))
                                        .executes(Cmd.run(ctx -> adminSet(ctx.getSource().getSender(),
                                                StringArgumentType.getString(ctx, "civ"),
                                                IntegerArgumentType.getInteger(ctx, "completed"))))))));
    }

    void progress(Player p) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        SpaceState st = state(c);
        civ.messages().send(p, "space.progress-header", Messages.arg("completed", st.completed()),
                Messages.arg("total", missions.size()),
                Messages.arg("shuttle", civ.messages().component(hasShuttleInCapital(c) ? "space.shuttle-ok" : "space.shuttle-missing")));
        Mission m = mission(st.current());
        if (m == null) {
            Mission next = mission(st.completed() + 1);
            civ.messages().sendRaw(p, next == null ? "space.progress-done" : "space.progress-idle",
                    Messages.arg("mission", next == null ? "" : next.name()));
            return;
        }
        double h = requiredHammers(c, m);
        double b = requiredBeakers(c, m);
        civ.messages().sendRaw(p, "space.progress-line", Messages.arg("mission", m.name()),
                Messages.number("hammers", st.hammers()), Messages.number("need_hammers", h),
                Messages.number("beakers", st.beakers()), Messages.number("need_beakers", b),
                Messages.arg("percent", Format.percent(Math.min(h <= 0 ? 1 : st.hammers() / h, b <= 0 ? 1 : st.beakers() / b))));
    }

    private void list(Player p) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        SpaceState st = state(c);
        civ.messages().send(p, "space.list-header");
        for (Mission m : missions) {
            List<String> comps = new ArrayList<>();
            m.components().forEach((id, n) -> comps.add(n + " × " + itemName(id)));
            List<String> rewards = new ArrayList<>();
            m.rewards().forEach((id, n) -> rewards.add(n + " × " + itemName(id)));
            String status = m.number() <= st.completed() ? "space.status.done"
                    : m.number() == st.current() ? "space.status.running" : "space.status.todo";
            civ.messages().sendRaw(p, "space.list-line", Messages.arg("n", m.number()), Messages.arg("mission", m.name()),
                    Messages.arg("status", civ.messages().component(status)),
                    Messages.number("hammers", requiredHammers(c, m)), Messages.number("beakers", requiredBeakers(c, m)),
                    Messages.arg("components", String.join(", ", comps)), Messages.arg("rewards", String.join(", ", rewards)));
        }
    }

    private void adminSet(CommandSender sender, String civName, int completed) throws CivException {
        Civilization c = civ.state().civByName(civName);
        if (c == null) throw new CivException("error.unknown-civ", Messages.arg("name", civName));
        SpaceState st = state(c);
        st.completed(Math.min(completed, missions.size()));
        st.launch(0);
        for (int i = 1; i <= st.completed(); i++) st.rewarded().add(i);
        save(st);
        civ.messages().send(sender, "space.admin-set", Messages.arg("civ", c.name()), Messages.arg("completed", st.completed()));
    }
}
