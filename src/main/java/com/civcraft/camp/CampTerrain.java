package com.civcraft.camp;

import com.civcraft.core.util.Cuboid;
import com.civcraft.storage.Stored;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * The terrain a camp replaced, saved before pasting so /camp undo, /camp disband and destruction can
 * restore the land. Stored as a palette of block-data strings plus one palette index per block.
 */
final class CampTerrain implements Stored {

    static final String COLLECTION = "camp_terrain";

    private String campId;
    private List<String> palette = new ArrayList<>();
    private int[] blocks;

    private CampTerrain() {
    }

    static CampTerrain capture(String campId, World world, Cuboid box) {
        CampTerrain t = new CampTerrain();
        t.campId = campId;
        Map<String, Integer> index = new HashMap<>();
        t.blocks = new int[(int) box.volume()];
        int i = 0;
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    String data = world.getBlockAt(x, y, z).getBlockData().getAsString();
                    Integer id = index.get(data);
                    if (id == null) {
                        id = t.palette.size();
                        t.palette.add(data);
                        index.put(data, id);
                    }
                    t.blocks[i++] = id;
                }
            }
        }
        return t;
    }

    void restore(World world, Cuboid box) {
        if (blocks == null || blocks.length != box.volume()) return;
        BlockData[] data = new BlockData[palette.size()];
        for (int k = 0; k < data.length; k++) {
            try {
                data[k] = Bukkit.createBlockData(palette.get(k));
            } catch (IllegalArgumentException e) {
                data[k] = Bukkit.createBlockData(org.bukkit.Material.AIR);
            }
        }
        int i = 0;
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    Block b = world.getBlockAt(x, y, z);
                    BlockData d = data[blocks[i++]];
                    if (!b.getBlockData().equals(d)) b.setBlockData(d, false);
                }
            }
        }
    }

    @Override
    public String storageId() {
        return campId;
    }
}
