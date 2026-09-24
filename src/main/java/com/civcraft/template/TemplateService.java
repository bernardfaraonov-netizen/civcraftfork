package com.civcraft.template;

import com.civcraft.core.util.BlockPos;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.plugin.Plugin;

/**
 * Loads templates by theme and id (plugin folder {@code templates/<theme>/<id>.schem} overrides the
 * bundled one; missing themes fall back to {@code default}) and pastes them.
 */
public final class TemplateService {

    private final Plugin plugin;
    private final Logger logger;
    private final Map<String, Template> cache = new HashMap<>();

    public TemplateService(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /** Returns the template or null if neither the theme nor the default theme has it. */
    public Template get(String theme, String id) {
        String key = theme + "/" + id;
        if (cache.containsKey(key)) return cache.get(key);
        Template t = read(theme, id);
        if (t == null && !"default".equals(theme)) t = get("default", id);
        cache.put(key, t);
        return t;
    }

    public boolean exists(String theme, String id) {
        return get(theme, id) != null;
    }

    public Template rotated(String theme, String id, StructureRotation rotation) {
        Template t = get(theme, id);
        return t == null ? null : t.rotate(rotation);
    }

    /** Registers a template generated at runtime (e.g. procedural fallback buildings). */
    public void put(String theme, String id, Template template) {
        cache.put(theme + "/" + id, template);
    }

    public void clearCache() {
        cache.clear();
    }

    private Template read(String theme, String id) {
        String path = "templates/" + theme + "/" + id + ".schem";
        File file = new File(plugin.getDataFolder(), path);
        try (InputStream in = file.isFile() ? new FileInputStream(file) : plugin.getResource(path)) {
            if (in == null) return null;
            return TemplateLoader.load(theme + "/" + id, in);
        } catch (IOException | RuntimeException e) {
            logger.severe("Cannot load template " + path + ": " + e.getMessage());
            return null;
        }
    }

    /** Rotation that makes the template's front (south side) face the player. */
    public static StructureRotation rotationFacing(BlockFace playerFacing) {
        return switch (playerFacing) {
            case NORTH -> StructureRotation.NONE;
            case EAST -> StructureRotation.CLOCKWISE_90;
            case SOUTH -> StructureRotation.CLOCKWISE_180;
            case WEST -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        };
    }

    /** Pastes one block of the template (with sign text) at {@code origin + (x,y,z)}. No physics. */
    public static void pasteBlock(World world, BlockPos origin, Template t, int x, int y, int z) {
        Block block = world.getBlockAt(origin.x() + x, origin.y() + y, origin.z() + z);
        BlockData data = t.block(x, y, z);
        block.setBlockData(data, false);
        List<String> text = t.signText(x, y, z);
        if (text != null && block.getState() instanceof Sign sign) {
            for (int i = 0; i < Math.min(4, text.size()); i++) sign.getSide(Side.FRONT).line(i, Component.text(text.get(i)));
            sign.setWaxed(true);
            sign.update(true, false);
        }
    }

    /** Pastes the whole template immediately (use only for small templates or during setup). */
    public static void pasteAll(World world, BlockPos origin, Template t) {
        for (int y = 0; y < t.sizeY(); y++) {
            for (int z = 0; z < t.sizeZ(); z++) {
                for (int x = 0; x < t.sizeX(); x++) pasteBlock(world, origin, t, x, y, z);
            }
        }
    }

    /**
     * Share of solid blocks directly below the footprint (spec: at least 80% for camps, towns and
     * buildings). Air, liquids, plants, snow layers and other non-solid blocks do not count.
     */
    public static double groundSupport(World world, BlockPos origin, int sizeX, int sizeZ) {
        int solid = 0;
        int total = sizeX * sizeZ;
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                Material m = world.getBlockAt(origin.x() + x, origin.y() - 1, origin.z() + z).getType();
                if (m.isSolid() && m.isOccluding()) solid++;
            }
        }
        return total == 0 ? 0 : solid / (double) total;
    }
}
