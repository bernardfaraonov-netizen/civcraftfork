package com.civcraft.integration;

import com.civcraft.CivCraft;
import java.util.HashSet;
import java.util.Set;
import org.dynmap.DynmapCommonAPI;
import org.dynmap.DynmapCommonAPIListener;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.Marker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

/** Draws town culture areas on Dynmap. Loaded only when Dynmap is installed. */
final class DynmapHook extends DynmapCommonAPIListener {

    private static final String SET_ID = "civcraft.towns";
    private final CivCraft civ;
    private MarkerAPI markers;

    DynmapHook(CivCraft civ) {
        this.civ = civ;
        DynmapCommonAPIListener.register(this);
    }

    @Override
    public void apiEnabled(DynmapCommonAPI api) {
        this.markers = api.getMarkerAPI();
        civ.tasks().sync(this::refresh);
    }

    void disable() {
        DynmapCommonAPIListener.unregister(this);
    }

    void refresh() {
        if (markers == null) return;
        MarkerSet set = markers.getMarkerSet(SET_ID);
        if (set == null) set = markers.createMarkerSet(SET_ID, "Города", null, false);
        if (set == null) return;
        Set<String> keep = new HashSet<>();
        for (MapLayer.TownArea area : MapLayer.collect(civ)) {
            int i = 0;
            for (Outline.Polygon poly : area.polygons()) {
                String id = "town-" + area.town().id() + "-" + i++;
                keep.add(id);
                double[] xs = new double[poly.outer().points().size()];
                double[] zs = new double[xs.length];
                for (int p = 0; p < xs.length; p++) {
                    xs[p] = poly.outer().points().get(p)[0];
                    zs[p] = poly.outer().points().get(p)[1];
                }
                AreaMarker m = set.findAreaMarker(id);
                if (m == null) m = set.createAreaMarker(id, area.label(), false, area.world(), xs, zs, false);
                else m.setCornerLocations(xs, zs);
                if (m == null) continue;
                m.setLabel(area.label());
                m.setDescription(area.detail());
                m.setLineStyle(2, 0.9, area.color());
                m.setFillStyle(0.25, area.color());
            }
            String hallId = "hall-" + area.town().id();
            keep.add(hallId);
            var c = area.town().center();
            Marker hall = set.findMarker(hallId);
            if (hall == null) {
                set.createMarker(hallId, area.town().name(), area.world(), c.x(), c.y(), c.z(),
                        markers.getMarkerIcon("tower"), false);
            } else {
                hall.setLocation(area.world(), c.x(), c.y(), c.z());
                hall.setLabel(area.town().name());
            }
        }
        for (AreaMarker m : Set.copyOf(set.getAreaMarkers())) if (!keep.contains(m.getMarkerID())) m.deleteMarker();
        for (Marker m : Set.copyOf(set.getMarkers())) if (!keep.contains(m.getMarkerID())) m.deleteMarker();
    }
}
