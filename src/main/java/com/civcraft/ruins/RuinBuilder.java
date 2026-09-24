package com.civcraft.ruins;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.bukkit.Material;

/**
 * Procedural ruins (spec 04 §15.1): a crumbling square room of 7–11 blocks with broken walls,
 * pillars and cobwebs, styled by biome (desert sandstone, badlands terracotta, mossy jungle stone,
 * snowy "New Year" ruins, plain stone bricks). Exactly one lucky block is placed on one of four
 * predefined positions. Works both inside world generation ({@code LimitedRegion}) and in a live
 * world (admin command) through {@link Sink}.
 */
final class RuinBuilder {

    /** Block access used by the builder. */
    interface Sink {
        Material get(int x, int y, int z);

        void set(int x, int y, int z, Material material);

        /** Highest non-air block Y of the column. */
        int surface(int x, int z);

        String biome(int x, int y, int z);
    }

    enum Style {
        STONE(List.of(Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS, Material.MOSSY_STONE_BRICKS, Material.COBBLESTONE),
                List.of(Material.COBBLESTONE, Material.STONE_BRICKS, Material.GRAVEL), Material.CHISELED_STONE_BRICKS,
                List.of(Material.COBWEB)),
        SANDSTONE(List.of(Material.SANDSTONE, Material.CUT_SANDSTONE, Material.SMOOTH_SANDSTONE, Material.SANDSTONE),
                List.of(Material.SANDSTONE, Material.SAND, Material.SMOOTH_SANDSTONE), Material.CHISELED_SANDSTONE,
                List.of(Material.COBWEB)),
        BADLANDS(List.of(Material.TERRACOTTA, Material.RED_SANDSTONE, Material.CUT_RED_SANDSTONE, Material.ORANGE_TERRACOTTA),
                List.of(Material.RED_SANDSTONE, Material.RED_SAND, Material.TERRACOTTA), Material.CHISELED_RED_SANDSTONE,
                List.of(Material.COBWEB)),
        JUNGLE(List.of(Material.MOSSY_COBBLESTONE, Material.MOSSY_STONE_BRICKS, Material.COBBLESTONE, Material.MOSSY_STONE_BRICKS),
                List.of(Material.MOSS_BLOCK, Material.MOSSY_COBBLESTONE, Material.COBBLESTONE), Material.CHISELED_STONE_BRICKS,
                List.of(Material.COBWEB, Material.MOSS_CARPET)),
        FESTIVE(List.of(Material.SNOW_BLOCK, Material.PACKED_ICE, Material.SPRUCE_PLANKS, Material.SNOW_BLOCK),
                List.of(Material.SPRUCE_PLANKS, Material.SNOW_BLOCK, Material.SPRUCE_PLANKS), Material.SPRUCE_LOG,
                List.of(Material.RED_WOOL, Material.GREEN_WOOL, Material.LANTERN));

        final List<Material> walls;
        final List<Material> floor;
        final Material pillar;
        final List<Material> decor;

        Style(List<Material> walls, List<Material> floor, Material pillar, List<Material> decor) {
            this.walls = walls;
            this.floor = floor;
            this.pillar = pillar;
            this.decor = decor;
        }

        static Style forBiome(String biomeKey, double festiveChance, Random rnd) {
            String b = biomeKey.toLowerCase(Locale.ROOT);
            if (rnd.nextDouble() < festiveChance) return FESTIVE;
            if (b.contains("snow") || b.contains("ice") || b.contains("frozen")) return FESTIVE;
            if (b.contains("desert") || b.contains("beach")) return SANDSTONE;
            if (b.contains("badlands")) return BADLANDS;
            if (b.contains("jungle") || b.contains("swamp") || b.contains("lush")) return JUNGLE;
            return STONE;
        }
    }

    /** Result of a build: the lucky block position. */
    record Built(int x, int y, int z, Style style) {
    }

    private RuinBuilder() {
    }

