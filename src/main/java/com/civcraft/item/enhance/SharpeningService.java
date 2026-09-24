package com.civcraft.item.enhance;

import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.item.ItemData;
import com.civcraft.item.ItemProfile;
import com.civcraft.item.ItemRegistry;
import com.civcraft.item.ItemRenderer;
import com.civcraft.item.def.ItemDef;
import com.civcraft.item.def.ItemKind;
import com.civcraft.item.def.SharpenType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Catalyst sharpening (spec 04 §6). Buildings (Blacksmith, War Lab) charge the price and pick the
 * stacks, then call {@link #attempt}; this class validates, rolls, updates the item or destroys it on
 * failure and keeps the hidden personal bonus.
 */
public final class SharpeningService {

    /** Where the attempt happens (spec 04 §6.1). */
    public enum Station { BLACKSMITH, WAR_LAB }

    public enum Outcome { SUCCESS, DESTROYED }

    /** Result of an attempt: new level on success, the chance that was rolled. */
    public record Result(Outcome outcome, int level, double chance) {
    }

    private final ItemRegistry registry;
    private final ItemRenderer renderer;
    private final Function<Player, ItemProfile> profiles;
    private final java.util.function.Consumer<ItemProfile> save;
    private SharpenMath.Config math;
    private int maxAttack;
    private int maxDefense;
    private long cost;
    private Set<Integer> blacksmithTiers;
    private int blacksmithT4AttackMax;
    private Set<Integer> warLabTiers;
    private double titaniumMin;
    private double titaniumMax;
    private Predicate<Player> titanium = p -> false;
    private final List<ToDoubleFunction<Player>> bonuses = new ArrayList<>();

    public SharpeningService(ItemRegistry registry, ItemRenderer renderer, Function<Player, ItemProfile> profiles,
                             java.util.function.Consumer<ItemProfile> save) {
        this.registry = registry;
        this.renderer = renderer;
        this.profiles = profiles;
        this.save = save;
    }

    public void configure(ConfigurationSection s) {
        math = new SharpenMath.Config(s.getDouble("base-chance.attack", 40), s.getDouble("base-chance.defense", 50),
                Math.max(1, s.getInt("step-size", 3)), s.getDouble("step-penalty", 21.428),
                s.getDouble("min-chance", 0.000001), s.getDouble("personal.per-tier", 0.1875),
                s.getDouble("personal.max", 10));
        maxAttack = s.getInt("max-attack-level", 8);
        maxDefense = s.getInt("max-defense-level", 5);
        cost = Math.round(Math.max(0, s.getDouble("cost", 50000)) * 100);
        blacksmithTiers = Set.copyOf(s.getIntegerList("stations.blacksmith.tiers"));
        blacksmithT4AttackMax = s.getInt("stations.blacksmith.t4-attack-max-level", 2);
        warLabTiers = Set.copyOf(s.getIntegerList("stations.war_lab.tiers"));
        titaniumMin = s.getDouble("titanium.min", 1);
        titaniumMax = Math.max(titaniumMin, s.getDouble("titanium.max", 5));
    }

    public SharpenMath.Config math() {
        return math;
    }

    public int maxLevel(SharpenType type) {
        return type == SharpenType.DEFENSE ? maxDefense : maxAttack;
    }

    /** Price of one attempt in hundredths of a coin (charged by the building). */
    public long attemptCost() {
        return cost;
    }

    /** «Укреплённая амуниция» check supplied by the trade goods module; adds a random 1–5 pp per attempt. */
    public void setTitaniumCheck(Predicate<Player> check) {
        this.titanium = check == null ? p -> false : check;
    }

    /** Extra flat chance bonuses in percentage points (talents, buffs...). */
    public void addChanceBonus(ToDoubleFunction<Player> bonus) {
        bonuses.add(bonus);
    }

    public boolean isCatalyst(ItemStack stack) {
        ItemDef def = registry.def(stack);
        return def != null && def.kind() == ItemKind.CATALYST;
    }

    public static SharpenType catalystType(ItemDef catalyst) {
        return SharpenType.parse(catalyst.data("sharpen", "attack"));
    }

    public static boolean mystic(ItemDef catalyst) {
        return Boolean.parseBoolean(catalyst.data("mystic", "false"));
    }

    /** Throws a player-facing error when the catalyst cannot be used on the target at this station. */
    public void validate(ItemStack target, ItemStack catalyst, Station station) throws CivException {
        ItemDef item = registry.def(target);
        CivException.check(item != null && item.isGear() && target.getAmount() == 1, "items.sharpen.not-gear");
        SharpenType type = item.gear().sharpen();
        CivException.check(type != SharpenType.NONE, "items.sharpen.not-sharpenable");
        ItemDef cat = registry.def(catalyst);
        CivException.check(cat != null && cat.kind() == ItemKind.CATALYST, "items.sharpen.no-catalyst");
        CivException.check(catalystType(cat) == type, "items.sharpen.wrong-type");
        CivException.check(cat.tier() == item.tier(), "items.sharpen.wrong-tier",
                Messages.arg("tier", item.tier()));
        int level = ItemData.sharpen(target);
        CivException.check(level < maxLevel(type), "items.sharpen.max-level", Messages.arg("max", maxLevel(type)));
        int tier = item.tier();
        if (station == Station.BLACKSMITH) {
            boolean t4Attack = tier == 4 && type == SharpenType.ATTACK && level + 1 <= blacksmithT4AttackMax;
            CivException.check(blacksmithTiers.contains(tier) || t4Attack, "items.sharpen.need-war-lab");
        } else {
            CivException.check(warLabTiers.contains(tier), "items.sharpen.need-blacksmith");
        }
    }

    /** The chance shown to players: formula without the hidden personal bonus and random titanium bonus. */
    public double displayedChance(Player player, ItemStack target, ItemStack catalyst) {
        ItemDef item = registry.def(target);
        ItemDef cat = registry.def(catalyst);
        if (item == null || cat == null || !item.isGear()) return 0;
        return SharpenMath.chance(math, item.gear().sharpen(), mystic(cat), ItemData.sharpen(target), 0,
                flatBonus(player));
    }

    private double flatBonus(Player player) {
        double total = 0;
        for (ToDoubleFunction<Player> b : bonuses) {
            double v = b.applyAsDouble(player);
            if (Double.isFinite(v)) total += v;
        }
        return total;
    }

    /**
     * Validates, consumes one catalyst from {@code catalyst} and rolls. On success the target gains a
     * level; on failure the target stack is destroyed (amount 0). Both stacks must be live inventory stacks.
     */
    public Result attempt(Player player, ItemStack target, ItemStack catalyst, Station station) throws CivException {
        validate(target, catalyst, station);
        ItemDef item = registry.def(target);
        ItemDef cat = registry.def(catalyst);
        SharpenType type = item.gear().sharpen();
        ItemProfile profile = profiles.apply(player);
        double bonus = flatBonus(player);
        if (titanium.test(player)) bonus += ThreadLocalRandom.current().nextDouble(titaniumMin, titaniumMax + 1e-9);
        int level = ItemData.sharpen(target);
        double chance = SharpenMath.chance(math, type, mystic(cat), level, profile.personalChance(), bonus);
        boolean success = SharpenMath.succeeds(chance, ThreadLocalRandom.current().nextDouble());
        catalyst.subtract(1);
        profile.personalChance(SharpenMath.nextPersonal(math, profile.personalChance(), cat.tier(), success));
        save.accept(profile);
        if (success) {
            ItemData.sharpen(target, level + 1);
            renderer.render(target, item);
            return new Result(Outcome.SUCCESS, level + 1, chance);
        }
        target.setAmount(0);
        return new Result(Outcome.DESTROYED, level, chance);
    }

    /** Tells the player how the attempt went. */
    public void announce(Player player, Result result, Messages messages) {
        if (result.outcome() == Outcome.SUCCESS) {
            messages.send(player, "items.sharpen.success", Messages.arg("level", result.level()));
        } else {
            messages.send(player, "items.sharpen.destroyed");
        }
    }

    /** Finds a catalyst in the inventory that fits the target (mystic ones first when {@code preferMystic}). */
    public ItemStack findCatalyst(Player player, ItemStack target, boolean preferMystic) {
        ItemDef item = registry.def(target);
        if (item == null || !item.isGear()) return null;
        ItemStack fallback = null;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            ItemDef cat = registry.def(stack);
            if (cat == null || cat.kind() != ItemKind.CATALYST || cat.tier() != item.tier()
                    || catalystType(cat) != item.gear().sharpen()) {
                continue;
            }
            if (mystic(cat) == preferMystic) return stack;
            if (fallback == null) fallback = stack;
        }
        return fallback;
    }

    /** Removes all sharpening (ruin recipe scrolls do this). */
    public void clear(ItemStack stack) {
        ItemData.sharpen(stack, 0);
        renderer.render(stack);
    }
}
