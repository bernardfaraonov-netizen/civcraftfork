package com.civcraft.structure.placement;

import org.bukkit.block.BlockFace;
import org.bukkit.block.structure.StructureRotation;

/**
 * Pure placement math. Templates are authored with their front on the south side (+Z). A player looking in
 * direction F gets the structure in front of them with its front facing back towards them. Footprints are chunk
 * aligned: the row of chunks nearest to the player is the chunk the player stands in, the footprint extends away from
 * the player and is centred on the player's chunk sideways.
 */
public final class Orientation {

    private Orientation() {
    }

    /** Chunk rectangle (inclusive minimum, size in chunks). */
    public record ChunkRect(int minX, int minZ, int sizeX, int sizeZ) {

        public int maxX() {
            return minX + sizeX - 1;
        }

        public int maxZ() {
            return minZ + sizeZ - 1;
        }

        public boolean contains(int cx, int cz) {
            return cx >= minX && cx <= maxX() && cz >= minZ && cz <= maxZ();
        }
    }

    /** Horizontal facing from a yaw in degrees (Minecraft: 0 = south, 90 = west, 180 = north, 270 = east). */
    public static BlockFace facing(float yaw) {
        int quadrant = Math.floorMod(Math.round(yaw / 90f), 4);
        return switch (quadrant) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    /** Rotation that turns the template's front (south) towards a player looking in {@code facing}. */
    public static StructureRotation rotation(BlockFace facing) {
        return switch (facing) {
            case EAST -> StructureRotation.CLOCKWISE_90;
            case SOUTH -> StructureRotation.CLOCKWISE_180;
            case WEST -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        };
    }

    public static boolean swapsAxes(StructureRotation rotation) {
        return rotation == StructureRotation.CLOCKWISE_90 || rotation == StructureRotation.COUNTERCLOCKWISE_90;
    }

    /** Size {x, z} after rotating a {sizeX × sizeZ} footprint. */
    public static int[] rotatedSize(int sizeX, int sizeZ, StructureRotation rotation) {
        return swapsAxes(rotation) ? new int[]{sizeZ, sizeX} : new int[]{sizeX, sizeZ};
    }

    /**
     * World chunk rectangle of a footprint of {@code chunksX × chunksZ} chunks (template axes) placed by a player in
     * chunk ({@code pcx}, {@code pcz}) looking in {@code facing}.
     */
    public static ChunkRect footprint(int pcx, int pcz, BlockFace facing, int chunksX, int chunksZ) {
        int[] size = rotatedSize(chunksX, chunksZ, rotation(facing));
        int wx = size[0];
        int wz = size[1];
        return switch (facing) {
            case SOUTH -> new ChunkRect(pcx - wx / 2, pcz, wx, wz);
            case EAST -> new ChunkRect(pcx, pcz - (wz - 1) / 2, wx, wz);
            case WEST -> new ChunkRect(pcx - wx + 1, pcz - wz / 2, wx, wz);
            default -> new ChunkRect(pcx - (wx - 1) / 2, pcz - wz + 1, wx, wz);
        };
    }

    /**
     * Offset of a template of rotated size {@code templateSize} inside a footprint of {@code footprintSize} blocks
     * along one axis: centred; a larger template overhangs on the far side.
     */
    public static int centreOffset(int footprintSize, int templateSize) {
        return Math.max(0, (footprintSize - templateSize) / 2);
    }

    /** Position of an unrotated template cell after rotation (same convention as {@code Template.rotate}). */
    public static int[] rotatePoint(StructureRotation rotation, int x, int z, int sizeX, int sizeZ) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new int[]{sizeZ - 1 - z, x};
            case CLOCKWISE_180 -> new int[]{sizeX - 1 - x, sizeZ - 1 - z};
            case COUNTERCLOCKWISE_90 -> new int[]{z, sizeX - 1 - x};
            default -> new int[]{x, z};
        };
    }

    /** Inverse of {@link #rotatePoint}: the unrotated cell of a rotated position. */
    public static int[] unrotatePoint(StructureRotation rotation, int rx, int rz, int sizeX, int sizeZ) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new int[]{rz, sizeZ - 1 - rx};
            case CLOCKWISE_180 -> new int[]{sizeX - 1 - rx, sizeZ - 1 - rz};
            case COUNTERCLOCKWISE_90 -> new int[]{sizeX - 1 - rz, rx};
            default -> new int[]{rx, rz};
        };
    }

    /** The direction the front of a rotated template faces. */
    public static BlockFace frontFace(StructureRotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> BlockFace.WEST;
            case CLOCKWISE_180 -> BlockFace.NORTH;
            case COUNTERCLOCKWISE_90 -> BlockFace.EAST;
            default -> BlockFace.SOUTH;
        };
    }

    /** Parses a height argument: absolute ("70") or relative ("+2", "-1") to {@code currentY}; null if invalid. */
    public static Integer parseHeight(String token, int currentY) {
        if (token == null || token.isEmpty()) return null;
        boolean relative = token.charAt(0) == '+' || token.charAt(0) == '-';
        try {
            int value = Integer.parseInt(relative && token.charAt(0) == '+' ? token.substring(1) : token);
            if (Math.abs(value) > 4096) return null;
            return relative ? currentY + value : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
