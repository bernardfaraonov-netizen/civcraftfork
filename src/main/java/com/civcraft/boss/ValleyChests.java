package com.civcraft.boss;

import com.civcraft.CivCraft;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.BlockPos;
import com.civcraft.gui.Items;
import com.civcraft.gui.Menu;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.pve.AreaService;
import com.civcraft.pve.Give;
import com.civcraft.pve.ItemSpec;
import com.civcraft.pve.PveArea;
import com.civcraft.science.ResearchApi;
import com.civcraft.storage.Stored;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Valley map chests (spec 04 §11.2): refilled around every boss spawn with exactly one item. The roll
 * walks the table from the rarest entry to the most common; the first success is the loot. Opened
 * with right click through a menu; items with a technology condition need that tech.
 */
final class ValleyChests implements Listener {

    static final String COLLECTION = "valley_chests";

    static final class State implements Stored {
        /** Chest position → "index:amount" in the loot table. */
        Map<String, String> loot = new HashMap<>();
        Instant lastFill;

        @Override
        public String storageId() {
            return "chests";
        }
    }

    private final CivCraft civ;
    private final AreaService areas;
    private final ConfigurationSection cfg;
    private final List<ItemSpec> table;
    private final List<LocalTime> bossTimes;
    private State state = new State();
    private String lastSlot;

    ValleyChests(CivCraft civ, AreaService areas, ConfigurationSection cfg, List<LocalTime> bossTimes) {
        this.civ = civ;
        this.areas = areas;
        this.cfg = cfg;
        this.bossTimes = bossTimes;
        this.table = ItemSpec.parseList(cfg.getList("loot"), civ.logger(), "valley.yml chests.loot");
    }

    void load() {
        civ.store().createCollection(COLLECTION);
        List<State> loaded = civ.store().loadAll(COLLECTION, State.class);
        if (!loaded.isEmpty()) state = loaded.getFirst();
        if (state.loot == null) state.loot = new HashMap<>();
    }

    private List<LocalTime> fillTimes() {
        int offset = cfg.getInt("fill-offset-minutes", -10);
        List<LocalTime> list = new ArrayList<>();
        for (LocalTime t : bossTimes) list.add(t.plusMinutes(offset));
        list.sort(null);
        return list;
    }

    Instant nextFill() {
        List<LocalTime> list = fillTimes();
        if (list.isEmpty()) return null;
        ZonedDateTime now = civ.clock().now();
        for (int day = 0; day <= 1; day++) {
            for (LocalTime t : list) {
                ZonedDateTime at = now.toLocalDate().plusDays(day).atTime(t).atZone(now.getZone());
                if (at.isAfter(now)) return at.toInstant();
            }
        }
        return null;
    }

    void minute() {
        ZonedDateTime now = civ.clock().now();
        LocalTime current = now.toLocalTime().withSecond(0).withNano(0);
        if (!fillTimes().contains(current)) return;
        String slot = now.toLocalDate() + "T" + current;
        if (slot.equals(lastSlot)) return;
        lastSlot = slot;
        fill();
    }

    /** Clears every chest and puts exactly one rolled item in each. */
    int fill() {
        PveArea area = areas.area(ValleyModule.AREA);
        state.loot.clear();
        if (area == null) return 0;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < table.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble(i -> table.get(i).chance()));
        int filled = 0;
        for (BlockPos pos : area.markers("chest")) {
            for (int i : order) {
                ItemSpec spec = table.get(i);
                if (spec.roll()) {
                    state.loot.put(pos.toString(), i + ":" + Math.max(1, spec.rollAmount()));
                    filled++;
                    break;
                }
            }
        }
        state.lastFill = Instant.now();
        civ.saves().save(COLLECTION, state);
        return filled;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onOpen(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        PveArea area = areas.area(ValleyModule.AREA);
        if (area == null || !area.isIn(event.getClickedBlock().getWorld())) return;
        BlockPos pos = BlockPos.of(event.getClickedBlock());
        if (!area.markers("chest").contains(pos)) return;
        event.setCancelled(true);
        new ChestMenu(pos.toString()).open(event.getPlayer());
    }

    private ItemSpec spec(String raw) {
        if (raw == null) return null;
        String[] p = raw.split(":");
        try {
            int i = Integer.parseInt(p[0]);
            return i >= 0 && i < table.size() ? table.get(i) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int amount(String raw) {
        String[] p = raw.split(":");
        try {
            return p.length > 1 ? Math.max(1, Integer.parseInt(p[1])) : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private final class ChestMenu extends Menu {
        private final String key;

        ChestMenu(String key) {
            super(3, civ.messages().component("valley.chest.title"));
            this.key = key;
        }

        @Override
        protected void render(Player viewer) {
            fill(Items.filler());
            String raw = state.loot.get(key);
            ItemSpec spec = spec(raw);
            if (spec == null) {
                set(13, Items.of(Material.BARRIER).name(civ.messages().component("valley.chest.empty")).build());
                return;
            }
            ItemStack preview = spec.build(amount(raw));
            if (preview == null) {
                set(13, Items.of(Material.BARRIER).name(civ.messages().component("valley.chest.empty")).build());
                return;
            }
            set(13, preview, click -> take(viewer, spec, raw));
        }

        private void take(Player player, ItemSpec spec, String raw) {
            if (!raw.equals(state.loot.get(key))) {
                refresh(player);
                return;
            }
            String tech = spec.string("tech");
            if (tech != null) {
                ResearchApi research = civ.apiOrNull(ResearchApi.class);
                Resident r = civ.state().resident(player);
                Civilization c = r == null ? null : civ.state().civOf(r);
                if (research != null && (c == null || !research.hasTech(c, tech))) {
                    civ.messages().send(player, "valley.chest.need-tech",
                            Messages.arg("tech", research.techName(tech)));
                    return;
                }
            }
            ItemStack item = spec.build(amount(raw));
            state.loot.remove(key);
            civ.saves().save(COLLECTION, state);
            if (item != null) Give.give(player, item);
            player.closeInventory();
        }
    }
}
