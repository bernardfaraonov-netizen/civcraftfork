package com.civcraft.structure.component;

import com.civcraft.structure.Structure;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerEvent;

/**
 * Turns a template marker ({@code /chest}, {@code /sign}, {@code /control}...) into something functional. One handler
 * per marker type; registering a handler for a type replaces the previous one. Called on the main thread with the
 * chunk loaded.
 */
public interface MarkerHandler {

    /** Marker type without the slash, lower case. */
    String type();

    /**
     * Places the functional block(s). Called once when the structure's blocks are ready and again on refresh or
     * repair; implementations must be idempotent (keep an existing chest and its contents).
     */
    default void build(Structure s, StructureComponent c, Block block) {
    }

    /** Right click on the marker block; return true to consume it. Behaviours get the click first. */
    default boolean interact(Structure s, StructureComponent c, Player player, PlayerEvent event) {
        return false;
    }

    /** The structure is removed; clean up entities or holograms created for the marker. */
    default void remove(Structure s, StructureComponent c) {
    }
}
