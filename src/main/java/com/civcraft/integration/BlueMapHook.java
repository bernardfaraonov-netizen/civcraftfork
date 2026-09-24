package com.civcraft.integration;

import com.civcraft.CivCraft;
import com.flowpowered.math.vector.Vector2d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Draws town culture areas and town hall markers on BlueMap. Loaded only when BlueMap is installed. */
final class BlueMapHook {

    private static final String SET_ID = "civcraft-towns";
    private final CivCraft civ;
    private final Consumer<BlueMapAPI> onEnable;

    BlueMapHook(CivCraft civ) {
        this.civ = civ;
        // BlueMap calls this from its own thread; game state is only read on the main thread.
        this.onEnable = api -> civ.tasks().sync(this::refresh);
        BlueMapAPI.onEnable(onEnable);
    }

    void disable() {
        BlueMapAPI.unregisterListener(onEnable);
    }

    /** Must be called on the main thread; BlueMap marker sets are thread-safe to update afterwards. */
    void refresh() {
        BlueMapAPI api = BlueMapAPI.getInstance().orElse(null);
        if (api == null) return;
        List<MapLayer.TownArea> areas = MapLayer.collect(civ);
        for (var world : civ.plugin().getServer().getWorlds()) {
            api.getWorld(world).ifPresent(bmWorld -> {
                MarkerSet set = MarkerSet.builder().label("Города").toggleable(true).defaultHidden(false).build();
                for (MapLayer.TownArea area : areas) {
                    if (!area.world().equals(world.getName())) continue;
                    int i = 0;
                    for (Outline.Polygon poly : area.polygons()) {
                        ShapeMarker.Builder builder = ShapeMarker.builder()
                                .label(area.label())
                                .detail(area.detail())
                                .shape(shape(poly.outer()), 64)
                                .lineColor(new Color(area.color(), 1f))
                                .fillColor(new Color(area.color(), 0.25f))
                                .lineWidth(2)
                                .depthTestEnabled(false);
                        List<Shape> holes = new ArrayList<>();
                        for (Outline.Ring hole : poly.holes()) holes.add(shape(hole));
                        if (!holes.isEmpty()) builder.holes(holes.toArray(Shape[]::new));
                        set.getMarkers().put("town-" + area.town().id() + "-" + i++, builder.build());
                    }
                    var c = area.town().center();
                    set.getMarkers().put("hall-" + area.town().id(), POIMarker.builder()
                            .label(area.town().name())
                            .detail(area.detail())
                            .position(c.x() + 0.5, c.y(), c.z() + 0.5)
                            .build());
                }
                for (var map : bmWorld.getMaps()) map.getMarkerSets().put(SET_ID, set);
            });
        }
    }

    private static Shape shape(Outline.Ring ring) {
        List<Vector2d> points = new ArrayList<>(ring.points().size());
        for (long[] p : ring.points()) points.add(new Vector2d(p[0], p[1]));
        return new Shape(points);
    }
}