    /**
     * Builds a ruin whose footprint starts at (x0, z0). Returns null (nothing changed) when the ground
     * is unsuitable: water, lava, or too uneven.
     */
    static Built build(Sink sink, int x0, int z0, int size, Material lucky, double festiveChance, Random rnd) {
        int cx = x0 + size / 2;
        int cz = z0 + size / 2;
        int y = sink.surface(cx, cz);
        Material top = sink.get(cx, y, cz);
        if (top == Material.WATER || top == Material.LAVA || top == Material.ICE || !top.isSolid()) return null;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int[] c : new int[][]{{x0, z0}, {x0 + size - 1, z0}, {x0, z0 + size - 1}, {x0 + size - 1, z0 + size - 1}}) {
            int h = sink.surface(c[0], c[1]);
            Material m = sink.get(c[0], h, c[1]);
            if (m == Material.WATER || m == Material.LAVA) return null;
            minY = Math.min(minY, h);
            maxY = Math.max(maxY, h);
        }
        if (maxY - minY > 6) return null;
        Style style = Style.forBiome(sink.biome(cx, y, cz), festiveChance, rnd);
        int floorY = y;
        for (int x = x0; x < x0 + size; x++) {
            for (int z = z0; z < z0 + size; z++) {
                // Foundation down to the ground, floor (partly broken), air above.
                for (int yy = floorY - 1; yy >= floorY - 4; yy--) {
                    Material m = sink.get(x, yy, z);
                    if (m.isAir() || m == Material.WATER || !m.isSolid()) sink.set(x, yy, z, style.floor.get(0));
                }
                if (rnd.nextDouble() < 0.85) sink.set(x, floorY, z, pick(style.floor, rnd));
                for (int yy = floorY + 1; yy <= floorY + 5; yy++) sink.set(x, yy, z, Material.AIR);
            }
        }
        int max = size - 1;
        for (int i = 0; i <= max; i++) {
            wall(sink, style, x0 + i, floorY, z0, rnd);
            wall(sink, style, x0 + i, floorY, z0 + max, rnd);
            wall(sink, style, x0, floorY, z0 + i, rnd);
            wall(sink, style, x0 + max, floorY, z0 + i, rnd);
        }
        for (int[] c : new int[][]{{x0, z0}, {x0 + max, z0}, {x0, z0 + max}, {x0 + max, z0 + max}}) {
            int h = 2 + rnd.nextInt(3);
            for (int yy = 1; yy <= h; yy++) sink.set(c[0], floorY + yy, c[1], style.pillar);
            if (style == Style.FESTIVE) sink.set(c[0], floorY + h + 1, c[1], Material.LANTERN);
        }
        // Doorway.
        sink.set(x0 + size / 2, floorY + 1, z0, Material.AIR);
        sink.set(x0 + size / 2, floorY + 2, z0, Material.AIR);
        for (int i = 0; i < size; i++) {
            int x = x0 + 1 + rnd.nextInt(size - 2);
            int z = z0 + 1 + rnd.nextInt(size - 2);
            if (rnd.nextDouble() < 0.5) {
                Material d = pick(style.decor, rnd);
                if (d != Material.LANTERN) sink.set(x, floorY + 1, z, d);
            }
        }
        // One lucky block on one of four predefined inner positions.
        int[][] spots = {{x0 + 1, z0 + 1}, {x0 + max - 1, z0 + 1}, {x0 + 1, z0 + max - 1}, {x0 + max - 1, z0 + max - 1}};
        int[] s = spots[rnd.nextInt(spots.length)];
        sink.set(s[0], floorY, s[1], style.floor.get(0));
        sink.set(s[0], floorY + 1, s[1], lucky);
        return new Built(s[0], floorY + 1, s[1], style);
    }

    private static void wall(Sink sink, Style style, int x, int floorY, int z, Random rnd) {
        int h = rnd.nextDouble() < 0.25 ? 0 : 1 + rnd.nextInt(4);
        for (int yy = 1; yy <= h; yy++) sink.set(x, floorY + yy, z, pick(style.walls, rnd));
    }

    private static Material pick(List<Material> list, Random rnd) {
        return list.get(rnd.nextInt(list.size()));
    }
}
