package com.civcraft.plot;

import com.civcraft.core.util.ChunkKey;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Optional: /plot farminfo delegates to the module that implements town Farms. */
public interface FarmInfoApi {

    /** Farm details of the chunk, or null when there is no farm in it. */
    List<Component> farmInfo(Player viewer, ChunkKey chunk);
}
