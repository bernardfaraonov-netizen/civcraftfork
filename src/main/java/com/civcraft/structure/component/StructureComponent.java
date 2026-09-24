package com.civcraft.structure.component;

import com.civcraft.core.util.BlockPos;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/**
 * A functional point of a structure created from a template marker ({@code /chest 1}, {@code /control},
 * {@code /sign exchange}...). Components are derived from the template on every load, never persisted; their
 * {@link #data()} map is scratch space for behaviours (e.g. the id of a spawned item frame).
 */
public final class StructureComponent {

    private final String structureId;
    private final String type;
    private final List<String> args;
    private final BlockPos pos;
    private final BlockFace facing;
    private final int index;
    private final Map<String, Object> data = new HashMap<>();

    public StructureComponent(String structureId, String type, List<String> args, BlockPos pos, BlockFace facing, int index) {
        this.structureId = structureId;
        this.type = type;
        this.args = List.copyOf(args);
        this.pos = pos;
        this.facing = facing;
        this.index = index;
    }

    public String structureId() {
        return structureId;
    }

    /** Marker type without the slash, lower case. */
    public String type() {
        return type;
    }

    public List<String> args() {
        return args;
    }

    /** First argument or {@code def}. */
    public String arg(String def) {
        return args.isEmpty() ? def : args.getFirst();
    }

    /** Value of a {@code key:value} argument (legacy command signs use {@code id:1}, {@code sell:iron}), or null. */
    public String param(String key) {
        String prefix = key + ":";
        for (String a : args) if (a.startsWith(prefix)) return a.substring(prefix.length());
        return null;
    }

    /** Marker id: the {@code id:n} argument, else the first plain argument, else {@code def}. */
    public String id(String def) {
        String id = param("id");
        if (id != null) return id;
        for (String a : args) if (!a.contains(":")) return a;
        return def;
    }

    public BlockPos pos() {
        return pos;
    }

    /** Facing of the marker sign after rotation (the direction a placed sign/chest looks). */
    public BlockFace facing() {
        return facing;
    }

    /** Ordinal among the components of the same type in this structure (0-based). */
    public int index() {
        return index;
    }

    /** The block, or null when the world is not loaded. Does not load chunks. */
    public Block block() {
        var world = pos.bukkitWorld();
        if (world == null || !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) return null;
        return world.getBlockAt(pos.x(), pos.y(), pos.z());
    }

    public Map<String, Object> data() {
        return data;
    }
}
