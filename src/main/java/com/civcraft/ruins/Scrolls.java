package com.civcraft.ruins;

import com.civcraft.CivCraft;
import com.civcraft.core.text.Format;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.Durations;
import com.civcraft.effect.EffectProvider;
import com.civcraft.effect.EffectSink;
import com.civcraft.effect.Modifier;
import com.civcraft.effect.Op;
import com.civcraft.effect.Scope;
import com.civcraft.effect.Stats;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import com.civcraft.pve.PveItems;
import com.civcraft.pve.PveKeys;
import com.civcraft.science.ResearchApi;
import com.civcraft.storage.Stored;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Bonus scrolls from lucky blocks (spec 04 §15.3): right click, the player must be in a town; the
 * effect goes to their town or civilization. Values are rolled when the scroll drops and shown on it.
 */
final class Scrolls implements Listener, EffectProvider {

    static final String COLLECTION = "scroll_effects";
    static final List<String> IDS = List.of("scroll_tech_1", "scroll_tech_2", "scroll_bank_2", "scroll_bank_3",
            "scroll_settler", "scroll_town_2", "scroll_town_3", "scroll_art", "scroll_construction");
    private static final NamespacedKey HOURS = new NamespacedKey("civcraft", "scroll_hours");

    /** A running "construction" scroll: flat hammers per hour for a while. */
    static final class HammerBonus implements Stored {
        String id;
        String townId;
        double hammers;
        Instant until;

        @Override
        public String storageId() {
            return id;
        }
    }

    private final CivCraft civ;
    private final RuinItems items;
    private final ConfigurationSection cfg;
    private final Map<String, HammerBonus> bonuses = new HashMap<>();

    Scrolls(CivCraft civ, RuinItems items, ConfigurationSection cfg) {
        this.civ = civ;
        this.items = items;
        this.cfg = cfg;
    }

    void load() {
        civ.store().createCollection(COLLECTION);
        for (HammerBonus b : civ.store().loadAll(COLLECTION, HammerBonus.class)) if (b.id != null) bonuses.put(b.id, b);
    }

    void register() {
        for (String id : IDS) PveItems.register(id, n -> create(id));
    }

    private double roll(String path, double defMin, double defMax) {
        double min = cfg.getDouble(path + ".min", defMin);
        double max = Math.max(min, cfg.getDouble(path + ".max", defMax));
        return min >= max ? min : ThreadLocalRandom.current().nextDouble(min, max);
    }

