package com.civcraft.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Fluent builder for display items using 1.21 data components. */
public final class Items {

    private final ItemStack stack;
    private final List<Component> lore = new ArrayList<>();

    private Items(Material material) {
        this.stack = ItemStack.of(material);
    }

    public static Items of(Material material) {
        return new Items(material);
    }

    public Items name(Component name) {
        stack.setData(DataComponentTypes.ITEM_NAME, name);
        return this;
    }

    public Items lore(Component line) {
        lore.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        return this;
    }

    public Items lore(List<Component> lines) {
        lines.forEach(this::lore);
        return this;
    }

    public Items amount(int amount) {
        stack.setAmount(Math.max(1, Math.min(stack.getMaxStackSize(), amount)));
        return this;
    }

    public Items glow(boolean glow) {
        if (glow) stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return this;
    }

    public ItemStack build() {
        if (!lore.isEmpty()) stack.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY,
                io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay()
                        .addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS).build());
        return stack;
    }

    public static ItemStack filler() {
        return of(Material.GRAY_STAINED_GLASS_PANE).name(Component.empty()).build();
    }
}
