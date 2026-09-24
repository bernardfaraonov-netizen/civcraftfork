package com.civcraft.item.recipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;

/**
 * Exact matcher for CivCraft workbench recipes on a crafting grid, by custom id rather than by base
 * material. The server picks the first recipe whose ingredients fit by material, which may be a vanilla
 * recipe or another CivCraft recipe when custom items share a base material (steel blades on the iron
 * sword shape); this matcher finds the recipe the grid really is.
 */
public final class RecipeMatcher {

    /** One grid cell: base material and custom id (null for vanilla items). */
    public record Cell(Material material, String customId) {
    }

    private RecipeMatcher() {
    }

    /**
     * @param grid  cells row by row, null for empty; length 4 (2×2) or 9 (3×3)
     */
    public static boolean matches(RecipeDef def, Cell[] grid) {
        if (def.station() != RecipeDef.Station.WORKBENCH) return false;
        return def.shaped() ? matchesShaped(def, grid) : matchesShapeless(def, grid);
    }

    static boolean accepts(Ingredient ingredient, Cell cell) {
        if (cell == null) return false;
        return switch (ingredient) {
            case Ingredient.Custom c -> c.id().equals(cell.customId());
            case Ingredient.Vanilla v -> cell.customId() == null && v.accepts(cell.material());
        };
    }

    // ------------------------------------------------------------------------------------ shaped

    /** The recipe's shape without all-blank rows and columns (Minecraft shrinks patterns the same way). */
    static List<String> trimmed(List<String> shape) {
        int top = 0;
        int bottom = shape.size() - 1;
        while (top <= bottom && shape.get(top).isBlank()) top++;
        while (bottom >= top && shape.get(bottom).isBlank()) bottom--;
        if (top > bottom) return List.of();
        int width = shape.getFirst().length();
        int left = width;
        int right = -1;
        for (int r = top; r <= bottom; r++) {
            String row = shape.get(r);
            for (int c = 0; c < width; c++) {
                if (row.charAt(c) != ' ') {
                    left = Math.min(left, c);
                    right = Math.max(right, c);
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (int r = top; r <= bottom; r++) out.add(shape.get(r).substring(left, right + 1));
        return out;
    }

    private static boolean matchesShaped(RecipeDef def, Cell[] grid) {
        int size = grid.length == 4 ? 2 : 3;
        List<String> shape = trimmed(def.shape());
        if (shape.isEmpty()) return false;
        int h = shape.size();
        int w = shape.getFirst().length();
        if (h > size || w > size) return false;
        for (int dy = 0; dy + h <= size; dy++) {
            for (int dx = 0; dx + w <= size; dx++) {
                if (fits(def, shape, grid, size, dx, dy, false) || fits(def, shape, grid, size, dx, dy, true)) return true;
            }
        }
        return false;
    }

    private static boolean fits(RecipeDef def, List<String> shape, Cell[] grid, int size, int dx, int dy, boolean mirror) {
        int h = shape.size();
        int w = shape.getFirst().length();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                Cell cell = grid[y * size + x];
                int sx = x - dx;
                int sy = y - dy;
                char ch = ' ';
                if (sx >= 0 && sx < w && sy >= 0 && sy < h) ch = shape.get(sy).charAt(mirror ? w - 1 - sx : sx);
                if (ch == ' ') {
                    if (cell != null) return false;
                } else if (!accepts(def.keys().get(ch), cell)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------ shapeless

    private static boolean matchesShapeless(RecipeDef def, Cell[] grid) {
        List<Cell> cells = new ArrayList<>();
        for (Cell c : grid) if (c != null) cells.add(c);
        if (cells.size() != def.slotCount()) return false;
        Map<Ingredient, Integer> remaining = new HashMap<>(def.totals());
        List<Cell> vanilla = new ArrayList<>();
        for (Cell cell : cells) {
            if (cell.customId() == null) {
                vanilla.add(cell);
                continue;
            }
            Ingredient key = new Ingredient.Custom(cell.customId());
            Integer left = remaining.get(key);
            if (left == null || left == 0) return false;
            remaining.put(key, left - 1);
        }
        // Vanilla cells: give each to the most specific vanilla ingredient that still needs one.
        for (Cell cell : vanilla) {
            Ingredient best = null;
            int bestSize = Integer.MAX_VALUE;
            for (Map.Entry<Ingredient, Integer> e : remaining.entrySet()) {
                if (e.getValue() > 0 && e.getKey() instanceof Ingredient.Vanilla v && v.accepts(cell.material())
                        && v.materials().size() < bestSize) {
                    best = v;
                    bestSize = v.materials().size();
                }
            }
            if (best == null) return false;
            remaining.merge(best, -1, Integer::sum);
        }
        for (int left : remaining.values()) if (left != 0) return false;
        return true;
    }
}
