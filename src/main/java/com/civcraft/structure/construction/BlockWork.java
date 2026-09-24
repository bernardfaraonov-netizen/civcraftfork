package com.civcraft.structure.construction;

import com.civcraft.template.Template;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Low level block helpers shared by construction and world jobs. Main thread, loaded chunks only. */
public final class BlockWork {

    private BlockWork() {
    }

    /** Drops the contents of a container before the block is replaced, so nothing is deleted silently. */
    public static void dropContents(Block block) {
        if (!block.getType().isAir() && block.getState(false) instanceof Container container) {
            Inventory inv = container instanceof org.bukkit.block.Chest chest ? chest.getBlockInventory() : container.getInventory();
            Location at = block.getLocation().add(0.5, 0.5, 0.5);
            for (ItemStack item : inv.getContents()) {
                if (item != null && !item.getType().isAir()) block.getWorld().dropItemNaturally(at, item);
            }
            inv.clear();
        }
    }

    /**
     * Places one template cell if the world block differs. Returns the previous block data string when a block was
     * replaced (for the undo buffer), else null.
     */
    public static String paste(Block block, Template t, int x, int y, int z) {
        BlockData target = t.block(x, y, z);
        BlockData current = block.getBlockData();
        List<String> text = t.signText(x, y, z);
        if (current.equals(target) && text == null) return null;
        String previous = current.getAsString();
        if (!current.equals(target)) {
            dropContents(block);
            block.setBlockData(target, false);
        }
        if (text != null) {
            BlockState state = block.getState(false);
            if (state instanceof Sign sign) {
                for (int i = 0; i < Math.min(4, text.size()); i++) sign.getSide(Side.FRONT).line(i, Component.text(text.get(i)));
                sign.setWaxed(true);
                sign.update(true, false);
            }
        }
        return previous;
    }
}
