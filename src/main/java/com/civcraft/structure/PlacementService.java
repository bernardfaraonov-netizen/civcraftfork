package com.civcraft.structure;

import static com.civcraft.core.text.Messages.arg;
import static com.civcraft.core.text.Messages.money;

import com.civcraft.CivCraft;
import com.civcraft.core.CivException;
import com.civcraft.core.task.Tasks;
import com.civcraft.core.text.Format;
import com.civcraft.core.ui.Prompts;
import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.Durations;
import com.civcraft.model.Town;
import com.civcraft.structure.construction.BuildMath;
import com.civcraft.structure.template.StructureTemplates;
import com.civcraft.structure.type.StructureType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The /build flow (spec 02 §1.3): validate, show the footprint to the player only (block display outline and a beam
 * at the start point), confirm with a native dialog (or {@code /build yes}) within 12 blocks of the start point,
 * validate again and only then charge and start construction.
 */
public final class PlacementService implements Listener {

    /** A pending placement of one player. */
    private record Session(UUID player, String townId, StructureType type, StructureApi.Placement placement, String theme,
                           Location start, long expiresAt, List<Entity> displays) {
    }

    private final StructureModule module;
    private final CivCraft civ;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, List<Entity>> strayPreviews = new HashMap<>();

    PlacementService(StructureModule module, CivCraft civ) {
        this.module = module;
        this.civ = civ;
    }

    /**
     * Starts placing a type for the player's selected town. {@code absoluteY} (nullable) or {@code yOffset} set the
     * height; {@code theme} null = town default.
     */
    public void begin(Player player, StructureType type, Integer absoluteY, int yOffset, String theme, List<String> extraArgs)
            throws CivException {
        Tasks.checkMain();
        Town town = module.integrations().selectedTown(player);
        CivException.check(module.integrations().canManage(player, town), "structure.error.not-manager");
        if (type.category().isCustom()) {
            CustomPlacement flow = module.customPlacement(type);
            if (flow == null) throw new CivException("structure.error.custom-unavailable");
            CivException.check(!module.warActive(), "structure.error.war");
            flow.begin(player, town, type, extraArgs);
            return;
        }
        CivException.check(!type.warOnly(), "structure.error.war-only");
        String th = theme == null ? module.defaultTheme(town) : theme.toLowerCase(Locale.ROOT);
        CivException.check(module.settings().themeExists(th), "structure.error.unknown-theme", arg("theme", th));
        boolean needsPermission = civ.balance().file("structures").getBoolean("themes." + th + ".permission", false);
        CivException.check(!needsPermission || th.equals(module.defaultTheme(town)) || player.hasPermission("civcraft.theme." + th),
                "structure.error.theme-permission", arg("theme", module.themes().getOrDefault(th, th)));
        if (type.water().isWater()) {
            int sea = player.getWorld().getSeaLevel();
            CivException.check(Math.abs(player.getLocation().getBlockY() - sea) <= module.settings().waterYTolerance(),
                    "structure.error.water-level", arg("blocks", module.settings().waterYTolerance()));
        }
        module.validator().checkAvailable(town, type);
        StructureApi.Placement pl = module.computePlacement(player.getLocation(), type, th, absoluteY, yOffset);
        World world = player.getWorld();
        PlacementValidator.Site site = new PlacementValidator.Site(world, pl.origin(), pl.sizeX(), pl.sizeY(), pl.sizeZ());
        cancel(player);
        try {
            module.validator().checkSite(town, type, site, null);
            module.behavior(type.id()).checkBuild(town, player);
        } catch (CivException e) {
            List<Entity> red = preview(player, site, false);
            strayPreviews.put(player.getUniqueId(), red);
            civ.tasks().later(20L * 8, () -> clearStray(player.getUniqueId(), red));
            throw e;
        }
        boolean relocation = type.isMain() && module.mainBuilding(town) != null;
        long cost = module.validator().cost(town, type, relocation);
        if (town.treasury() < cost) throw new CivException("structure.error.money", money("cost", cost));
        double hammers = module.validator().hammers(town, type);
        List<Entity> displays = preview(player, site, true);
        long expires = System.currentTimeMillis() + module.settings().confirmSeconds() * 1000L;
        Session session = new Session(player.getUniqueId(), town.id(), type, pl, th, player.getLocation(), expires, displays);
        sessions.put(player.getUniqueId(), session);

        long eta = BuildMath.secondsRemaining(hammers, module.integrations().hammersPerHour(town), module.settings().speedMultiplier());
        String etaText = eta < 0 ? civ.messages().plain("structure.eta-never") : Durations.format(Duration.ofSeconds(eta));
        List<Component> body = new ArrayList<>();
        body.add(civ.messages().component("structure.confirm.cost", money("cost", cost), money("treasury", town.treasury())));
        body.add(civ.messages().component("structure.confirm.hammers", arg("hammers", Format.number(Math.ceil(hammers))), arg("eta", etaText)));
        body.add(civ.messages().component("structure.confirm.upkeep", money("upkeep", type.upkeep())));
        body.add(civ.messages().component("structure.confirm.size", arg("x", pl.sizeX()), arg("y", pl.sizeY()), arg("z", pl.sizeZ()),
                arg("theme", module.themes().getOrDefault(th, th))));
        body.add(civ.messages().component("structure.confirm.location", arg("coords", pl.origin().x() + ", " + pl.origin().y() + ", " + pl.origin().z())));
        if (relocation) body.add(civ.messages().component(type.id().equals(StructureModule.CAPITOL) && module.mainBuilding(town).type().equals(StructureModule.TOWN_HALL)
                ? "structure.confirm.replaces-hall" : "structure.confirm.relocation"));
        if (type.slot()) body.add(civ.messages().component("structure.confirm.slot", arg("used", module.slotsUsed(town)), arg("max", module.slots(town))));
        body.add(civ.messages().component("structure.confirm.hint", arg("radius", Format.number(module.settings().confirmRadius()))));
        Prompts.confirm(player, civ.messages().component("structure.confirm.title", arg("name", type.name())), body,
                civ.messages().component("structure.confirm.yes"), civ.messages().component("structure.confirm.no"),
                p -> {
                    try {
                        confirm(p);
                    } catch (CivException e) {
                        civ.messages().send(p, e.key(), e.args());
                    }
                });
        civ.messages().send(player, "structure.confirm.chat", arg("name", type.name()));
    }

