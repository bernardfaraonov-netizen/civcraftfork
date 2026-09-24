package com.civcraft.structure;

import com.civcraft.core.CivException;
import com.civcraft.core.util.BlockPos;
import com.civcraft.model.Town;
import java.util.List;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;

/**
 * Structures (buildings, wonders, town halls, walls, roads, war camps...). Implemented by the structure
 * module; other modules only use this interface plus the events in {@code com.civcraft.event}.
 */
public interface StructureApi {

    /** Placed structure view shared with other modules. */
    interface Placed {
        String id();

        String type();

        String townId();

        BlockPos origin();

        int sizeX();

        int sizeY();

        int sizeZ();

        boolean complete();

        /** Build progress 0..1. */
        double progress();

        /** Current level (1 for structures without levels). */
        int level();

        boolean contains(BlockPos pos);

        BlockPos center();
    }

    /** Structure covering the block, or null. */
    Placed at(BlockPos pos);

    List<? extends Placed> of(Town town);

    List<? extends Placed> of(Town town, String type);

    Placed byId(String id);

    boolean typeExists(String type);

    /**
     * Places a structure for a town. With {@code instant} the template is pasted at once (town hall of a
     * new town is NOT instant: it is built with hammers), otherwise it is queued for construction.
     * Validates ground, claims, limits, costs unless {@code skipChecks}.
     */
    Placed place(Player player, Town town, String type, BlockPos origin, StructureRotation rotation, String theme,
                 boolean instant, boolean skipChecks) throws CivException;

    /** Removes a structure and its blocks (demolish / destroy / disband). */
    void remove(Placed structure, boolean restoreTerrain);

    /** Computes origin and rotation for a player standing where they want the structure's front. */
    Placement placementFor(Player player, String type, int yOffset);

    record Placement(BlockPos origin, StructureRotation rotation, int sizeX, int sizeY, int sizeZ) {
    }
}
