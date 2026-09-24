package com.civcraft.structure.template;

import com.civcraft.structure.type.MarkerSpec;
import com.civcraft.structure.type.StructureType;
import com.civcraft.template.Template;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.block.data.type.Ladder;

/**
 * Generates themed buildings of the right footprint for types without a {@code .schem} template. Every template has
 * a raised plaza (y = 0), a front on the south side (+Z, the side that faces the player) and the functional markers
 * the type needs ({@code markers:} in the balance file). Output is deterministic, so structures keep their layout
 * across restarts.
 */
public final class ProceduralTemplates {

    private ProceduralTemplates() {
    }

    /** A marker location candidate: cell plus the direction a sign / chest there faces. */
    private record Slot(int x, int y, int z, BlockFace facing) {
    }

    private static final class Slots {
        final Deque<Slot> wall = new ArrayDeque<>();
        final Deque<Slot> wallHigh = new ArrayDeque<>();
        final Deque<Slot> floor = new ArrayDeque<>();
        final Deque<Slot> outdoor = new ArrayDeque<>();
        final Deque<Slot> top = new ArrayDeque<>();
        Slot center;
    }

    public static Template generate(StructureType type, String theme) {
        ThemePalette p = ThemePalette.of(theme);
        Slots slots = new Slots();
        TemplateBuilder b = switch (type.style()) {
            case "tower" -> tower(type, p, slots);
            case "spire" -> spire(type, p, slots);
            case "temple" -> temple(type, p, slots);
            case "monument" -> monument(type, p, slots);
            case "market" -> market(type, p, slots);
            case "ship" -> ship(type, p, slots);
            case "field" -> field(type, p, slots);
            case "lighthouse" -> lighthouse(type, p, slots);
            case "wonder" -> wonder(type, p, slots);
            case "industrial" -> hallStyle(type, p, slots, false, true);
            case "workshop" -> hallStyle(type, p, slots, true, false);
            default -> hallStyle(type, p, slots, false, false);
        };
        placeMarkers(b, p, type.markers(), slots);
        b.connectFaces();
        return b.build("proc:" + theme + "/" + type.id());
    }

    // --- shared pieces ----------------------------------------------------------------------------------------------

    /** Raised plaza over the whole footprint with a trim border and lamp posts at the corners. */
    private static void plaza(TemplateBuilder b, ThemePalette p, Slots s) {
        int w = b.sizeX();
        int d = b.sizeZ();
        b.box(0, 0, 0, w - 1, 0, d - 1, p.foundation());
        for (int x = 0; x < w; x++) {
            b.set(x, 0, 0, p.trim());
            b.set(x, 0, d - 1, p.trim());
        }
        for (int z = 0; z < d; z++) {
            b.set(0, 0, z, p.trim());
            b.set(w - 1, 0, z, p.trim());
        }
        for (int[] c : new int[][]{{1, 1}, {w - 2, 1}, {1, d - 2}, {w - 2, d - 2}}) {
            b.box(c[0], 1, c[1], c[0], 2, c[1], p.fence());
            b.set(c[0], 3, c[1], p.light());
        }
        // Control pedestal ring: every ~6 blocks along an inset-1 ring, skipping corners and the front path.
        int mid = w / 2;
        for (int x = 4; x < w - 4; x += 6) {
            if (Math.abs(x - mid) > 2) s.outdoor.add(new Slot(x, 1, d - 2, BlockFace.SOUTH));
            s.outdoor.add(new Slot(x, 1, 1, BlockFace.NORTH));
        }
        for (int z = 4; z < d - 4; z += 6) {
            s.outdoor.add(new Slot(1, 1, z, BlockFace.WEST));
            s.outdoor.add(new Slot(w - 2, 1, z, BlockFace.EAST));
        }
        s.center = new Slot(w / 2, 1, d / 2, BlockFace.SOUTH);
    }

    /** Walk from the front door to the front edge of the plaza. */
    private static void path(TemplateBuilder b, ThemePalette p, int x, int fromZ) {
        for (int z = fromZ; z < b.sizeZ() - 1; z++) {
            b.set(x, 0, z, p.path());
            b.set(x - 1, 0, z, p.path());
            b.set(x + 1, 0, z, p.path());
        }
    }

    /**
     * A closed building on rect (x1..x2, z1..z2) with floor at {@code y0}, walls up to {@code y0 + wallH}, corner
     * pillars, a trim band, windows, a front door, a ceiling beam with lanterns and interior marker slots.
     */
    private static void building(TemplateBuilder b, ThemePalette p, Slots s, int x1, int z1, int x2, int z2, int y0,
                                 int wallH, Material wallMat) {
        b.box(x1, y0, z1, x2, y0, z2, p.floor());
        b.walls(x1, y0 + 1, z1, x2, y0 + wallH, z2, wallMat);
        BlockData pillar = TemplateBuilder.axis(p.pillar(), Axis.Y);
        for (int[] c : new int[][]{{x1, z1}, {x2, z1}, {x1, z2}, {x2, z2}}) b.box(c[0], y0 + 1, c[1], c[0], y0 + wallH, c[1], pillar);
        for (int x = x1; x <= x2; x++) {
            b.set(x, y0 + wallH, z1, p.trim());
            b.set(x, y0 + wallH, z2, p.trim());
        }
        for (int z = z1; z <= z2; z++) {
            b.set(x1, y0 + wallH, z, p.trim());
            b.set(x2, y0 + wallH, z, p.trim());
        }
        int winTop = Math.min(y0 + 3, y0 + wallH - 2);
        for (int x = x1 + 2; x <= x2 - 2; x += 3) {
            for (int y = y0 + 2; y <= winTop; y++) {
                b.set(x, y, z1, p.glass());
                b.set(x, y, z2, p.glass());
            }
        }
        for (int z = z1 + 2; z <= z2 - 2; z += 3) {
            for (int y = y0 + 2; y <= winTop; y++) {
                b.set(x1, y, z, p.glass());
                b.set(x2, y, z, p.glass());
            }
        }
        int xm = (x1 + x2) / 2;
        b.set(xm, y0 + 1, z2, TemplateBuilder.door(p.door(), BlockFace.NORTH, false));
        b.set(xm, y0 + 2, z2, TemplateBuilder.door(p.door(), BlockFace.NORTH, true));
        b.set(xm - 1, y0 + 2, z2, wallMat);
        b.set(xm + 1, y0 + 2, z2, wallMat);
        b.set(xm, y0 + 3, z2, p.trim());
        // Ceiling beams with hanging lanterns.
        BlockData beam = TemplateBuilder.axis(p.log(), Axis.Z);
        for (int x = x1 + 3; x <= x2 - 3; x += 4) {
            for (int z = z1 + 1; z <= z2 - 1; z++) b.set(x, y0 + wallH, z, beam);
            for (int z = z1 + 3; z <= z2 - 3; z += 4) b.set(x, y0 + wallH - 1, z, TemplateBuilder.hanging(p.light()));
        }
        // Marker slots: back wall first, then the side walls, chests at floor level and signs above them.
        for (int x = x1 + 2; x <= x2 - 2; x += 2) {
            if (Math.abs(x - xm) <= 1) continue;
            s.wall.add(new Slot(x, y0 + 1, z1 + 1, BlockFace.SOUTH));
            s.wallHigh.add(new Slot(x, y0 + 2, z1 + 1, BlockFace.SOUTH));
        }
        for (int z = z1 + 2; z <= z2 - 2; z += 2) {
            s.wall.add(new Slot(x1 + 1, y0 + 1, z, BlockFace.EAST));
            s.wall.add(new Slot(x2 - 1, y0 + 1, z, BlockFace.WEST));
            s.wallHigh.add(new Slot(x1 + 1, y0 + 2, z, BlockFace.EAST));
            s.wallHigh.add(new Slot(x2 - 1, y0 + 2, z, BlockFace.WEST));
        }
        for (int z = z1 + 3; z <= z2 - 3; z += 3) {
            for (int x = x1 + 3; x <= x2 - 3; x += 3) s.floor.add(new Slot(x, y0 + 1, z, BlockFace.SOUTH));
        }
    }

