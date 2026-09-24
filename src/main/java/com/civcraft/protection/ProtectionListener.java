package com.civcraft.protection;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Translates every griefing-relevant Bukkit event into a {@link ProtectionService} query. Covers the
 * paths the legacy plugin missed: pistons, liquids, dispensers, explosions, fire, tree growth,
 * endermen, hanging entities, armor stands and lecterns.
 */
public final class ProtectionListener implements Listener {

    private final ProtectionService protection;

    public ProtectionListener(ProtectionService protection) {
        this.protection = protection;
    }

    private void deny(Cancellable event, Player player, com.civcraft.protection.Action action, Block block) {
        if (!protection.allowed(player, action, block)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        deny(e, e.getPlayer(), com.civcraft.protection.Action.BREAK, e.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e instanceof BlockMultiPlaceEvent multi) {
            for (var state : multi.getReplacedBlockStates()) {
                if (!protection.allowed(e.getPlayer(), com.civcraft.protection.Action.PLACE, state.getBlock())) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
        deny(e, e.getPlayer(), com.civcraft.protection.Action.PLACE, e.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSign(SignChangeEvent e) {
        deny(e, e.getPlayer(), com.civcraft.protection.Action.PLACE, e.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block block = e.getClickedBlock();
        if (block == null) return;
        if (e.getAction() == Action.PHYSICAL) {
            // Pressure plates, tripwire, farmland trampling.
            com.civcraft.protection.Action action = block.getType() == Material.FARMLAND
                    ? com.civcraft.protection.Action.BREAK : com.civcraft.protection.Action.INTERACT;
            if (!protection.check(e.getPlayer(), action, block, null).denied()) return;
            e.setCancelled(true);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() == EquipmentSlot.OFF_HAND && e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) return;
        if (isInteractable(block.getType()) && !e.getPlayer().isSneaking()) {
            if (!protection.allowed(e.getPlayer(), com.civcraft.protection.Action.INTERACT, block)) {
                e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
                e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            }
            return;
        }
        if (e.getItem() != null && isUsableItem(e.getItem().getType())) {
            Block target = block.getRelative(e.getBlockFace());
            if (!protection.allowed(e.getPlayer(), com.civcraft.protection.Action.ITEMUSE, block)
                    || !protection.allowed(e.getPlayer(), com.civcraft.protection.Action.ITEMUSE, target)) {
                e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
                e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            }
        } else if (isInteractable(block.getType())) {
            if (!protection.allowed(e.getPlayer(), com.civcraft.protection.Action.INTERACT, block)) {
                e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean isInteractable(Material m) {
        return m.isInteractable() || Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)
                || Tag.BUTTONS.isTagged(m) || Tag.PRESSURE_PLATES.isTagged(m) || Tag.SHULKER_BOXES.isTagged(m)
                || m == Material.LEVER || m == Material.CHEST || m == Material.TRAPPED_CHEST || m == Material.BARREL;
    }

    private static boolean isUsableItem(Material m) {
        return m == Material.FLINT_AND_STEEL || m == Material.FIRE_CHARGE || m == Material.BONE_MEAL
                || m.name().endsWith("_BUCKET") || m.name().endsWith("_SPAWN_EGG") || m == Material.ARMOR_STAND
                || m == Material.END_CRYSTAL || m.name().endsWith("_MINECART") || m == Material.MINECART
                || m.name().endsWith("_BOAT") || m.name().endsWith("_RAFT") || m == Material.ITEM_FRAME
                || m == Material.GLOW_ITEM_FRAME || m == Material.PAINTING || m == Material.LEAD
                || m == Material.SHEARS || m == Material.BRUSH || m == Material.HONEYCOMB || m.name().endsWith("_AXE")
                || m.name().endsWith("_HOE") || m.name().endsWith("_SHOVEL");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        deny(e, e.getPlayer(), com.civcraft.protection.Action.ITEMUSE, e.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        deny(e, e.getPlayer(), com.civcraft.protection.Action.ITEMUSE, e.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent e) {
        if (e.getPlayer() == null) return;
        deny(e, e.getPlayer(), com.civcraft.protection.Action.ITEMUSE, e.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLectern(PlayerTakeLecternBookEvent e) {
        deny(e, e.getPlayer(), com.civcraft.protection.Action.INTERACT, e.getLectern().getBlock());
    }

    // --- entities -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        Entity target = e.getRightClicked();
        if (target instanceof org.bukkit.entity.Hanging || target instanceof org.bukkit.entity.ArmorStand
                || target instanceof org.bukkit.entity.Animals || target instanceof org.bukkit.entity.minecart.StorageMinecart
                || target instanceof org.bukkit.entity.ChestBoat || target instanceof org.bukkit.entity.Villager) {
            deny(e, e.getPlayer(), com.civcraft.protection.Action.ENTITY, target.getLocation().getBlock());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        deny(e, e.getPlayer(), com.civcraft.protection.Action.ENTITY, e.getRightClicked().getLocation().getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        if (!(victim instanceof org.bukkit.entity.Hanging || victim instanceof org.bukkit.entity.ArmorStand
                || victim instanceof org.bukkit.entity.Animals || victim instanceof org.bukkit.entity.Villager)) return;
        Player attacker = attacker(e.getDamager());
        if (attacker == null) {
            if (!protection.environmentAllowed(com.civcraft.protection.Action.ENTITY, victim.getLocation().getBlock(), null)) {
                e.setCancelled(true);
            }
            return;
        }
        deny(e, attacker, com.civcraft.protection.Action.ENTITY, victim.getLocation().getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if (e instanceof HangingBreakByEntityEvent byEntity) {
            Player attacker = attacker(byEntity.getRemover());
            if (attacker != null) {
                deny(e, attacker, com.civcraft.protection.Action.ENTITY, e.getEntity().getLocation().getBlock());
                return;
            }
        }
        if (e.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION || e.getCause() == HangingBreakEvent.RemoveCause.ENTITY) {
            if (!protection.environmentAllowed(com.civcraft.protection.Action.ENTITY, e.getEntity().getLocation().getBlock(), null)) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (e.getPlayer() != null) deny(e, e.getPlayer(), com.civcraft.protection.Action.PLACE, e.getBlock());
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    // --- environment ----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        filterExplosion(e.blockList());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        filterExplosion(e.blockList());
    }

    private void filterExplosion(List<Block> blocks) {
        blocks.removeIf(b -> !protection.environmentAllowed(com.civcraft.protection.Action.BREAK, b, null));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player p) {
            deny(e, p, com.civcraft.protection.Action.BREAK, e.getBlock());
        } else if (!(e.getEntity() instanceof org.bukkit.entity.FallingBlock)) {
            // Endermen, withers, ravagers, silverfish, rabbits...
            if (!protection.environmentAllowed(com.civcraft.protection.Action.BREAK, e.getBlock(), null)) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (!pistonAllowed(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (!pistonAllowed(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    private boolean pistonAllowed(Block piston, List<Block> moved, BlockFace direction) {
        for (Block b : moved) {
            if (!protection.environmentAllowed(com.civcraft.protection.Action.FLOW, b, piston)) return false;
            if (!protection.environmentAllowed(com.civcraft.protection.Action.FLOW, b.getRelative(direction), piston)) return false;
        }
        return protection.environmentAllowed(com.civcraft.protection.Action.FLOW, piston.getRelative(direction), piston);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (!protection.environmentAllowed(com.civcraft.protection.Action.FLOW, e.getToBlock(), e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (!(e.getBlock().getBlockData() instanceof org.bukkit.block.data.Directional d)) return;
        Block target = e.getBlock().getRelative(d.getFacing());
        if (!protection.environmentAllowed(com.civcraft.protection.Action.FLOW, target, e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (e.getPlayer() != null) {
            deny(e, e.getPlayer(), com.civcraft.protection.Action.ITEMUSE, e.getBlock());
        } else if (!protection.environmentAllowed(com.civcraft.protection.Action.FIRE, e.getBlock(),
                e.getIgnitingBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (!protection.environmentAllowed(com.civcraft.protection.Action.FIRE, e.getBlock(), e.getIgnitingBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (e.getSource().getType() == Material.FIRE || e.getSource().getType() == Material.SOUL_FIRE) {
            if (!protection.environmentAllowed(com.civcraft.protection.Action.FIRE, e.getBlock(), e.getSource())) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent e) {
        Block origin = e.getLocation().getBlock();
        e.getBlocks().removeIf(state -> !protection.environmentAllowed(com.civcraft.protection.Action.FLOW, state.getBlock(), origin));
    }
}
