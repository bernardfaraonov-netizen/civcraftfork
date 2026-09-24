package com.civcraft.integration;

import com.civcraft.CivCraft;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import java.util.ArrayList;
import java.util.List;

/** Data shared by the web-map integrations: one entry per town with its culture outline. */
final class MapLayer {

    record TownArea(Town town, Civilization civ, String world, List<Outline.Polygon> polygons, int color, String label,
                    String detail) {
    }

    private MapLayer() {
    }

    static List<TownArea> collect(CivCraft civ) {
        List<TownArea> result = new ArrayList<>();
        for (Town town : civ.state().towns()) {
            if (town.center() == null) continue;
            var chunks = civ.culture().chunks(town);
            if (chunks.isEmpty()) continue;
            String world = chunks.iterator().next().world();
            List<ChunkKey> sameWorld = chunks.stream().filter(c -> c.world().equals(world)).toList();
            Civilization c = civ.state().civ(town.civId());
            String civName = c == null ? "—" : c.name();
            String label = town.name() + " (" + civName + ")";
            String detail = "<b>" + escape(town.name()) + "</b><br>" + escape(civName)
                    + (c != null && c.tag() != null ? " [" + escape(c.tag()) + "]" : "")
                    + "<br>Жителей: " + town.residents().size()
                    + "<br>Культура: " + (long) town.culture();
            result.add(new TownArea(town, c, world, Outline.of(sameWorld), color(c, town), label, detail));
        }
        return result;
    }

    static int color(Civilization civ, Town town) {
        if (civ != null && civ.cultureColor() != null) {
            try {
                return Integer.parseInt(civ.cultureColor().replace("#", ""), 16);
            } catch (NumberFormatException ignored) {
                // fall through to the hashed colour
            }
        }
        int hash = (civ != null ? civ.id() : town.id()).hashCode();
        float hue = (hash & 0xFFFF) / 65535f;
        return java.awt.Color.HSBtoRGB(hue, 0.65f, 0.9f) & 0xFFFFFF;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