    /** Gable roof over the rect with a one block overhang; ridge along the longer side. Returns its height. */
    private static int gableRoof(TemplateBuilder b, ThemePalette p, int x1, int z1, int x2, int z2, int yBase, Material gable) {
        boolean ridgeAlongX = (x2 - x1) >= (z2 - z1);
        int rx1 = x1 - 1, rx2 = x2 + 1, rz1 = z1 - 1, rz2 = z2 + 1;
        int k = 0;
        while (true) {
            int y = yBase + k;
            if (ridgeAlongX) {
                int zs = rz2 - k;
                int zn = rz1 + k;
                if (zn > zs) break;
                if (zn == zs) {
                    for (int x = rx1; x <= rx2; x++) b.set(x, y, zn, TemplateBuilder.slab(p.roofSlab(), false));
                    k++;
                    break;
                }
                for (int x = rx1; x <= rx2; x++) {
                    b.set(x, y, zs, TemplateBuilder.stairs(p.roofStairs(), BlockFace.NORTH, false));
                    b.set(x, y, zn, TemplateBuilder.stairs(p.roofStairs(), BlockFace.SOUTH, false));
                }
                for (int z = zn + 1; z <= zs - 1; z++) {
                    b.set(x1, y, z, gable);
                    b.set(x2, y, z, gable);
                }
            } else {
                int xe = rx2 - k;
                int xw = rx1 + k;
                if (xw > xe) break;
                if (xw == xe) {
                    for (int z = rz1; z <= rz2; z++) b.set(xw, y, z, TemplateBuilder.slab(p.roofSlab(), false));
                    k++;
                    break;
                }
                for (int z = rz1; z <= rz2; z++) {
                    b.set(xe, y, z, TemplateBuilder.stairs(p.roofStairs(), BlockFace.WEST, false));
                    b.set(xw, y, z, TemplateBuilder.stairs(p.roofStairs(), BlockFace.EAST, false));
                }
                for (int x = xw + 1; x <= xe - 1; x++) {
                    b.set(x, y, z1, gable);
                    b.set(x, y, z2, gable);
                }
            }
            k++;
        }
        return k;
    }

    private static int gableHeight(int x1, int z1, int x2, int z2) {
        int span = Math.min(x2 - x1, z2 - z1) + 3;
        return (span + 1) / 2 + 1;
    }

    /** Flat roof with a trim parapet. */
    private static void flatRoof(TemplateBuilder b, ThemePalette p, int x1, int z1, int x2, int z2, int y) {
        b.box(x1, y, z1, x2, y, z2, p.roofBlock());
        for (int x = x1; x <= x2; x++) {
            b.set(x, y + 1, z1, p.trim());
            b.set(x, y + 1, z2, p.trim());
        }
        for (int z = z1; z <= z2; z++) {
            b.set(x1, y + 1, z, p.trim());
            b.set(x2, y + 1, z, p.trim());
        }
    }

    private static void chimney(TemplateBuilder b, int x, int z, int yFrom, int yTo) {
        b.box(x, yFrom, z, x, yTo, z, Material.BRICKS);
        BlockData fire = Material.CAMPFIRE.createBlockData();
        if (fire instanceof Campfire c) c.setSignalFire(true);
        b.set(x, yTo + 1, z, fire);
    }

    private static int wallHeight(StructureType t) {
        int chunks = Math.min(t.chunksX(), t.chunksZ());
        return Math.max(5, Math.min(11, 4 + chunks * 2));
    }

    // --- styles -----------------------------------------------------------------------------------------------------

    private static TemplateBuilder hallStyle(StructureType t, ThemePalette p, Slots s, boolean workshop, boolean industrial) {
        int w = t.blocksX();
        int d = t.blocksZ();
        int m = Math.min(w, d) >= 32 ? 3 : 2;
        int x1 = m, z1 = m, x2 = w - 1 - m, z2 = d - 2 - m;
        int wallH = wallHeight(t) + (industrial ? 1 : 0);
        int roofH = industrial ? 2 : gableHeight(x1, z1, x2, z2);
        int sy = 1 + wallH + roofH + (workshop || industrial ? 9 : 2);
        TemplateBuilder b = new TemplateBuilder(w, sy, d);
        plaza(b, p, s);
        Material wallMat = industrial ? p.wallAlt() : p.wall();
        building(b, p, s, x1, z1, x2, z2, 0, wallH, wallMat);
        if (industrial) {
            flatRoof(b, p, x1, z1, x2, z2, wallH + 1);
            // Wide factory gate instead of a single door.
            int xm = (x1 + x2) / 2;
            b.box(xm - 1, 1, z2, xm + 1, 3, z2, Material.AIR);
            b.box(xm - 2, 4, z2, xm + 2, 4, z2, p.trim());
            for (int x = x1 + 2; x <= x2 - 2; x += 3) {
                b.set(x, 2, z1, p.bars());
                b.set(x, 3, z1, p.bars());
            }
            int top = sy - 2;
            chimney(b, x1 + 2, z1 + 2, wallH + 2, top);
            chimney(b, x2 - 2, z1 + 2, wallH + 2, top);
            if (x2 - x1 > 30) chimney(b, (x1 + x2) / 2, z1 + 2, wallH + 2, top);
        } else {
            gableRoof(b, p, x1, z1, x2, z2, wallH + 1, wallMat);
            if (workshop) chimney(b, x2 - 2, z1 - 1, 1, sy - 2);
        }
        path(b, p, (x1 + x2) / 2, z2 + 1);
        if (workshop) {
            b.set(x1 - 1, 1, z2 - 1, Material.BARREL);
            b.set(x1 - 1, 1, z2 - 2, Material.BARREL);
            b.set(x1 - 1, 2, z2 - 1, Material.BARREL);
            b.set(x2 + 1, 1, z2 - 1, Material.CRAFTING_TABLE);
        }
        return b;
    }

