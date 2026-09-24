package com.civcraft.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Chest based menu. Every click inside a menu is cancelled before any handler runs, and items are
 * never taken from the menu inventory, which rules out the GUI dupes the legacy lore-GUI suffered from.
 */
public abstract class Menu implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();

    protected Menu(int rows, Component title) {
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    /** Fills the inventory. Called on open and on {@link #refresh()}. */
    protected abstract void render(Player viewer);

    public final void open(Player player) {
        clear();
        render(player);
        player.openInventory(inventory);
    }

    public final void refresh(Player viewer) {
        clear();
        render(viewer);
    }

    private void clear() {
        inventory.clear();
        handlers.clear();
    }

    protected void set(int slot, ItemStack item) {
        inventory.setItem(slot, item);
        handlers.remove(slot);
    }

    protected void set(int slot, ItemStack item, Consumer<InventoryClickEvent> onClick) {
        inventory.setItem(slot, item);
        handlers.put(slot, onClick);
    }

    protected void fill(ItemStack item) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, item);
        }
    }

    protected int size() {
        return inventory.getSize();
    }

    /** Called by {@link MenuListener}; the event is already cancelled. */
    void handleClick(InventoryClickEvent event) {
        if (event.getClickedInventory() != inventory) return;
        Consumer<InventoryClickEvent> handler = handlers.get(event.getSlot());
        if (handler != null) handler.accept(event);
    }

    /** Called when the viewer closes the menu. */
    protected void onClose(Player player) {
    }

    void handleClose(Player player) {
        onClose(player);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
