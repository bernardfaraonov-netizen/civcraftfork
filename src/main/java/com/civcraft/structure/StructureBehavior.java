package com.civcraft.structure;

import com.civcraft.core.CivException;
import com.civcraft.effect.EffectSink;
import com.civcraft.event.StructureDestroyedEvent;
import com.civcraft.model.Town;
import com.civcraft.structure.component.StructureComponent;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerEvent;

/**
 * Behaviour of a structure type (cottage consumption, bank GUI, tower turrets...), registered with
 * {@link StructureModule#registerBehavior(String, StructureBehavior)}. Every hook runs on the main thread.
 * Hooks must not load chunks: use {@link Structure#components()} / {@link StructureComponent#block()} (null when
 * unloaded) or {@link StructureModule#whenLoaded(Structure, Runnable)} to defer work.
 */
public interface StructureBehavior {

    /** Extra validation before construction starts; throw to refuse. */
    default void checkBuild(Town town, Player player) throws CivException {
    }

    /** Construction was paid for and started. */
    default void onPlaced(Structure s) {
    }

    /** Construction finished (hammers complete). Marker blocks may still be pasting in unloaded chunks. */
    default void onComplete(Structure s) {
    }

    /** All blocks and marker blocks were placed (after completion, repair or refresh). */
    default void onBlocksReady(Structure s) {
    }

    /** Server start / behaviour registration for every complete structure of the type. */
    default void onLoad(Structure s) {
    }

    /** Hourly game tick (only active structures). */
    default void onHourly(Structure s) {
    }

    /** Daily tick at the world tax collection (only active structures). */
    default void onDaily(Structure s) {
    }

    /**
     * Right click on a block (or entity) of the structure. {@code component} is the marker at that position, or
     * null for any other block of the structure. Return true to consume the click (the event is cancelled).
     */
    default boolean onInteract(Structure s, StructureComponent component, Player player, Block block, PlayerEvent event) {
        return false;
    }

    /** Opens the structure's menu (sign / hologram click, /build nearest). Return false if it has none. */
    default boolean openGui(Structure s, Player player) {
        return false;
    }

    default void onDamaged(Structure s, int amount, Player attacker) {
    }

    /** Hit points reached zero (war) – the structure stops working until repaired. */
    default void onDestroyed(Structure s, StructureDestroyedEvent.Cause cause) {
    }

    default void onRepaired(Structure s) {
    }

    /** The structure is being removed for good (demolished, replaced, disbanded, captured wonder...). */
    default void onRemoved(Structure s, StructureDestroyedEvent.Cause cause) {
    }

    /** A chunk of the structure was loaded (re-create entities, holograms...). */
    default void onChunkLoaded(Structure s) {
    }

    default void onLevelChanged(Structure s, int oldLevel, int newLevel) {
    }

    /** Dynamic modifiers in addition to the static {@code effects} of the type (only active structures). */
    default void contributeEffects(Structure s, EffectSink sink) {
    }

    /** Extra daily upkeep in hundredths (walls per segment...). */
    default long extraUpkeep(Structure s) {
        return 0;
    }
}