    private static TemplateBuilder tower(StructureType t, ThemePalette p, Slots s) {
        int w = t.blocksX();
        int d = t.blocksZ();
        if (Math.min(w, d) >= 32) return castle(t, p, s);
        int side = Math.min(8, Math.min(w, d) - 6);
        int x1 = (w - side) / 2, z1 = (d - side) / 2, x2 = x1 + side - 1, z2 = z1 + side - 1;
        int h = 24;
        TemplateBuilder b = new TemplateBuilder(w, h + 4, d);
        plaza(b, p, s);
        b.box(x1, 0, z1, x2, 0, z2, p.floor());
        b.walls(x1, 1, z1, x2, h, z2, p.wall());
        BlockData pillar = TemplateBuilder.axis(p.pillar(), Axis.Y);
        for (int[] c : new int[][]{{x1, z1}, {x2, z1}, {x1, z2}, {x2, z2}}) b.box(c[0], 1, c[1], c[0], h, c[1], pillar);
        int xm = (x1 + x2) / 2;
        int zm = (z1 + z2) / 2;
        for (int y = 6; y < h; y += 6) {
            b.box(x1 + 1, y, z1 + 1, x2 - 1, y, z2 - 1, p.floor());
            for (int x = x1; x <= x2; x++) {
                b.set(x, y, z1, p.trim());
                b.set(x, y, z2, p.trim());
            }
            for (int z = z1; z <= z2; z++) {
                b.set(x1, y, z, p.trim());
                b.set(x2, y, z, p.trim());
            }
        }
        for (int y = 3; y < h; y += 6) {
            b.set(xm, y, z1, p.bars());
            b.set(xm, y, z2, p.bars());
            b.set(x1, y, zm, p.bars());
            b.set(x2, y, zm, p.bars());
        }
        BlockData ladder = Material.LADDER.createBlockData();
        if (ladder instanceof Ladder l) l.setFacing(BlockFace.SOUTH);
        for (int y = 1; y < h; y++) b.set(x1 + 1, y, z1 + 1, ladder);
        b.set(xm, 1, z2, TemplateBuilder.door(p.door(), BlockFace.NORTH, false));
        b.set(xm, 2, z2, TemplateBuilder.door(p.door(), BlockFace.NORTH, true));
        b.box(x1, h, z1, x2, h, z2, p.roofBlock());
        b.set(x1 + 1, h, z1 + 1, ladder);
        battlements(b, p, x1, z1, x2, z2, h + 1);
        b.set(xm, h + 1, zm, p.light());
        s.top.add(new Slot(x1 + 1, h + 1, z2 - 1, BlockFace.SOUTH));
        s.top.add(new Slot(x2 - 1, h + 1, z2 - 1, BlockFace.EAST));
        s.top.add(new Slot(x2 - 1, h + 1, z1 + 1, BlockFace.NORTH));
        s.top.add(new Slot(x1 + 2, h + 1, z1 + 1, BlockFace.WEST));
        s.wall.add(new Slot(x2 - 1, 1, z1 + 1, BlockFace.SOUTH));
        s.wall.add(new Slot(x2 - 1, 1, zm, BlockFace.WEST));
        s.wallHigh.add(new Slot(x2 - 1, 2, zm, BlockFace.WEST));
        s.wallHigh.add(new Slot(x1 + 1, 2, zm, BlockFace.EAST));
        s.floor.add(new Slot(xm, 1, zm, BlockFace.SOUTH));
        s.floor.add(new Slot(xm + 1, 1, zm + 1, BlockFace.SOUTH));
        path(b, p, xm, z2 + 1);
        return b;
    }

    /** A tall tapering tower (skyscraper, Eiffel tower, launch tower) on a plaza. */
    private static TemplateBuilder spire(StructureType t, ThemePalette p, Slots s) {
        int w = t.blocksX();
        int d = t.blocksZ();
        int half = Math.max(4, (Math.min(w, d) - 16) / 2);
        int h = 40 + Math.min(t.chunksX(), t.chunksZ()) * 8;
        TemplateBuilder b = new TemplateBuilder(w, h + 8, d);
        plaza(b, p, s);
        int cx = w / 2;
        int cz = d / 2;
        for (int y = 1; y <= h; y++) {
            int r = Math.max(1, half - (y - 1) * (half - 1) / h);
            for (int i = -r; i <= r; i++) {
                for (int[] c : new int[][]{{cx + i, cz - r}, {cx + i, cz + r}, {cx - r, cz + i}, {cx + r, cz + i}}) {
                    Material m;
                    if (y % 8 == 0) m = p.trim();
                    else if (Math.abs(i) == r) m = p.pillar();
                    else if (Math.floorMod(i, 3) == 0 && y % 8 >= 2 && y % 8 <= 5) m = p.glass();
                    else m = p.wallAlt();
                    b.set(c[0], y, c[1], m == p.pillar() ? TemplateBuilder.axis(m, Axis.Y) : m.createBlockData());
                }
            }
            if (y % 8 == 0 && r > 1) b.box(cx - r + 1, y, cz - r + 1, cx + r - 1, y, cz + r - 1, p.floor());
        }
        b.box(cx - half + 1, 0, cz - half + 1, cx + half - 1, 0, cz + half - 1, p.floor());
        b.box(cx, h + 1, cz, cx, h + 5, cz, TemplateBuilder.axis(p.pillar(), Axis.Y));
        b.set(cx, h + 6, cz, p.accent());
        b.set(cx, h + 7, cz, p.light());
        b.box(cx - 1, 1, cz + half, cx + 1, 3, cz + half, Material.AIR);
        b.set(cx, 4, cz + half, p.trim());
        for (int x = cx - half + 3; x <= cx + half - 3; x += 3) {
            for (int z = cz - half + 3; z <= cz + half - 3; z += 3) s.floor.add(new Slot(x, 1, z, BlockFace.SOUTH));
        }
        for (int x = cx - half + 2; x <= cx + half - 2; x += 3) s.wall.add(new Slot(x, 1, cz - half + 1, BlockFace.SOUTH));
        for (int x = cx - half + 2; x <= cx + half - 2; x += 3) s.wallHigh.add(new Slot(x, 2, cz - half + 1, BlockFace.SOUTH));
        s.top.add(new Slot(cx + 1, h + 1, cz, BlockFace.EAST));
        s.top.add(new Slot(cx - 1, h + 1, cz, BlockFace.WEST));
        path(b, p, cx, cz + half + 1);
        return b;
    }

