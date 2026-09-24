package com.civcraft.pve;

import com.civcraft.CivCraft;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.Money;
import com.civcraft.model.Resident;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Coins dropped by mobs (spec 04 §10.5): an item on the ground that is credited to the personal
 * balance when picked up. The value lives in the item's PDC, never in its name. Only the owner (the
 * killer) can pick the coins up; hoppers and mobs cannot.
 */
public final class Coins implements Listener {

    private final CivCraft civ;
    private Material material = Material.GOLD_NUGGET;
    private boolean pickupMessage;

    public Coins(CivCraft civ) {
        this.civ = civ;
    }

    public void configure(String materialName, boolean pickupMessage) {
        Material m = Material.matchMaterial(materialName);
        this.material = m != null && m.isItem() ? m : Material.GOLD_NUGGET;
        this.pickupMessage = pickupMessage;
    }

    /** Random amount in whole coins between min and max (inclusive), as hundredths. */
    public static long roll(long minCoins, long maxCoins) {
        if (maxCoins <= minCoins) return Money.ofCoins(Math.max(0, minCoins));
        return Money.ofCoins(ThreadLocalRandom.current().nextLong(minCoins, maxCoins + 1));
    }

    /** Drops a coin item worth {@code cents}, pickable only by {@code owner} (null = anyone). */
    public void drop(Location at, long cents, UUID owner) {
        if (cents <= 0 || at.getWorld() == null) return;
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(PveKeys.COINS, PersistentDataType.LONG, cents);
        meta.displayName(civ.messages().component("pve.coins.item-name", Messages.money("amount", cents))
                .decoration(TextDecoration.ITALIC, false));
        meta.setMaxStackSize(99);
        stack.setItemMeta(meta);
        Item item = at.getWorld().dropItemNaturally(at, stack);
        item.setCanMobPickup(false);
        if (owner != null) item.setOwner(owner);
    }

    public static long value(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return 0;
        Long v = stack.getItemMeta().getPersistentDataContainer().get(PveKeys.COINS, PersistentDataType.LONG);
        return v == null || v <= 0 ? 0 : Math.multiplyExact(v, (long) stack.getAmount());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(PlayerAttemptPickupItemEvent event) {
        Item item = event.getItem();
        long cents = value(item.getItemStack());
        if (cents <= 0) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (item.getOwner() != null && !item.getOwner().equals(player.getUniqueId())) return;
        Resident resident = civ.state().resident(player);
        if (resident == null) return;
        item.remove();
        resident.addBalance(cents);
        civ.state().save(resident);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.4f, 1.6f);
        if (pickupMessage) civ.messages().actionBar(player, "pve.coins.picked", Messages.money("amount", cents));
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopper(InventoryPickupItemEvent event) {
        if (value(event.getItem().getItemStack()) > 0) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMerge(ItemMergeEvent event) {
        if (value(event.getEntity().getItemStack()) <= 0) return;
        if (!Objects.equals(event.getEntity().getOwner(), event.getTarget().getOwner())) event.setCancelled(true);
    }
}
