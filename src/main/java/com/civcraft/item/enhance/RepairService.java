package com.civcraft.item.enhance;

import com.civcraft.item.ItemData;
import com.civcraft.item.ItemKeys;
import com.civcraft.item.ItemRegistry;
import com.civcraft.item.ItemRenderer;
import com.civcraft.item.def.GearStats;
import com.civcraft.item.def.ItemDef;
import com.civcraft.item.def.ItemFlag;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Repair prices (spec 04 §8.1): armor and weapons cost {@code 2500^(tier^0.2)} (T1 2 500, T2 8 002,
 * T3 17 096, T4 30 451); vanilla tools have a fixed table; some items cannot be repaired at all.
 * Buildings (Blacksmith, War Base) charge the price and call {@link #repair}.
 */
public final class RepairService {

    private final ItemRegistry registry;
    private final ItemRenderer renderer;
    private double base = 2500;
    private double exponent = 0.2;
    private final Map<Material, Long> tools = new EnumMap<>(Material.class);

    public RepairService(ItemRegistry registry, ItemRenderer renderer) {
        this.registry = registry;
        this.renderer = renderer;
    }

    public void configure(ConfigurationSection s) {
        base = s.getDouble("base", 2500);
        exponent = s.getDouble("exponent", 0.2);
        tools.clear();
        ConfigurationSection t = s.getConfigurationSection("tools");
        if (t != null) {
            for (String key : t.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                double coins = t.getDouble(key);
                if (m != null && Double.isFinite(coins) && coins >= 0) tools.put(m, Math.round(coins * 100));
            }
        }
    }

    /** Price in coins of repairing gear of the given tier: {@code base^(tier^exponent)}, rounded. */
    public static long formulaCoins(int tier, double base, double exponent) {
        if (tier <= 0) return 0;
        return Math.round(Math.pow(base, Math.pow(tier, exponent)));
    }

    public boolean damaged(ItemStack stack) {
        return !ItemData.empty(stack) && stack.hasData(DataComponentTypes.MAX_DAMAGE)
                && stack.getDataOrDefault(DataComponentTypes.DAMAGE, 0) > 0;
    }

    /** Repair price in hundredths, or empty when the item cannot be repaired. */
    public OptionalLong cost(ItemStack stack) {
        if (ItemData.empty(stack) || !stack.hasData(DataComponentTypes.MAX_DAMAGE)) return OptionalLong.empty();
        if (stack.getPersistentDataContainer().has(ItemKeys.NO_REPAIR)) return OptionalLong.empty();
        ItemDef def = registry.def(stack);
        if (def != null) {
            if (def.has(ItemFlag.NO_REPAIR) || def.has(ItemFlag.UNBREAKABLE)) return OptionalLong.empty();
            GearStats gear = def.gear();
            if (gear == null) return OptionalLong.empty();
            if (gear.repairCost() >= 0) return OptionalLong.of(gear.repairCost());
            if (gear.repairTier() > 0) return OptionalLong.of(formulaCoins(gear.repairTier(), base, exponent) * 100);
            Long tool = tools.get(stack.getType());
            return tool == null ? OptionalLong.empty() : OptionalLong.of(tool);
        }
        Long tool = tools.get(stack.getType());
        return tool == null ? OptionalLong.empty() : OptionalLong.of(tool);
    }

    /** Restores full durability. Returns false when the item is not repairable or not damaged. */
    public boolean repair(ItemStack stack) {
        if (!damaged(stack) || cost(stack).isEmpty()) return false;
        stack.setData(DataComponentTypes.DAMAGE, 0);
        if (registry.def(stack) != null) renderer.render(stack);
        return true;
    }

    /** Damaged repairable stacks of the inventory including worn armor ("починить всё"). */
    public List<ItemStack> repairable(Player player) {
        List<ItemStack> out = new ArrayList<>();
        PlayerInventory inv = player.getInventory();
        for (ItemStack stack : inv.getContents()) {
            if (damaged(stack) && cost(stack).isPresent()) out.add(stack);
        }
        return out;
    }

    /** Total price of repairing everything {@link #repairable} returns, hundredths. */
    public long costAll(Player player) {
        long total = 0;
        for (ItemStack stack : repairable(player)) total = Math.addExact(total, cost(stack).orElse(0));
        return total;
    }
}