    private static void battlements(TemplateBuilder b, ThemePalette p, int x1, int z1, int x2, int z2, int y) {
        for (int x = x1; x <= x2; x++) {
            if ((x - x1) % 2 == 0) {
                b.set(x, y, z1, p.trim());
                b.set(x, y, z2, p.trim());
            }
        }
        for (int z = z1; z <= z2; z++) {
            if ((z - z1) % 2 == 0) {
                b.set(x1, y, z, p.trim());
                b.set(x2, y, z, p.trim());
            }
        }
    }

    /** Larger defensive footprint: curtain wall, four corner towers and a keep. */
    private static TemplateBuilder castle(StructureType t, ThemePalette p, Slots s) {
        int w = t.blocksX();
        int d = t.blocksZ();
        int wallH = 7;
        int towerH = 13;
        int kx1 = 9, kz1 = 9, kx2 = w - 10, kz2 = d - 12;
        int keepH = 9;
        int roof = gableHeight(kx1, kz1, kx2, kz2);
        int sy = Math.max(towerH + 3, 1 + keepH + roof + 2);
        TemplateBuilder b = new TemplateBuilder(w, sy, d);
        plaza(b, p, s);
        s.outdoor.clear();
        int c1 = 2, c2x = w - 3, c2z = d - 3;
        b.walls(c1, 1, c1, c2x, wallH, c2z, p.wallAlt());
        battlements(b, p, c1, c1, c2x, c2z, wallH + 1);
        int gate = w / 2;
        b.box(gate - 1, 1, c2z, gate + 1, 4, c2z, Material.AIR);
        b.box(gate - 2, 5, c2z, gate + 2, 5, c2z, p.trim());
        for (int[] c : new int[][]{{c1, c1}, {c2x - 4, c1}, {c1, c2z - 4}, {c2x - 4, c2z - 4}}) {
            b.walls(c[0], 1, c[1], c[0] + 4, towerH, c[1] + 4, p.wall());
            b.box(c[0] + 1, 1, c[1] + 1, c[0] + 3, towerH - 1, c[1] + 3, Material.AIR);
            b.box(c[0], towerH, c[1], c[0] + 4, towerH, c[1] + 4, p.roofBlock());
            battlements(b, p, c[0], c[1], c[0] + 4, c[1] + 4, towerH + 1);
            b.set(c[0] + 2, towerH + 1, c[1] + 2, p.light());
            s.top.add(new Slot(c[0] + 1, towerH + 1, c[1] + 1, BlockFace.SOUTH));
        }
        building(b, p, s, kx1, kz1, kx2, kz2, 0, keepH, p.wall());
        gableRoof(b, p, kx1, kz1, kx2, kz2, keepH + 1, p.wall());
        path(b, p, (kx1 + kx2) / 2, kz2 + 1);
        // Control pedestals inside the courtyard.
        for (int x = c1 + 6; x < c2x - 5; x += 6) {
            s.outdoor.add(new Slot(x, 1, kz2 + 3, BlockFace.SOUTH));
            s.outdoor.add(new Slot(x, 1, c1 + 6, BlockFace.NORTH));
        }
        return b;
    }

    private static TemplateBuilder temple(StructureType t, ThemePalette p, Slots s) {
        int w = t.blocksX();
        int d = t.blocksZ();
        int big = Math.min(w, d) >= 32 ? 1 : 0;
        int wallH = wallHeight(t);
        int px1 = 3, pz1 = 3, px2 = w - 4, pz2 = d - 4;
        int cx1 = px1 + 3 + big, cz1 = pz1 + 3 + big, cx2 = px2 - 3 - big, cz2 = pz2 - 4 - big;
        int roof = gableHeight(px1, pz1, px2, pz2);
        int sy = 3 + wallH + roof + 3;
        TemplateBuilder b = new TemplateBuilder(w, sy, d);
        plaza(b, p, s);
        b.box(2, 1, 2, w - 3, 1, d - 3, p.trim());
        b.box(px1, 2, pz1, px2, 2, pz2, p.foundation());
        building(b, p, s, cx1, cz1, cx2, cz2, 2, wallH, p.wallAlt());
        BlockData column = TemplateBuilder.axis(p.pillar(), Axis.Y);
        for (int x = px1; x <= px2; x += 2) {
            b.box(x, 3, pz1, x, 2 + wallH, pz1, column);
            b.box(x, 3, pz2, x, 2 + wallH, pz2, column);
        }
        for (int z = pz1; z <= pz2; z += 2) {
            b.box(px1, 3, z, px1, 2 + wallH, z, column);
            b.box(px2, 3, z, px2, 2 + wallH, z, column);
        }
        int top = 3 + wallH;
        for (int x = px1; x <= px2; x++) {
            b.set(x, top, pz1, p.trim());
            b.set(x, top, pz2, p.trim());
        }
        for (int z = pz1; z <= pz2; z++) {
            b.set(px1, top, z, p.trim());
            b.set(px2, top, z, p.trim());
        }
        gableRoof(b, p, px1, pz1, px2, pz2, top + 1, p.trim());
        int xm = w / 2;
        for (int x = xm - 2; x <= xm + 2; x++) {
            b.set(x, 1, d - 2, TemplateBuilder.stairs(p.roofStairs(), BlockFace.NORTH, false));
            b.set(x, 2, d - 3, TemplateBuilder.stairs(p.roofStairs(), BlockFace.NORTH, false));
        }
        b.box(xm - 1, 3, pz2, xm + 1, 5, pz2, Material.AIR);
        b.set(xm, 2 + wallH, pz2, p.accent());
        path(b, p, xm, d - 2);
        return b;
    }

