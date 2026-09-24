package com.civcraft.item.guard;

import com.civcraft.core.text.Messages;
import com.civcraft.gui.Menu;
import com.civcraft.item.ItemData;
import com.civcraft.item.ItemRegistry;
import com.civcraft.item.ItemRenderer;
import com.civcraft.item.ItemRules;
import com.civcraft.item.def.ItemDef;
import com.civcraft.item.def.ItemFlag;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.BrewingStandFuelEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps custom items from acting as their vanilla base items and dispatches right-click handlers:
 * no placing, eating, feeding into stations (anvil, grindstone, smithing, furnaces, brewing, enchanting,
 * stonecutter, loom, beacon, villagers), dispensers or item-eating blocks; SoulBound / unit storage rules;
 * stale items are re-rendered when players join, open containers or pick them up.
 */
public final class ItemGuardListener implements Listener {

    private final ItemRegistry registry;
    private final ItemRenderer renderer;
    private final ItemRules rules;
    private final Messages messages;
    private final Map<String, BiConsumer<Player, PlayerInteractEvent>> handlers;
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public ItemGuardListener(ItemRegistry registry, ItemRenderer renderer, ItemRules rules, Messages messages,
                             Map<String, BiConsumer<Player, PlayerInteractEvent>> handlers) {
        this.registry = registry;
        this.renderer = renderer;
        this.rules = rules;
        this.messages = messages;
        this.handlers = handlers;
    }

    private boolean custom(ItemStack stack) {
        return ItemData.id(stack) != null;
    }

    // ------------------------------------------------------------------------------------ use

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        String id = ItemData.id(item);
        if (id == null) return;
        ItemDef def = registry.get(id);
        Player player = event.getPlayer();
        BiConsumer<Player, PlayerInteractEvent> handler = handlers.get(id);
        if (handler != null) {
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
            // Both hands fire in the same tick; run the handler once.
            long tick = Bukkit.getCurrentTick();
            Long last = lastUse.put(player.getUniqueId(), (long) tick);
            if (last != null && last == tick) return;
            handler.accept(player, event);
            return;
        }
        if (def == null || (!def.kind().isGear() && !def.has(ItemFlag.VANILLA_USE))) {
            event.setUseItemInHand(Event.Result.DENY);
        }
        Block block = event.getClickedBlock();
        if (block != null && rules.blockedBlocks.contains(block.getType())) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        ItemStack item = event.getHand() == EquipmentSlot.OFF_HAND
                ? event.getPlayer().getInventory().getItemInOffHand()
                : event.getPlayer().getInventory().getItemInMainHand();
        ItemDef def = registry.def(item);
        if (custom(item) && (def == null || (!def.kind().isGear() && !def.has(ItemFlag.VANILLA_USE)))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemDef def = registry.def(event.getItemInHand());
        if (custom(event.getItemInHand()) && (def == null || !def.has(ItemFlag.PLACEABLE))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHang(HangingPlaceEvent event) {
        ItemStack item = event.getItemStack();
        ItemDef def = registry.def(item);
        if (custom(item) && (def == null || !def.has(ItemFlag.PLACEABLE))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemDef def = registry.def(event.getItem());
        if (custom(event.getItem()) && (def == null || !def.has(ItemFlag.CONSUMABLE))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (custom(event.getItem())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------------------------ stations

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvil(PrepareAnvilEvent event) {
        Inventory inv = event.getInventory();
        if (custom(inv.getItem(0)) || custom(inv.getItem(1))) event.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onGrindstone(PrepareGrindstoneEvent event) {
        Inventory inv = event.getInventory();
        if (custom(inv.getItem(0)) || custom(inv.getItem(1))) event.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSmithing(PrepareSmithingEvent event) {
        for (ItemStack stack : event.getInventory().getContents()) {
            if (custom(stack)) {
                event.setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        if (custom(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (custom(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(FurnaceBurnEvent event) {
        if (custom(event.getFuel())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSmelt(FurnaceSmeltEvent event) {
        if (custom(event.getSource())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCook(BlockCookEvent event) {
        if (custom(event.getSource())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        for (ItemStack stack : event.getContents().getContents()) {
            if (custom(stack)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrewFuel(BrewingStandFuelEvent event) {
        if (custom(event.getFuel())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        if (!custom(event.getItem())) return;
        if (rules.blockedStations.contains(event.getDestination().getType())) {
            event.setCancelled(true);
            return;
        }
        ItemDef def = registry.def(event.getItem());
        if (def != null && storageBlocked(def, event.getItem())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------------------------ inventory moves

    private boolean storageBlocked(ItemDef def, ItemStack stack) {
        return def.has(ItemFlag.NO_CONTAINER)
                || (rules.soulboundBlockContainers && (def.has(ItemFlag.SOULBOUND) || ItemData.soulbound(stack)));
    }

    /** Whether the top inventory may not receive this stack. */
    private boolean forbiddenIn(Inventory top, ItemStack stack) {
        if (ItemData.empty(stack)) return false;
        if (top.getHolder(false) instanceof Menu) return false;
        InventoryType type = top.getType();
        if (type == InventoryType.PLAYER || type == InventoryType.CRAFTING || type == InventoryType.WORKBENCH
                || type == InventoryType.CREATIVE) {
            return false;
        }
        if (custom(stack) && rules.blockedStations.contains(type)) return true;
        ItemDef def = registry.def(stack);
        if (def != null) return storageBlocked(def, stack);
        return rules.soulboundBlockContainers && ItemData.soulbound(stack);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        boolean clickedTop = event.getRawSlot() >= 0 && event.getRawSlot() < top.getSize();
        InventoryAction action = event.getAction();
        ItemStack moving = null;
        if (clickedTop) {
            switch (action) {
                case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> moving = event.getCursor();
                case HOTBAR_SWAP -> moving = event.getHotbarButton() >= 0
                        ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton())
                        : event.getWhoClicked().getInventory().getItemInOffHand();
                default -> { }
            }
        } else if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            moving = event.getCurrentItem();
        }
        if (moving != null && forbiddenIn(top, moving)) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player p) messages.actionBar(p, "items.guard.no-container");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!forbiddenIn(top, event.getOldCursor())) return;
        for (int raw : event.getRawSlots()) {
            if (raw < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack stack = event.getItemDrop().getItemStack();
        ItemDef def = registry.def(stack);
        boolean soulbound = ItemData.soulbound(stack) || (def != null && def.has(ItemFlag.SOULBOUND));
        if ((def != null && def.has(ItemFlag.NO_DROP)) || (rules.soulboundBlockDrop && soulbound)) {
            event.setCancelled(true);
            messages.actionBar(event.getPlayer(), "items.guard.no-drop");
        }
    }

    // ------------------------------------------------------------------------------------ refresh stale stacks

    public void refreshInventory(Inventory inventory) {
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack fresh = renderer.refreshed(contents[i]);
            if (fresh != null) inventory.setItem(i, fresh);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        refreshInventory(event.getPlayer().getInventory());
        refreshInventory(event.getPlayer().getEnderChest());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        Inventory top = event.getInventory();
        if (top.getHolder(false) instanceof Menu) return;
        refreshInventory(top);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        ItemStack fresh = renderer.refreshed(event.getItem().getItemStack());
        if (fresh != null) event.getItem().setItemStack(fresh);
    }

    public void forget(UUID player) {
        lastUse.remove(player);
    }
}
