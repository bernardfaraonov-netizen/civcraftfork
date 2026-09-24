package com.civcraft.structure.template;

import com.civcraft.template.Template;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.Directional;
import org.bukkit.Axis;

/** Mutable voxel grid used to generate procedural templates. Coordinates are template cells (front = +Z). */
public final class TemplateBuilder {

    private final int sx;
    private final int sy;
    private final int sz;
    private final int[] blocks;
    private final List<BlockData> palette = new ArrayList<>();
    private final Map<String, Integer> index = new HashMap<>();
    private final List<Template.Marker> markers = new ArrayList<>();

    public TemplateBuilder(int sx, int sy, int sz) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.blocks = new int[sx * sy * sz];
        paletteIndex(Material.AIR.createBlockData());
    }

    public int sizeX() {
        return sx;
    }

    public int sizeY() {
        return sy;
    }

    public int sizeZ() {
        return sz;
    }

    private int paletteIndex(BlockData data) {
        String key = data.getAsString();
        Integer i = index.get(key);
        if (i == null) {
            i = palette.size();
            palette.add(data);
            index.put(key, i);
        }
        return i;
    }

    public boolean in(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
    }

    private int cell(int x, int y, int z) {
        return x + z * sx + y * sx * sz;
    }

    public void set(int x, int y, int z, BlockData data) {
        if (!in(x, y, z)) return;
        blocks[cell(x, y, z)] = paletteIndex(data);
    }

    public void set(int x, int y, int z, Material material) {
        set(x, y, z, material.createBlockData());
    }

    public BlockData get(int x, int y, int z) {
        if (!in(x, y, z)) return palette.getFirst();
        return palette.get(blocks[cell(x, y, z)]);
    }

    public boolean isAir(int x, int y, int z) {
        return in(x, y, z) && blocks[cell(x, y, z)] == 0;
    }

    public void clear(int x, int y, int z) {
        if (in(x, y, z)) blocks[cell(x, y, z)] = 0;
    }

    /** Fills an inclusive box (coordinates in any order). */
    public void box(int x1, int y1, int z1, int x2, int y2, int z2, BlockData data) {
        for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) set(x, y, z, data);
            }
        }
    }

    public void box(int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
        box(x1, y1, z1, x2, y2, z2, material.createBlockData());
    }

    /** Four vertical walls of a box (no floor, no ceiling). */
    public void walls(int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
        BlockData data = material.createBlockData();
        box(x1, y1, z1, x2, y2, z1, data);
        box(x1, y1, z2, x2, y2, z2, data);
        box(x1, y1, z1, x1, y2, z2, data);
        box(x2, y1, z1, x2, y2, z2, data);
    }

    /** Records a functional marker; its cell stays air (the owning structure places the real block). */
    public void marker(int x, int y, int z, String type, List<String> args, BlockFace facing) {
        if (!in(x, y, z)) return;
        clear(x, y, z);
        markers.add(new Template.Marker(x, y, z, type, List.copyOf(args),
                facing == null ? null : facing.name().toLowerCase(Locale.ROOT)));
    }

    public boolean hasMarkerAt(int x, int y, int z) {
        for (Template.Marker m : markers) if (m.x() == x && m.y() == y && m.z() == z) return true;
        return false;
    }

    // --- block data helpers ---------------------------------------------------------------------------------------

    public static BlockData stairs(Material material, BlockFace facing, boolean upsideDown) {
        BlockData data = material.createBlockData();
        if (data instanceof Stairs stairs) {
            stairs.setFacing(facing);
            stairs.setHalf(upsideDown ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
        }
        return data;
    }

    public static BlockData slab(Material material, boolean top) {
        BlockData data = material.createBlockData();
        if (data instanceof Slab slab) slab.setType(top ? Slab.Type.TOP : Slab.Type.BOTTOM);
        return data;
    }

    public static BlockData door(Material material, BlockFace facing, boolean upper) {
        BlockData data = material.createBlockData();
        if (data instanceof Door door) {
            door.setFacing(facing);
            door.setHalf(upper ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
        }
        return data;
    }

    public static BlockData axis(Material material, Axis axis) {
        BlockData data = material.createBlockData();
        if (data instanceof Orientable o && o.getAxes().contains(axis)) o.setAxis(axis);
        return data;
    }

    public static BlockData hanging(Material material) {
        BlockData data = material.createBlockData();
        if (data instanceof Lantern lantern) lantern.setHanging(true);
        return data;
    }

    public static BlockData facing(Material material, BlockFace face) {
        BlockData data = material.createBlockData();
        if (data instanceof Directional d && d.getFaces().contains(face)) d.setFacing(face);
        return data;
    }

    /** Connects fences, panes and bars to their neighbours (blocks are pasted without physics). */
    public void connectFaces() {
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockData data = get(x, y, z);
                    if (!(data instanceof MultipleFacing mf)) continue;
                    MultipleFacing copy = (MultipleFacing) mf.clone();
                    boolean changed = false;
                    for (BlockFace face : faces) {
                        if (!copy.getAllowedFaces().contains(face)) continue;
                        BlockData n = get(x + face.getModX(), y, z + face.getModZ());
                        boolean connect = n.getMaterial() == data.getMaterial() || n instanceof MultipleFacing
                                || (n.getMaterial().isSolid() && n.getMaterial().isOccluding());
                        if (copy.hasFace(face) != connect) {
                            copy.setFace(face, connect);
                            changed = true;
                        }
                    }
                    if (changed) set(x, y, z, copy);
                }
            }
        }
    }

    public Template build(String id) {
        return new Template(id, sx, sy, sz, palette.toArray(BlockData[]::new), blocks.clone(), markers, Map.of());
    }
}