    private static TemplateBuilder monument(StructureType t, ThemePalette p, Slots s) {
        int w = t.blocksX();
        int d = t.blocksZ();
        String id = t.id();
        if (id.contains("arena") || id.contains("amphithea") || id.contains("colosseum")) return arena(t, p, s);
        int obelisk = 8 + Math.min(t.chunksX(), t.chunksZ()) * 5;
        TemplateBuilder b = new TemplateBuilder(w, obelisk + 6, d);
        plaza(b, p, s);
        int cx = w / 2;
        int cz = d / 2;
        b.box(cx - 3, 1, cz - 3, cx + 3, 1, cz + 3, p.trim());
        b.box(cx - 2, 2, cz - 2, cx + 2, 2, cz + 2, p.foundation());
        b.box(cx - 1, 3, cz - 1, cx + 1, 4, cz + 1, p.wallAlt());
        b.box(cx, 5, cz, cx, 4 + obelisk, cz, TemplateBuilder.axis(p.pillar(), Axis.Y));
        b.set(cx, 5 + obelisk, cz, p.accent());
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            b.set(cx + f.getModX() * 2, 3, cz + f.getModZ() * 2, TemplateBuilder.stairs(p.roofStairs(), f.getOppositeFace(), false));
        }
        int r = Math.min(w, d) / 2 - 3;
        int columns = r >= 12 ? 16 : 8;
        for (int i = 0; i < columns; i++) {
            double a = 2 * Math.PI * i / columns;
            int x = cx + (int) Math.round(Math.cos(a) * r);
            int z = cz + (int) Math.round(Math.sin(a) * r);
            if (z >= d - 3 && Math.abs(x - cx) <= 2) continue;
            b.box(x, 1, z, x, 5, z, TemplateBuilder.axis(p.pillar(), Axis.Y));
            b.set(x, 6, z, TemplateBuilder.slab(p.roofSlab(), false));
            b.set(x, 0, z, p.trim());
        }
        garden(b, p, cx - r + 2, cz - r + 2, cx - 5, cz - 5);
        garden(b, p, cx + 5, cz - r + 2, cx + r - 2, cz - 5);
        path(b, p, cx, cz + 4);
        s.floor.add(new Slot(cx + 4, 1, cz + 4, BlockFace.SOUTH));
        s.floor.add(new Slot(cx - 4, 1, cz + 4, BlockFace.SOUTH));
        s.top.add(new Slot(cx - 3, 2, cz - 3, BlockFace.NORTH));
        s.top.add(new Slot(cx + 3, 2, cz + 3, BlockFace.SOUTH));
        s.wallHigh.add(new Slot(cx, 3, cz + 2, BlockFace.SOUTH));
        return b;
    }

    /** Flower beds / bushes on a small rect (only where the plaza is). */
    private static void garden(TemplateBuilder b, ThemePalette p, int x1, int z1, int x2, int z2) {
        if (x2 - x1 < 1 || z2 - z1 < 1) return;
        Material[] flowers = {Material.POPPY, Material.DANDELION, Material.AZURE_BLUET, Material.CORNFLOWER, Material.OXEYE_DAISY};
        boolean soil = p.ground() == Material.GRASS_BLOCK || p.ground() == Material.MOSS_BLOCK;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                if (!b.in(x, 0, z)) continue;
                b.set(x, 0, z, p.ground());
                int n = Math.floorMod(x * 31 + z * 17, 7);
                if (soil && n < flowers.length) b.set(x, 1, z, flowers[n]);
                else if (n == 6) b.set(x, 1, z, persistentLeaves());
            }
        }
    }

    private static BlockData persistentLeaves() {
        BlockData leaves = Material.AZALEA_LEAVES.createBlockData();
        if (leaves instanceof org.bukkit.block.data.type.Leaves l) l.setPersistent(true);
        return leaves;
    }

    private static TemplateBuilder arena(StructureType t, ThemePalette p, Slots s) {
        int w = t.blocksX();
        int d = t.blocksZ();
        int rows = 5;
        TemplateBuilder b = new TemplateBuilder(w, rows + 5, d);
        plaza(b, p, s);
        double cx = (w - 1) / 2.0;
        double cz = (d - 1) / 2.0;
        double rx = w / 2.0 - 2;
        double rz = d / 2.0 - 2;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                double e = Math.sqrt(Math.pow((x - cx) / rx, 2) + Math.pow((z - cz) / rz, 2));
                if (e > 1.0) continue;
                if (e < 0.5) {
                    b.set(x, 0, z, Material.SAND);
                    continue;
                }
                int row = (int) Math.min(rows, Math.floor((e - 0.5) / 0.5 * rows) + 1);
                b.box(x, 1, z, x, row, z, p.wallAlt());
                if (e > 0.93) {
                    boolean arch = Math.floorMod(x + z, 5) == 0;
                    b.box(x, 1, z, x, rows + 2, z, p.wall());
                    if (arch) b.box(x, 1, z, x, 3, z, Material.AIR);
                    b.set(x, rows + 3, z, p.trim());
                }
            }
        }
        int gx = w / 2;
        for (int z = d - 3; z > (int) (cz + rz * 0.5); z--) b.box(gx - 1, 1, z, gx + 1, 4, z, Material.AIR);
        path(b, p, gx, (int) (cz + rz * 0.5));
        s.floor.add(new Slot(gx, 1, (int) cz, BlockFace.SOUTH));
        s.floor.add(new Slot(gx + 2, 1, (int) cz, BlockFace.SOUTH));
        s.wallHigh.add(new Slot(gx + 3, 1, (int) (cz + rz * 0.45), BlockFace.NORTH));
        return b;
    }

    private static TemplateBuilder market(StructureType t, ThemePalette p, Slots s) {
        int w = t.blocksX();
        int d = t.blocksZ();
        TemplateBuilder b = new TemplateBuilder(w, 14, d);
        plaza(b, p, s);
        int cx = w / 2;
        int cz = d / 2;
        if (d >= 48) {
            // Shopping centre: covered hall in the back half, stalls in the front.
            int hz2 = d / 2 - 2;
            building(b, p, s, 3, 3, w - 4, hz2, 0, 7, p.wall());
            flatRoof(b, p, 3, 3, w - 4, hz2, 8);
            cz = (hz2 + d) / 2;
        }
        b.box(cx - 2, 1, cz - 2, cx + 2, 1, cz + 2, p.trim());
        b.box(cx - 1, 1, cz - 1, cx + 1, 1, cz + 1, Material.WATER);
        b.box(cx, 1, cz, cx, 3, cz, TemplateBuilder.axis(p.pillar(), Axis.Y));
        b.set(cx, 4, cz, p.light());
        Material[] awnings = {p.wool(), Material.WHITE_WOOL};
        int ring = Math.min(w, d) / 2 - 5;
        int i = 0;
        for (int dx = -ring; dx <= ring; dx += 5) {
            for (int dz : new int[]{-ring, ring}) {
                int x = cx + dx;
                int z = Math.max(4, Math.min(d - 5, cz + dz));
                if (dz > 0 && Math.abs(dx) <= 2) continue;
                stall(b, p, x, z, awnings[i++ % 2], dz > 0 ? BlockFace.NORTH : BlockFace.SOUTH, s);
            }
        }
        path(b, p, cx, cz + 3);
        s.wallHigh.add(new Slot(cx, 2, cz + 3, BlockFace.SOUTH));
        s.floor.add(new Slot(cx + 3, 1, cz, BlockFace.SOUTH));
        return b;
    }

    private static void stall(TemplateBuilder b, ThemePalette p, int x, int z, Material awning, BlockFace front, Slots s) {
        for (int[] c : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) b.box(x + c[0], 1, z + c[1], x + c[0], 2, z + c[1], p.fence());
        b.box(x - 1, 3, z - 1, x + 1, 3, z + 1, awning);
        int fz = z + front.getModZ();
        b.set(x, 1, fz, Material.BARREL);
        s.wall.add(new Slot(x, 1, z, front));
        s.wallHigh.add(new Slot(x, 2, z, front));
    }

    private static TemplateBuilder ship(StructureType t, ThemePalette p, Slots s) {
        int w = t.blocksX();
        int d = t.blocksZ();
        boolean water = t.water().isWater();
        int sea = Math.max(0, -t.yShift());
        int deck = water ? sea + 1 : 6;
        int mastH = 11;
        boolean airship = !water;
        int sy = deck + mastH + (airship ? 8 : 3);
        TemplateBuilder b = new TemplateBuilder(w, sy, d);
        boolean alongX = w > d;
        int len = (alongX ? w : d) - 4;
        int beam = Math.min((alongX ? d : w) - 4, 11) | 1;
        int half = beam / 2;
        int centreAcross = (alongX ? d : w) / 2;
        if (water) b.box(0, 0, 0, w - 1, sea, d - 1, Material.WATER);
        else s.center = null;
        BlockData hull = p.hull().createBlockData();
        BlockData deckData = p.deck().createBlockData();
        for (int u = 0; u < len; u++) {
            int taper = Math.min(u, len - 1 - u);
            int hw = Math.max(1, Math.min(half, taper < 4 ? taper + 1 : half));
            for (int layer = 0; layer <= 3; layer++) {
                int y = deck - 3 + layer;
                if (y < 0) continue;
                int lw = Math.max(0, hw - (3 - layer));
                for (int v = -lw; v <= lw; v++) {
                    int[] xz = shipCell(alongX, u + 2, centreAcross + v);
                    boolean edge = Math.abs(v) == lw || layer == 0;
                    b.set(xz[0], y, xz[1], layer == 3 && !edge ? deckData : hull);
                }
            }
            for (int v : new int[]{-hw, hw}) {
                int[] xz = shipCell(alongX, u + 2, centreAcross + v);
                b.set(xz[0], deck + 1, xz[1], p.fence());
            }
        }
        // Masts with sails (or a balloon for the airship).
        BlockData mast = TemplateBuilder.axis(p.log(), Axis.Y);
        int[] masts = len > 20 ? new int[]{len / 3 + 2, 2 * len / 3 + 2} : new int[]{len / 2 + 2};
        for (int m : masts) {
            int[] c = shipCell(alongX, m, centreAcross);
            b.box(c[0], deck + 1, c[1], c[0], deck + mastH, c[1], mast);
            if (!airship) {
                for (int y = deck + 4; y <= deck + mastH - 1; y++) {
                    for (int v = -half + 1; v <= half - 1; v++) {
                        int[] sc = shipCell(alongX, m + 1, centreAcross + v);
                        b.set(sc[0], y, sc[1], p.wool());
                    }
                }
                s.top.add(new Slot(c[0], deck + mastH + 1, c[1], BlockFace.SOUTH));
            }
        }
        if (airship) {
            int[] c = shipCell(alongX, len / 2 + 2, centreAcross);
            int by = deck + mastH + 3;
            int ra = len / 2;
            int rb = Math.max(3, half + 1);
            for (int du = -ra; du <= ra; du++) {
                for (int dv = -rb; dv <= rb; dv++) {
                    for (int dy = -4; dy <= 4; dy++) {
                        double e = (du * du) / (double) (ra * ra) + (dv * dv) / (double) (rb * rb) + (dy * dy) / 16.0;
                        if (e > 1.0 || e < 0.6) continue;
                        int[] xz = shipCell(alongX, len / 2 + 2 + du, centreAcross + dv);
                        b.set(xz[0], by + dy, xz[1], p.wool());
                    }
                }
            }
            s.top.add(new Slot(c[0], deck + 1, c[1] + 1, BlockFace.SOUTH));
        }
        // Stern cabin.
        int[] a = shipCell(alongX, 3, centreAcross - half + 1);
        int[] z = shipCell(alongX, 7, centreAcross + half - 1);
        int ax = Math.min(a[0], z[0]), bx = Math.max(a[0], z[0]), az = Math.min(a[1], z[1]), bz = Math.max(a[1], z[1]);
        b.walls(ax, deck + 1, az, bx, deck + 3, bz, p.wall());
        b.box(ax, deck + 4, az, bx, deck + 4, bz, TemplateBuilder.slab(p.roofSlab(), false));
        int[] door = shipCell(alongX, 7, centreAcross);
        b.set(door[0], deck + 1, door[1], Material.AIR);
        b.set(door[0], deck + 2, door[1], Material.AIR);
        int[] lamp = shipCell(alongX, len + 1, centreAcross);
        b.set(lamp[0], deck + 1, lamp[1], p.light());
        for (int u = 9; u < len - 2; u += 3) {
            int[] c = shipCell(alongX, u, centreAcross - half + 2);
            s.wall.add(new Slot(c[0], deck + 1, c[1], alongX ? BlockFace.SOUTH : BlockFace.EAST));
            int[] f = shipCell(alongX, u, centreAcross);
            s.floor.add(new Slot(f[0], deck + 1, f[1], BlockFace.SOUTH));
        }
        for (int u = 9; u < len - 2; u += 5) {
            int[] c = shipCell(alongX, u, centreAcross + half - 1);
            s.outdoor.add(new Slot(c[0], deck + 1, c[1], alongX ? BlockFace.NORTH : BlockFace.WEST));
        }
        s.center = new Slot(shipCell(alongX, len / 2 + 2, centreAcross)[0], deck + 1,
                shipCell(alongX, len / 2 + 2, centreAcross)[1], BlockFace.SOUTH);
        return b;
    }

    private static int[] shipCell(boolean alongX, int u, int v) {
        return alongX ? new int[]{u, v} : new int[]{v, u};
    }

    private static TemplateBuilder field(StructureType t, ThemePalette p, Slots s) {
        int w = t.blocksX();
        int d = t.blocksZ();
        TemplateBuilder b = new TemplateBuilder(w, 11, d);
        plaza(b, p, s);
        b.box(1, 0, 1, w - 2, 0, d - 2, p.ground());
        for (int x = 1; x < w - 1; x++) {
            b.set(x, 1, 1, p.fence());
            b.set(x, 1, d - 2, p.fence());
        }
        for (int z = 1; z < d - 1; z++) {
            b.set(1, 1, z, p.fence());
            b.set(w - 2, 1, z, p.fence());
        }
        int xm = w / 2;
        b.set(xm, 1, d - 2, Material.AIR);
        b.set(xm - 1, 1, d - 2, Material.AIR);
        // Shed at the back, the working area in front of it.
        int sx1 = 3, sz1 = 2, sx2 = Math.min(w - 4, 9), sz2 = 6;
        building(b, p, s, sx1, sz1, sx2, sz2, 0, 4, p.wall());
        gableRoof(b, p, sx1, sz1, sx2, sz2, 5, p.wall());
        String id = t.id();
        int fx1 = 3, fz1 = sz2 + 3, fx2 = w - 4, fz2 = d - 4;
        if (id.contains("fish")) {
            for (int px = fx1; px + 3 <= fx2; px += 5) {
                b.box(px, 0, fz1, px + 3, 0, fz2, p.trim());
                b.box(px + 1, 0, fz1 + 1, px + 2, 0, fz2 - 1, Material.WATER);
            }
        } else if (id.contains("floating") || id.contains("rice")) {
            b.box(fx1, 0, fz1, fx2, 0, fz2, Material.WATER);
            for (int x = fx1; x <= fx2; x += 2) {
                for (int z = fz1; z <= fz2; z += 3) b.set(x, 1, z, Material.LILY_PAD);
            }
            for (int x = fx1 + 1; x <= fx2; x += 5) {
                b.set(x, 0, fz1 + 2, Material.MOSS_BLOCK);
                b.set(x, 1, fz1 + 2, Material.FLOWERING_AZALEA);
            }
        } else if (id.contains("api") || id.contains("hive")) {
            for (int x = fx1; x <= fx2; x += 3) {
                for (int z = fz1; z <= fz2; z += 3) {
                    b.set(x, 1, z, p.fence());
                    b.set(x, 2, z, TemplateBuilder.facing(Material.BEEHIVE, BlockFace.SOUTH));
                    b.set(x + 1, 1, z, Material.CORNFLOWER);
                    b.set(x, 1, z + 1, Material.OXEYE_DAISY);
                }
            }
        } else {
            for (int x = fx1; x <= fx2; x += 4) {
                for (int z = fz1; z <= fz2; z++) b.set(x, 1, z, persistentLeaves());
            }
            for (int x = fx1 + 2; x <= fx2; x += 4) {
                for (int z = fz1; z <= fz2; z += 4) b.set(x, 1, z, Material.HAY_BLOCK);
            }
        }
        return b;
    }

    private static TemplateBuilder lighthouse(StructureType t, ThemePalette p, Slots s) {
        int w = t.blocksX();
        int d = t.blocksZ();
        boolean water = t.water().isWater();
        int sea = Math.max(0, -t.yShift());
        int base = water ? sea + 1 : 1;
        int r = Math.min(w, d) >= 32 ? 5 : 3;
        int h = Math.min(w, d) >= 32 ? 40 : 26;
        TemplateBuilder b = new TemplateBuilder(w, base + h + 8, d);
        int cx = w / 2;
        int cz = d / 2;
        int island = Math.min(w, d) / 2 - 2;
        if (water) {
            b.box(0, 0, 0, w - 1, sea, d - 1, Material.WATER);
            disk(b, cx, cz, island, 0, base - 1, p.foundation().createBlockData());
            ring(b, cx, cz, island, base - 1, p.trim().createBlockData());
        } else {
            plaza(b, p, s);
        }
        for (int y = base; y < base + h; y++) {
            Material m = ((y - base) / 4) % 2 == 0 ? p.wall() : p.trim();
            ring(b, cx, cz, r, y, m.createBlockData());
        }
        disk(b, cx, cz, r, base + h, base + h, p.roofBlock().createBlockData());
        for (int y = base + h + 1; y <= base + h + 3; y++) ring(b, cx, cz, r, y, p.glass().createBlockData());
        b.box(cx, base + h + 1, cz, cx, base + h + 2, cz, Material.SEA_LANTERN);
        for (int k = 0; k <= r; k++) disk(b, cx, cz, r - k, base + h + 4 + k / 2, base + h + 4 + k / 2, p.roofBlock().createBlockData());
        b.set(cx, base + h + 5 + r / 2, cz, p.accent());
        b.set(cx, base, cz + r, TemplateBuilder.door(p.door(), BlockFace.NORTH, false));
        b.set(cx, base + 1, cz + r, TemplateBuilder.door(p.door(), BlockFace.NORTH, true));
        BlockData ladder = Material.LADDER.createBlockData();
        if (ladder instanceof Ladder l) l.setFacing(BlockFace.SOUTH);
        for (int y = base; y < base + h; y++) b.set(cx, y, cz - r + 1, ladder);
        b.set(cx, base + h, cz - r + 1, ladder);
        s.floor.add(new Slot(cx + 1, base, cz, BlockFace.SOUTH));
        s.wall.add(new Slot(cx + r - 1, base, cz, BlockFace.WEST));
        s.wallHigh.add(new Slot(cx - r + 1, base + 1, cz, BlockFace.EAST));
        for (int[] c : new int[][]{{-island + 2, -island + 2}, {island - 2, -island + 2}, {-island + 2, island - 2}, {island - 2, island - 2}}) {
            s.outdoor.addFirst(new Slot(cx + c[0], base, cz + c[1], BlockFace.SOUTH));
        }
        s.top.add(new Slot(cx + 1, base + h + 1, cz + 1, BlockFace.SOUTH));
        s.top.add(new Slot(cx - 1, base + h + 1, cz - 1, BlockFace.NORTH));
        s.center = new Slot(cx + 2, base, cz + r + 2, BlockFace.SOUTH);
        return b;
    }

    private static void disk(TemplateBuilder b, int cx, int cz, int r, int y1, int y2, BlockData data) {
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z <= r * r + r) b.box(cx + x, y1, cz + z, cx + x, y2, cz + z, data);
            }
        }
    }

    private static void ring(TemplateBuilder b, int cx, int cz, int r, int y, BlockData data) {
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int dd = x * x + z * z;
                if (dd <= r * r + r && dd > (r - 1) * (r - 1) + (r - 1)) b.set(cx + x, y, cz + z, data);
            }
        }
    }

    private static TemplateBuilder wonder(StructureType t, ThemePalette p, Slots s) {
        int w = t.blocksX();
        int d = t.blocksZ();
        int steps = 3;
        int inset = Math.max(8, Math.min(w, d) / 5);
        int hx1 = inset, hz1 = inset, hx2 = w - 1 - inset, hz2 = d - 1 - inset - 2;
        int wallH = 12;
        int r = Math.max(4, Math.min(hx2 - hx1, hz2 - hz1) / 2 - 1);
        int sy = steps + wallH + r + 10;
        TemplateBuilder b = new TemplateBuilder(w, sy, d);
        plaza(b, p, s);
        for (int i = 1; i <= steps; i++) {
            int m = 1 + i * 2;
            b.box(m, i, m, w - 1 - m, i, d - 1 - m, i % 2 == 1 ? p.trim() : p.foundation());
        }
        building(b, p, s, hx1, hz1, hx2, hz2, steps, wallH, p.wallAlt());
        b.box(hx1, steps + wallH + 1, hz1, hx2, steps + wallH + 1, hz2, p.trim());
        // Front colonnade.
        BlockData column = TemplateBuilder.axis(p.pillar(), Axis.Y);
        for (int x = hx1; x <= hx2; x += 3) {
            b.box(x, steps + 1, hz2 + 2, x, steps + wallH, hz2 + 2, column);
            b.set(x, steps + wallH + 1, hz2 + 2, p.trim());
            b.set(x, steps + wallH + 1, hz2 + 1, p.trim());
        }
        // Dome.
        int cx = (hx1 + hx2) / 2;
        int cz = (hz1 + hz2) / 2;
        int domeBase = steps + wallH + 2;
        for (int dy = 0; dy <= r; dy++) {
            int rr = (int) Math.round(Math.sqrt(Math.max(0, r * r - dy * dy)));
            Material m = dy == 2 ? p.glass() : p.roofBlock();
            ring(b, cx, cz, Math.max(0, rr), domeBase + dy, m.createBlockData());
            if (rr <= 1) b.set(cx, domeBase + dy, cz, p.roofBlock());
        }
        b.box(cx, domeBase + r + 1, cz, cx, domeBase + r + 5, cz, column);
        b.set(cx, domeBase + r + 6, cz, p.accent());
        // Obelisks on the platform corners.
        int o = 1 + steps * 2 + 1;
        for (int[] c : new int[][]{{o, o}, {w - 1 - o, o}, {o, d - 1 - o}, {w - 1 - o, d - 1 - o}}) {
            b.box(c[0], steps + 1, c[1], c[0], steps + 9, c[1], column);
            b.set(c[0], steps + 10, c[1], p.accent());
            s.top.add(new Slot(c[0], steps + 11, c[1], BlockFace.SOUTH));
        }
        // Reflecting pools in the front yard.
        int poolZ1 = hz2 + 4;
        int poolZ2 = d - 1 - (1 + steps * 2) - 2;
        if (poolZ2 - poolZ1 >= 2) {
            b.box(hx1, steps, poolZ1, cx - 3, steps, poolZ2, p.trim());
            b.box(hx1 + 1, steps, poolZ1 + 1, cx - 4, steps, poolZ2 - 1, Material.WATER);
            b.box(cx + 3, steps, poolZ1, hx2, steps, poolZ2, p.trim());
            b.box(cx + 4, steps, poolZ1 + 1, hx2 - 1, steps, poolZ2 - 1, Material.WATER);
        }
        for (int i = 1; i <= steps; i++) {
            int z = d - 2 - i * 2;
            for (int x = cx - 2; x <= cx + 2; x++) {
                b.set(x, i, z + 1, TemplateBuilder.stairs(p.roofStairs(), BlockFace.NORTH, false));
            }
        }
        path(b, p, cx, d - 3);
        s.outdoor.clear();
        for (int x = hx1; x <= hx2; x += 6) {
            s.outdoor.add(new Slot(x, steps + 1, hz2 + 4, BlockFace.SOUTH));
            s.outdoor.add(new Slot(x, steps + 1, hz1 - 2, BlockFace.NORTH));
        }
        return b;
    }

    // --- markers ----------------------------------------------------------------------------------------------------

    private static void placeMarkers(TemplateBuilder b, ThemePalette p, List<MarkerSpec> markers, Slots s) {
        for (MarkerSpec m : markers) {
            Slot slot = switch (m.type()) {
                case "chest", "barrel", "furnace" -> take(b, s.wall, s.floor, s.outdoor);
                case "sign", "itemframe", "techbar", "techname", "techdata", "gui" -> take(b, s.wallHigh, s.wall, s.floor);
                case "control" -> take(b, s.outdoor, s.floor, s.wall);
                case "towerfire" -> take(b, s.top, s.outdoor, s.floor);
                case "tradeoutpost" -> s.center != null && b.isAir(s.center.x(), s.center.y(), s.center.z())
                        ? s.center : take(b, s.outdoor, s.floor);
                default -> take(b, s.floor, s.outdoor, s.wall);
            };
            if (slot == null) slot = freeCell(b);
            if (slot == null) continue;
            if (m.type().equals("control")) {
                b.set(slot.x(), slot.y(), slot.z(), p.trim());
                b.marker(slot.x(), slot.y() + 1, slot.z(), m.type(), m.args(), slot.facing());
            } else {
                b.marker(slot.x(), slot.y(), slot.z(), m.type(), m.args(), slot.facing());
            }
        }
    }

    @SafeVarargs
    private static Slot take(TemplateBuilder b, Deque<Slot>... sources) {
        for (Deque<Slot> source : sources) {
            while (!source.isEmpty()) {
                Slot slot = source.poll();
                if (b.in(slot.x(), slot.y() + 1, slot.z()) && b.isAir(slot.x(), slot.y(), slot.z())
                        && !b.hasMarkerAt(slot.x(), slot.y(), slot.z()) && !b.hasMarkerAt(slot.x(), slot.y() + 1, slot.z())) {
                    return slot;
                }
            }
        }
        return null;
    }

    /** Any air cell standing on a solid block (last resort for templates with many markers). */
    private static Slot freeCell(TemplateBuilder b) {
        for (int y = 1; y < b.sizeY() - 1; y++) {
            for (int z = 1; z < b.sizeZ() - 1; z++) {
                for (int x = 1; x < b.sizeX() - 1; x++) {
                    if (b.isAir(x, y, z) && b.isAir(x, y + 1, z) && !b.isAir(x, y - 1, z) && !b.hasMarkerAt(x, y, z)) {
                        return new Slot(x, y, z, BlockFace.SOUTH);
                    }
                }
            }
        }
        return null;
    }
}
