package com.civcraft.structure;

import com.civcraft.CivCraft;
import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.structure.component.MarkerHandler;
import com.civcraft.structure.component.StructureComponent;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Routes clicks on structures to behaviours and marker handlers (before protection runs), and resumes deferred work
 * when chunks load (holograms, marker blocks, behaviours' entities).
 */
final class StructureListener implements Listener {

    private final StructureModule module;
    private final CivCraft civ;

    StructureListener(StructureModule module, CivCraft civ) {
        this.module = module;
        this.civ = civ;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block block = e.getClickedBlock();
        if (block == null) return;
        BlockPos pos = BlockPos.of(block);
        Structure s = module.index().at(pos);
        if (s == null || s.removed() || s.isBuilding()) return;
        StructureComponent component = module.index().component(pos);
        Player player = e.getPlayer();
        if (dispatch(s, component, player, block, e)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Entity entity = e.getRightClicked();
        if (!(entity instanceof ItemFrame frame)) return;
        Block block = frame.getLocation().getBlock();
        BlockPos pos = BlockPos.of(block);
        Structure s = module.index().at(pos);
        if (s == null || s.removed() || s.isBuilding()) return;
        StructureComponent component = module.index().component(pos);
        if (dispatch(s, component, e.getPlayer(), block, e)) e.setCancelled(true);
    }

    private boolean dispatch(Structure s, StructureComponent component, Player player, Block block,
                             org.bukkit.event.player.PlayerEvent event) {
        try {
            if (module.behavior(s.type()).onInteract(s, component, player, block, event)) return true;
            if (component != null) {
                MarkerHandler h = module.markerHandler(component.type());
                return h != null && h.interact(s, component, player, event);
            }
        } catch (RuntimeException ex) {
            civ.logger().log(java.util.logging.Level.SEVERE, "Structure interaction failed for " + s.type(), ex);
            civ.messages().send(player, "error.internal");
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        ChunkKey key = ChunkKey.of(e.getChunk());
        civ.tasks().nextTick(() -> {
            module.runDeferred(key);
            for (Structure s : java.util.List.copyOf(module.index().chunk(key))) {
                if (s.removed()) continue;
                if (s.complete()) module.controlPoints().refresh(s);
                if (s.isActive() || s.isDestroyed()) module.safe(s, "onChunkLoaded", () -> module.behavior(s.type()).onChunkLoaded(s));
            }
        });
    }
}
