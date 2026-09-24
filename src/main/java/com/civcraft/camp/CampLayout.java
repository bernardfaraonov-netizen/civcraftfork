package com.civcraft.camp;

import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.core.util.Cuboid;
import com.civcraft.model.Camp;
import com.civcraft.template.Template;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/**
 * The resolved template of a placed camp: its box and the absolute positions of every functional
 * marker ({@code /control}, {@code /door}, {@code /firefurnace}, {@code /firepit}, {@code /fire},
 * {@code /growth}, {@code /gardensign}, {@code /sifter}, {@code /foodinput}). Rebuilt from the template on
 * load, so nothing about blocks has to be persisted.
 */
final class CampLayout {

    record Point(BlockPos pos, String facing, int id) {
    }

    final Camp camp;
    final Template template;
    final Cuboid box;
    Point control;
    final List<Point> doors = new ArrayList<>();
    final List<Point> furnaces = new ArrayList<>();
    final List<Point> firepits = new ArrayList<>();
    final List<Point> fires = new ArrayList<>();
    final List<Point> growth = new ArrayList<>();
    final List<Point> gardenSigns = new ArrayList<>();
    final List<Point> foodInputs = new ArrayList<>();
    Point sifterIn;
    Point sifterOut;
    final Set<BlockPos> markers = new HashSet<>();
    final Set<BlockPos> doorTops = new HashSet<>();

    CampLayout(Camp camp, Template template) {
        this.camp = camp;
        this.template = template;
        BlockPos o = camp.origin();
        this.box = Cuboid.of(o, template.sizeX(), template.sizeY(), template.sizeZ());
        TreeMap<Integer, Point> pits = new TreeMap<>();
        int pitSeq = 1000;
        for (Template.Marker m : template.markers()) {
            BlockPos pos = o.offset(m.x(), m.y(), m.z());
            int id = markerId(m.args());
            Point p = new Point(pos, m.facing(), id);
            markers.add(pos);
            switch (m.type().toLowerCase(Locale.ROOT)) {
                case "control" -> control = p;
                case "door" -> {
                    doors.add(p);
                    doorTops.add(pos.offset(0, 1, 0));
                }
                case "firefurnace" -> furnaces.add(p);
                case "firepit" -> pits.put(id >= 0 ? id : pitSeq++, p);
                case "fire" -> fires.add(p);
                case "growth" -> growth.add(p);
                case "gardensign" -> gardenSigns.add(p);
                case "foodinput" -> foodInputs.add(p);
                case "sifter" -> {
                    if (id == 1) sifterOut = p;
                    else sifterIn = p;
                }
                default -> {
                }
            }
        }
        firepits.addAll(pits.values());
    }

    private static int markerId(List<String> args) {
        for (String a : args) {
            String v = a.toLowerCase(Locale.ROOT).startsWith("id:") ? a.substring(3) : a;
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException ignored) {
                // not an id argument
            }
        }
        return -1;
    }

    boolean contains(BlockPos pos) {
        return box.contains(pos);
    }

    /** Whether the block is part of the camp structure (template block, marker or door top). */
    boolean structural(BlockPos pos) {
        if (!box.contains(pos)) return false;
        if (markers.contains(pos) || doorTops.contains(pos)) return true;
        int x = pos.x() - box.minX();
        int y = pos.y() - box.minY();
        int z = pos.z() - box.minZ();
        return !template.block(x, y, z).getMaterial().isAir();
    }

    boolean isGrowth(BlockPos pos) {
        for (Point p : growth) if (p.pos().equals(pos)) return true;
        return false;
    }

    boolean isDoorOrContainer(BlockPos pos) {
        for (Point p : doors) if (p.pos().equals(pos)) return true;
        if (doorTops.contains(pos)) return true;
        for (Point p : furnaces) if (p.pos().equals(pos)) return true;
        for (Point p : foodInputs) if (p.pos().equals(pos)) return true;
        return (sifterIn != null && sifterIn.pos().equals(pos)) || (sifterOut != null && sifterOut.pos().equals(pos));
    }

    Set<ChunkKey> chunks() {
        return box.chunks();
    }

    BlockPos center() {
        return box.center();
    }
}
