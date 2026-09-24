package com.civcraft.mob;

import java.time.Instant;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/** Custom CivCraft mobs (spec 04 §10). Implemented by {@link MobModule}. */
public interface MobApi {

    /** Spawns a configured custom mob; null if the location's chunk is not loaded. */
    LivingEntity spawn(MobType type, MobTier tier, Location at);

    boolean isCustom(Entity entity);

    MobType type(Entity entity);

    MobTier tier(Entity entity);

    /** When the next ClearLag sweep runs (for {@code /civ time}); null when disabled. */
    Instant nextClearLag();
}