    /** Creates a scroll with freshly rolled values. */
    ItemStack create(String id) {
        double value = switch (id) {
            case "scroll_tech_1", "scroll_tech_2" -> Math.round(roll("scrolls.tech-percent", 1, 25));
            case "scroll_settler" -> Math.round(roll("scrolls.settler-percent", 1, 35));
            case "scroll_art" -> Math.round(roll("scrolls.art-culture", 20, 250));
            case "scroll_construction" -> Math.round(roll("scrolls.construction-hammers", 50, 175));
            case "scroll_bank_3", "scroll_town_3" -> 3;
            default -> 2;
        };
        double hours = id.equals("scroll_construction") ? Math.round(roll("scrolls.construction-hours", 4, 8)) : 0;
        ItemStack stack = items.make(Material.PAPER, id, 1, Messages.arg("value", Format.number(value)),
                Messages.arg("hours", Format.number(hours)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(PveKeys.SCROLL_VALUE, PersistentDataType.DOUBLE, value);
        meta.getPersistentDataContainer().set(HOURS, PersistentDataType.DOUBLE, hours);
        meta.setEnchantmentGlintOverride(true);
        meta.setMaxStackSize(1);
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack stack = event.getItem();
        String id = PveItems.id(stack);
        if (id == null || !IDS.contains(id)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        Town town = civ.state().townOf(player);
        if (town == null) {
            civ.messages().send(player, "error.not-in-town");
            return;
        }
        var pdc = stack.getItemMeta().getPersistentDataContainer();
        double value = pdc.getOrDefault(PveKeys.SCROLL_VALUE, PersistentDataType.DOUBLE, 0d);
        double hours = pdc.getOrDefault(HOURS, PersistentDataType.DOUBLE, 0d);
        String error = apply(id, player, town, value, hours);
        if (error != null) {
            civ.messages().send(player, error);
            return;
        }
        stack.setAmount(stack.getAmount() - 1);
    }

    /** Applies the scroll; returns an error message key or null on success. */
    private String apply(String id, Player player, Town town, double value, double hours) {
        switch (id) {
            case "scroll_tech_1", "scroll_tech_2" -> {
                ResearchApi research = civ.apiOrNull(ResearchApi.class);
                Civilization c = civ.state().civOf(town);
                if (research == null || c == null) return "ruins.scroll.no-research";
                String tech = research.currentResearch(c);
                if (tech == null) return "ruins.scroll.no-research";
                int wanted = cfg.getInt(id.equals("scroll_tech_1") ? "scrolls.tech-era-1" : "scrolls.tech-era-2",
                        id.equals("scroll_tech_1") ? 0 : 1);
                if (research.techEra(tech) != wanted) return "ruins.scroll.wrong-era";
                double cost = research.researchCost(c, tech);
                if (cost <= 0) return "ruins.scroll.no-research";
                double beakers = cost * value / 100.0;
                research.addBeakers(c, beakers);
                civ.messages().send(player, "ruins.scroll.tech-used", Messages.arg("tech", research.techName(tech)),
                        Messages.arg("percent", Format.number(value)), Messages.number("beakers", beakers));
                return null;
            }
            case "scroll_bank_2", "scroll_bank_3", "scroll_settler" -> {
                ScrollUseEvent e = new ScrollUseEvent(id, player, town, value).call();
                if (e.failKey() != null) return e.failKey();
                if (!e.isHandled()) return "ruins.scroll.unavailable";
                civ.messages().send(player, "ruins.scroll.used");
                return null;
            }
            case "scroll_town_2", "scroll_town_3" -> {
                int target = (int) value;
                if (town.level() >= target) return "ruins.scroll.town-level-high";
                ScrollUseEvent e = new ScrollUseEvent(id, player, town, value).call();
                if (e.failKey() != null) return e.failKey();
                if (!e.isHandled()) {
                    town.level(target);
                    civ.state().save(town);
                    civ.stats().invalidate();
                }
                civ.messages().send(player, "ruins.scroll.town-level", Messages.arg("level", target));
                return null;
            }
            case "scroll_art" -> {
                town.culture(town.culture() + value);
                civ.state().save(town);
                civ.culture().recompute(true);
                civ.messages().send(player, "ruins.scroll.art", Messages.arg("culture", Format.number(value)));
                Civilization c = civ.state().civOf(town);
                if (c != null && civ.state().capital(c) == town && town.level() <= 1) {
                    civ.messages().send(player, "ruins.scroll.art-talent");
                }
                return null;
            }
            case "scroll_construction" -> {
                HammerBonus b = new HammerBonus();
                b.id = UUID.randomUUID().toString();
                b.townId = town.id();
                b.hammers = value;
                b.until = Instant.now().plus(Duration.ofMinutes(Math.round(Math.max(1, hours) * 60)));
                bonuses.put(b.id, b);
                civ.saves().save(COLLECTION, b);
                civ.stats().invalidate();
                civ.messages().send(player, "ruins.scroll.construction", Messages.arg("hammers", Format.number(value)),
                        Messages.arg("time", Durations.format(Duration.between(Instant.now(), b.until))));
                return null;
            }
            default -> {
                return "ruins.scroll.unavailable";
            }
        }
    }

    /** Every minute: drop expired hammer bonuses. */
    void minute() {
        Instant now = Instant.now();
        boolean changed = false;
        for (HammerBonus b : List.copyOf(bonuses.values())) {
            if (b.until == null || now.isAfter(b.until) || civ.state().town(b.townId) == null) {
                bonuses.remove(b.id);
                civ.saves().delete(COLLECTION, b.id);
                changed = true;
            }
        }
        if (changed) civ.stats().invalidate();
    }

    @Override
    public void contribute(EffectSink sink) {
        Instant now = Instant.now();
        for (HammerBonus b : bonuses.values()) {
            if (b.until == null || now.isAfter(b.until)) continue;
            Town town = civ.state().town(b.townId);
            if (town != null) sink.town(town, new Modifier(Stats.HAMMERS, Op.ADD, b.hammers, Scope.TOWN, "scroll:construction"));
        }
    }
}
