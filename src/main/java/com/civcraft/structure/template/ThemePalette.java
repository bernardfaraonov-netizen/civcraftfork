package com.civcraft.structure.template;

import java.util.Locale;
import org.bukkit.Material;

/** Block palette of a template theme for procedural buildings. */
public record ThemePalette(
        Material foundation,
        Material floor,
        Material wall,
        Material wallAlt,
        Material pillar,
        Material trim,
        Material roofStairs,
        Material roofSlab,
        Material roofBlock,
        Material glass,
        Material bars,
        Material door,
        Material light,
        Material fence,
        Material accent,
        Material ground,
        Material path,
        Material wool,
        Material hull,
        Material deck,
        Material log) {

    public static ThemePalette of(String theme) {
        return switch (theme == null ? "default" : theme.toLowerCase(Locale.ROOT)) {
            case "arctic" -> new ThemePalette(
                    Material.POLISHED_DIORITE, Material.SPRUCE_PLANKS, Material.SPRUCE_PLANKS, Material.PACKED_ICE,
                    Material.STRIPPED_SPRUCE_LOG, Material.SNOW_BLOCK, Material.SPRUCE_STAIRS, Material.SPRUCE_SLAB,
                    Material.SNOW_BLOCK, Material.LIGHT_BLUE_STAINED_GLASS, Material.IRON_BARS, Material.SPRUCE_DOOR,
                    Material.SOUL_LANTERN, Material.SPRUCE_FENCE, Material.BLUE_ICE, Material.SNOW_BLOCK,
                    Material.PACKED_ICE, Material.LIGHT_BLUE_WOOL, Material.SPRUCE_PLANKS, Material.STRIPPED_SPRUCE_WOOD,
                    Material.SPRUCE_LOG);
            case "aztec" -> new ThemePalette(
                    Material.MOSSY_STONE_BRICKS, Material.JUNGLE_PLANKS, Material.STONE_BRICKS, Material.MOSSY_COBBLESTONE,
                    Material.STRIPPED_JUNGLE_LOG, Material.CHISELED_STONE_BRICKS, Material.MOSSY_STONE_BRICK_STAIRS,
                    Material.MOSSY_STONE_BRICK_SLAB, Material.MOSSY_STONE_BRICKS, Material.LIME_STAINED_GLASS,
                    Material.IRON_BARS, Material.JUNGLE_DOOR, Material.LANTERN, Material.JUNGLE_FENCE, Material.GOLD_BLOCK,
                    Material.MOSS_BLOCK, Material.COARSE_DIRT, Material.GREEN_WOOL, Material.JUNGLE_PLANKS,
                    Material.STRIPPED_JUNGLE_WOOD, Material.JUNGLE_LOG);
            case "egyptian" -> new ThemePalette(
                    Material.SMOOTH_SANDSTONE, Material.CUT_SANDSTONE, Material.SANDSTONE, Material.SMOOTH_SANDSTONE,
                    Material.CUT_SANDSTONE, Material.CHISELED_SANDSTONE, Material.SANDSTONE_STAIRS, Material.SANDSTONE_SLAB,
                    Material.SMOOTH_SANDSTONE, Material.ORANGE_STAINED_GLASS, Material.IRON_BARS, Material.ACACIA_DOOR,
                    Material.LANTERN, Material.ACACIA_FENCE, Material.LAPIS_BLOCK, Material.SAND, Material.SMOOTH_SANDSTONE,
                    Material.YELLOW_WOOL, Material.ACACIA_PLANKS, Material.STRIPPED_ACACIA_WOOD, Material.ACACIA_LOG);
            case "hell" -> new ThemePalette(
                    Material.BLACKSTONE, Material.CRIMSON_PLANKS, Material.NETHER_BRICKS, Material.POLISHED_BLACKSTONE_BRICKS,
                    Material.POLISHED_BASALT, Material.RED_NETHER_BRICKS, Material.NETHER_BRICK_STAIRS,
                    Material.NETHER_BRICK_SLAB, Material.NETHER_BRICKS, Material.RED_STAINED_GLASS, Material.IRON_BARS,
                    Material.CRIMSON_DOOR, Material.SHROOMLIGHT, Material.NETHER_BRICK_FENCE, Material.GILDED_BLACKSTONE,
                    Material.CRIMSON_NYLIUM, Material.POLISHED_BLACKSTONE, Material.RED_WOOL, Material.CRIMSON_PLANKS,
                    Material.STRIPPED_CRIMSON_HYPHAE, Material.CRIMSON_STEM);
            case "roman" -> new ThemePalette(
                    Material.SMOOTH_STONE, Material.SMOOTH_QUARTZ, Material.WHITE_TERRACOTTA, Material.QUARTZ_BRICKS,
                    Material.QUARTZ_PILLAR, Material.CHISELED_QUARTZ_BLOCK, Material.BRICK_STAIRS, Material.BRICK_SLAB,
                    Material.BRICKS, Material.GLASS, Material.IRON_BARS, Material.BIRCH_DOOR, Material.LANTERN,
                    Material.BIRCH_FENCE, Material.GOLD_BLOCK, Material.GRASS_BLOCK, Material.POLISHED_ANDESITE,
                    Material.RED_WOOL, Material.BIRCH_PLANKS, Material.STRIPPED_BIRCH_WOOD, Material.BIRCH_LOG);
            default -> new ThemePalette(
                    Material.STONE_BRICKS, Material.SPRUCE_PLANKS, Material.OAK_PLANKS, Material.COBBLESTONE,
                    Material.STRIPPED_SPRUCE_LOG, Material.POLISHED_ANDESITE, Material.DARK_OAK_STAIRS,
                    Material.DARK_OAK_SLAB, Material.DARK_OAK_PLANKS, Material.GLASS, Material.IRON_BARS,
                    Material.SPRUCE_DOOR, Material.LANTERN, Material.SPRUCE_FENCE, Material.GOLD_BLOCK,
                    Material.GRASS_BLOCK, Material.DIRT_PATH, Material.WHITE_WOOL, Material.DARK_OAK_PLANKS,
                    Material.SPRUCE_PLANKS, Material.SPRUCE_LOG);
        };
    }
}
