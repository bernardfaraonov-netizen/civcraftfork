package com.civcraft.integration;

import com.civcraft.core.util.ChunkKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts a set of chunks into outline polygons in block coordinates: outer rings (counter-clockwise)
 * with the holes they contain. Used to draw culture borders on web maps with one shape per region
 * instead of one marker per chunk.
 */
public final class Outline {

    public record Ring(List<long[]> points) {
        double signedArea() {
            double a = 0;
            for (int i = 0; i < points.size(); i++) {
                long[] p = points.get(i);
                long[] q = points.get((i + 1) % points.size());
                a += (double) p[0] * q[1] - (double) q[0] * p[1];
            }
            return a / 2;
        }

        boolean contains(double x, double z) {
            boolean inside = false;
            for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
                long[] pi = points.get(i);
                long[] pj = points.get(j);
                if ((pi[1] > z) != (pj[1] > z) && x < (double) (pj[0] - pi[0]) * (z - pi[1]) / (pj[1] - pi[1]) + pi[0]) {
                    inside = !inside;
                }
            }
            return inside;
        }
    }

    public record Polygon(Ring outer, List<Ring> holes) {
    }

    private Outline() {
    }

    public static List<Polygon> of(Collection<ChunkKey> chunks) {
        Set<Long> cells = new HashSet<>();
        for (ChunkKey c : chunks) cells.add(pack(c.x(), c.z()));
        // Directed boundary edges, keyed by start vertex.
        Map<Long, List<Long>> edges = new HashMap<>();
        for (ChunkKey c : chunks) {
            int x = c.x();
            int z = c.z();
            if (!cells.contains(pack(x, z - 1))) addEdge(edges, x, z, x + 1, z);
            if (!cells.contains(pack(x + 1, z))) addEdge(edges, x + 1, z, x + 1, z + 1);
            if (!cells.contains(pack(x, z + 1))) addEdge(edges, x + 1, z + 1, x, z + 1);
            if (!cells.contains(pack(x - 1, z))) addEdge(edges, x, z + 1, x, z);
        }
        List<Ring> rings = new ArrayList<>();
        while (!edges.isEmpty()) {
            long start = edges.keySet().iterator().next();
            List<long[]> points = new ArrayList<>();
            long current = start;
            do {
                List<Long> out = edges.get(current);
                long next = out.removeLast();
                if (out.isEmpty()) edges.remove(current);
                points.add(new long[]{unpackX(current) * 16L, unpackZ(current) * 16L});
                current = next;
            } while (current != start && edges.containsKey(current));
            rings.add(new Ring(simplify(points)));
        }
        List<Polygon> polygons = new ArrayList<>();
        List<Ring> holes = new ArrayList<>();
        for (Ring r : rings) {
            if (r.signedArea() > 0) polygons.add(new Polygon(r, new ArrayList<>()));
            else holes.add(r);
        }
        for (Ring hole : holes) {
            long[] p = hole.points().getFirst();
            for (Polygon poly : polygons) {
                if (poly.outer().contains(p[0] + 0.5, p[1] + 0.5)) {
                    poly.holes().add(hole);
                    break;
                }
            }
        }
        return polygons;
    }

    /** Removes collinear points. */
    private static List<long[]> simplify(List<long[]> points) {
        List<long[]> result = new ArrayList<>();
        int n = points.size();
        for (int i = 0; i < n; i++) {
            long[] prev = points.get((i - 1 + n) % n);
            long[] cur = points.get(i);
            long[] next = points.get((i + 1) % n);
            long cross = (cur[0] - prev[0]) * (next[1] - cur[1]) - (cur[1] - prev[1]) * (next[0] - cur[0]);
            if (cross != 0) result.add(cur);
        }
        return result.isEmpty() ? points : result;
    }

    private static void addEdge(Map<Long, List<Long>> edges, int x1, int z1, int x2, int z2) {
        edges.computeIfAbsent(pack(x1, z1), k -> new ArrayList<>()).add(pack(x2, z2));
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int unpackX(long v) {
        return (int) (v >> 32);
    }

    private static int unpackZ(long v) {
        return (int) v;
    }
}
