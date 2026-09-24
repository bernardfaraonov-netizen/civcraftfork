package com.civcraft.gui;

import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/** Menu with 5 content rows and a navigation bar. */
public abstract class PagedMenu<T> extends Menu {

    private static final int PAGE_SIZE = 45;
    private int page;

    protected PagedMenu(Component title) {
        super(6, title);
    }

    protected abstract List<T> entries(Player viewer);

    protected abstract ItemStack icon(Player viewer, T entry);

    protected abstract Consumer<InventoryClickEvent> action(Player viewer, T entry);

    /** Extra buttons for the bottom row (slots 46..52 except 49). */
    protected void renderFooter(Player viewer) {
    }

    @Override
    protected final void render(Player viewer) {
        List<T> entries = entries(viewer);
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= entries.size()) break;
            T entry = entries.get(index);
            set(i, icon(viewer, entry), action(viewer, entry));
        }
        for (int slot = 45; slot < 54; slot++) set(slot, Items.filler());
        if (page > 0) {
            set(45, Items.of(Material.ARROW).name(Component.text("← " + page)).build(), e -> {
                page--;
                refresh(viewer);
            });
        }
        if (page < pages - 1) {
            set(53, Items.of(Material.ARROW).name(Component.text((page + 2) + " →")).build(), e -> {
                page++;
                refresh(viewer);
            });
        }
        renderFooter(viewer);
    }
}
