package com.civcraft.template;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;

/**
 * An immutable block template: a palette of {@link BlockData}, a dense index array and a list of
 * functional markers. Markers come from "command signs" (a sign whose first line starts with
 * {@code /}); their positions are left as air and the owning structure decides what to put there.
 */
public final class Template {

    /** A functional point inside the template, e.g. {@code /control}, {@code /chest 1}. */
    public record Marker(int x, int y, int z, String type, List<String> args, String facing) {
    }

    private final String id;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final BlockData[] palette;
    private final int[] blocks;
    private final List<Marker> markers;
    private final Map<Integer, List<String>> signText;

    public Template(String id, int sizeX, int sizeY, int sizeZ, BlockData[] palette, int[] blocks,
                    List<Marker> markers, Map<Integer, List<String>> signText) {
        this.id = id;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.palette = palette;
        this.blocks = blocks;
        this.markers = List.copyOf(markers);
        this.signText = Map.copyOf(signText);
    }

    public String id() {
        return id;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int index(int x, int y, int z) {
        return x + z * sizeX + y * sizeX * sizeZ;
    }

    /** The block at a template coordinate. The returned data is shared; clone before mutating. */
    public BlockData block(int x, int y, int z) {
        return palette[blocks[index(x, y, z)]];
    }

    public List<String> signText(int x, int y, int z) {
        return signText.get(index(x, y, z));
    }

    public List<Marker> markers() {
        return markers;
    }

    public List<Marker> markers(String type) {
        return markers.stream().filter(m -> m.type().equalsIgnoreCase(type)).toList();
    }

    public int blockCount() {
        return blocks.length;
    }

    /** Number of non-air blocks; used for build-progress calculations. */
    public int solidCount() {
        int count = 0;
        for (int b : blocks) {
            if (!palette[b].getMaterial().isAir()) count++;
        }
        return count;
    }

    /** Returns this template rotated around the Y axis. Rotation is clockwise when seen from above. */
    public Template rotate(StructureRotation rotation) {
        if (rotation == StructureRotation.NONE) return this;
        boolean swap = rotation != StructureRotation.CLOCKWISE_180;
        int nx = swap ? sizeZ : sizeX;
        int nz = swap ? sizeX : sizeZ;
        BlockData[] newPalette = new BlockData[palette.length];
        for (int i = 0; i < palette.length; i++) {
            BlockData data = palette[i].clone();
            data.rotate(rotation);
            newPalette[i] = data;
        }
        int[] newBlocks = new int[blocks.length];
        Map<Integer, List<String>> newSigns = new HashMap<>();
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    int[] r = rotatePoint(rotation, x, z);
                    int target = r[0] + r[1] * nx + y * nx * nz;
                    int source = index(x, y, z);
                    newBlocks[target] = blocks[source];
                    List<String> text = signText.get(source);
                    if (text != null) newSigns.put(target, text);
                }
            }
        }
        List<Marker> newMarkers = new ArrayList<>(markers.size());
        for (Marker m : markers) {
            int[] r = rotatePoint(rotation, m.x(), m.z());
            newMarkers.add(new Marker(r[0], m.y(), r[1], m.type(), m.args(), rotateFacing(m.facing(), rotation)));
        }
        return new Template(id, nx, sizeY, nz, newPalette, newBlocks, newMarkers, newSigns);
    }

    private int[] rotatePoint(StructureRotation rotation, int x, int z) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new int[]{sizeZ - 1 - z, x};
            case CLOCKWISE_180 -> new int[]{sizeX - 1 - x, sizeZ - 1 - z};
            case COUNTERCLOCKWISE_90 -> new int[]{z, sizeX - 1 - x};
            default -> new int[]{x, z};
        };
    }

    private static final String[] FACINGS = {"north", "east", "south", "west"};

    private static String rotateFacing(String facing, StructureRotation rotation) {
        if (facing == null) return null;
        int index = List.of(FACINGS).indexOf(facing);
        if (index < 0) return facing;
        int steps = switch (rotation) {
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
            default -> 0;
        };
        return FACINGS[(index + steps) % 4];
    }
}