    /** Confirms the pending placement: validates again, charges and starts construction. */
    public Structure confirm(Player player) throws CivException {
        Tasks.checkMain();
        Session s = sessions.remove(player.getUniqueId());
        if (s == null) throw new CivException("structure.error.no-session");
        clear(s.displays());
        CivException.check(System.currentTimeMillis() <= s.expiresAt(), "structure.error.session-expired");
        Location now = player.getLocation();
        CivException.check(now.getWorld().equals(s.start().getWorld())
                        && now.distanceSquared(s.start()) <= module.settings().confirmRadius() * module.settings().confirmRadius(),
                "structure.error.too-far", arg("radius", Format.number(module.settings().confirmRadius())));
        Town town = civ.state().town(s.townId());
        CivException.check(town != null, "error.not-in-town");
        CivException.check(module.integrations().canManage(player, town), "structure.error.not-manager");
        StructureTemplates.Resolved res = module.templates().resolve(s.type(), s.theme());
        World world = s.start().getWorld();
        StructureApi.Placement pl = s.placement();
        PlacementValidator.Site site = new PlacementValidator.Site(world, pl.origin(), pl.sizeX(), pl.sizeY(), pl.sizeZ());
        return module.startConstruction(town, s.type(), site, pl.rotation(), s.theme(), res, player, false, false);
    }

    public void cancel(Player player) {
        Session s = sessions.remove(player.getUniqueId());
        if (s != null) clear(s.displays());
        List<Entity> stray = strayPreviews.remove(player.getUniqueId());
        if (stray != null) clear(stray);
    }

    public boolean hasSession(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    void tick() {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(s -> {
            if (s.expiresAt() >= now) return false;
            clear(s.displays());
            return true;
        });
    }

    void clearAll() {
        for (Session s : sessions.values()) clear(s.displays());
        sessions.clear();
        for (List<Entity> list : strayPreviews.values()) clear(list);
        strayPreviews.clear();
    }

    private void clearStray(UUID player, List<Entity> list) {
        if (strayPreviews.get(player) == list) strayPreviews.remove(player);
        clear(list);
    }

    private static void clear(List<Entity> displays) {
        for (Entity e : displays) if (e.isValid()) e.remove();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancel(e.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        cancel(e.getPlayer());
    }

    // --- preview ----------------------------------------------------------------------------------------------------

    /**
     * Outline of the volume as 12 thin block displays plus a beam at the start point, visible only to the player.
     * All displays are anchored at the player's position (always loaded) and translated, so no chunk is loaded.
     */
    private List<Entity> preview(Player player, PlacementValidator.Site site, boolean valid) {
        List<Entity> list = new ArrayList<>();
        if (!module.settings().previewEnabled()) return list;
        Location anchor = player.getLocation().getBlock().getLocation();
        BlockPos o = site.origin();
        float t = module.settings().edgeThickness();
        float x1 = o.x() - anchor.getBlockX();
        float y1 = o.y() - anchor.getBlockY();
        float z1 = o.z() - anchor.getBlockZ();
        float x2 = x1 + site.sizeX();
        float y2 = y1 + site.sizeY();
        float z2 = z1 + site.sizeZ();
        float lx = site.sizeX();
        float ly = site.sizeY();
        float lz = site.sizeZ();
        BlockData data = (valid ? module.settings().previewValid() : module.settings().previewInvalid()).createBlockData();
        Color glow = valid ? Color.LIME : Color.RED;
        float h = t / 2;
        for (float y : new float[]{y1, y2}) {
            for (float z : new float[]{z1, z2}) list.add(edge(player, anchor, data, glow, x1, y - h, z - h, lx, t, t));
            for (float x : new float[]{x1, x2}) list.add(edge(player, anchor, data, glow, x - h, y - h, z1, t, t, lz));
        }
        for (float x : new float[]{x1, x2}) {
            for (float z : new float[]{z1, z2}) list.add(edge(player, anchor, data, glow, x - h, y1, z - h, t, ly, t));
        }
        BlockData beam = Material.LIME_STAINED_GLASS.createBlockData();
        list.add(edge(player, anchor, beam, Color.LIME, 0.4f, 0, 0.4f, 0.2f, module.settings().beamHeight(), 0.2f));
        return list;
    }

    private Entity edge(Player player, Location anchor, BlockData data, Color glow, float tx, float ty, float tz,
                        float sx, float sy, float sz) {
        BlockDisplay display = anchor.getWorld().spawn(anchor, BlockDisplay.class, d -> {
            d.setVisibleByDefault(false);
            d.setPersistent(false);
            d.setBlock(data);
            d.setGlowing(true);
            d.setGlowColorOverride(glow);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(4f);
            d.setTransformation(new Transformation(new Vector3f(tx, ty, tz), new Quaternionf(),
                    new Vector3f(Math.max(0.01f, sx), Math.max(0.01f, sy), Math.max(0.01f, sz)), new Quaternionf()));
        });
        player.showEntity(civ.plugin(), display);
        return display;
    }
}
