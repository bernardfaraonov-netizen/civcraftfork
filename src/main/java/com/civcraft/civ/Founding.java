package com.civcraft.civ;

import com.civcraft.CivCraft;
import com.civcraft.camp.CampModule;
import com.civcraft.chat.Channels;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.core.ui.Prompts;
import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.core.util.Cuboid;
import com.civcraft.diplomacy.DiplomacyApi;
import com.civcraft.economy.BiomeTable;
import com.civcraft.economy.EconomyEngine;
import com.civcraft.economy.Production;
import com.civcraft.event.CivFoundedEvent;
import com.civcraft.government.GovernmentModule;
import com.civcraft.gui.Items;
import com.civcraft.gui.PagedMenu;
import com.civcraft.item.ItemApi;
import com.civcraft.model.Claim;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.resident.Names;
import com.civcraft.resident.ResidentModule;
import com.civcraft.structure.StructureApi;
import com.civcraft.template.Template;
import com.civcraft.template.TemplateService;
import com.civcraft.town.TownModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Founding a province with the founding flag (spec §5.1–§5.2) and new towns with a settler (§6.1).
 * Every step is re-validated at the end of the dialog chain, the item is consumed only when everything
 * succeeded, and a failure while placing the town hall rolls the whole foundation back.
 */
public final class Founding {

    /** Where the new town hall would stand. */
    public record Site(BlockPos origin, StructureRotation rotation, int sizeX, int sizeY, int sizeZ) {
        public BlockPos center() {
            return new BlockPos(origin.world(), origin.x() + sizeX / 2, origin.y(), origin.z() + sizeZ / 2);
        }

        public Cuboid box() {
            return Cuboid.of(origin, sizeX, sizeY, sizeZ);
        }
    }

    private final CivCraft civ;
    private final CivModule module;

    Founding(CivCraft civ, CivModule module) {
        this.civ = civ;
        this.module = module;
    }

    void enable() {
        ItemApi items = civ.apiOrNull(ItemApi.class);
        if (items == null) {
            civ.logger().warning("No item module: the founding flag and settlers cannot be used");
            return;
        }
        items.onUse(flagId(), (player, event) -> run(player, () -> useFlag(player)));
        items.onUse(settlerId(), (player, event) -> run(player, () -> useSettler(player)));
    }

    private String flagId() {
        return civ.balance().file("civ").getString("items.founding-flag", "founding_flag");
    }

    private String settlerId() {
        return civ.balance().file("civ").getString("items.settler", "settler");
    }

    private interface Step {
        void run() throws CivException;
    }

    private void run(Player p, Step step) {
        try {
            step.run();
        } catch (CivException e) {
            civ.messages().send(p, e.key(), e.args());
        }
    }

    private TownModule towns() {
        return civ.module(TownModule.class);
    }

    // --- site -----------------------------------------------------------------------------------

    public Site site(Player p) throws CivException {
        StructureApi api = civ.apiOrNull(StructureApi.class);
        String type = civ.balance().file("civ").getString("town-hall-type", "town_hall");
        if (api != null) {
            StructureApi.Placement pl = api.placementFor(p, type, 0);
            return new Site(pl.origin(), pl.rotation(), pl.sizeX(), pl.sizeY(), pl.sizeZ());
        }
        StructureRotation rotation = TemplateService.rotationFacing(p.getFacing());
        Template t = civ.templates().rotated(civ.settings().defaultTheme(), type, rotation);
        if (t == null) throw new CivException("civ.found.no-template");
        BlockPos at = BlockPos.of(p.getLocation());
        return new Site(new BlockPos(at.world(), at.x() - t.sizeX() / 2, at.y(), at.z() - t.sizeZ() / 2), rotation,
                t.sizeX(), t.sizeY(), t.sizeZ());
    }

    /**
     * Location rules shared by the flag and settlers: game world, 80 % solid ground, free chunks, 150
     * blocks from every town centre, 12.5 blocks from foreign culture, not during war.
     */
    public void checkSite(Site site, Civilization own) throws CivException {
        if (!civ.settings().isGameWorld(site.origin().world())) throw new CivException("civ.found.world");
        DiplomacyApi dip = civ.apiOrNull(DiplomacyApi.class);
        if (dip != null && dip.isWarTime()) throw new CivException("civ.found.war");
        org.bukkit.World world = site.origin().bukkitWorld();
        if (world == null) throw new CivException("civ.found.world");
        double minGround = civ.balance().getDouble("civ", "min-ground", 0.8);
        if (TemplateService.groundSupport(world, site.origin(), site.sizeX(), site.sizeZ()) < minGround) {
            throw new CivException("civ.found.ground", Messages.arg("percent", Math.round(minGround * 100)));
        }
        for (ChunkKey chunk : site.box().chunks()) {
            if (civ.state().claim(chunk) != null) throw new CivException("civ.found.claimed");
        }
        BlockPos center = site.center();
        double minTown = civ.balance().getDouble("core", "town.settler-min-distance", 150);
        for (Town t : civ.state().towns()) {
            if (t.center() == null || !Objects.equals(t.center().world(), center.world())) continue;
            double d = Production.xzDistance(t.center(), center);
            if (d < minTown) throw new CivException("civ.found.too-close", Messages.arg("town", t.name()),
                    Messages.arg("distance", Math.round(d)), Messages.arg("min", Math.round(minTown)));
        }
        double minCulture = civ.balance().getDouble("core", "town.foreign-culture-min-distance-blocks", 12.5);
        int r = (int) Math.ceil(minCulture / 16.0) + 1;
        ChunkKey c0 = center.chunk();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkKey c = c0.offset(dx, dz);
                Town owner = civ.culture().owner(c);
                if (owner == null || (own != null && own.id().equals(owner.civId()))) continue;
                int minX = c.x() << 4;
                int minZ = c.z() << 4;
                double ddx = Math.max(0, Math.max(minX - center.x(), center.x() - (minX + 15)));
                double ddz = Math.max(0, Math.max(minZ - center.z(), center.z() - (minZ + 15)));
                if (Math.sqrt(ddx * ddx + ddz * ddz) < minCulture) {
                    throw new CivException("civ.found.foreign-culture", Messages.arg("town", owner.name()));
                }
            }
        }
    }

    /** Survey lines for the confirmation dialog (like /t survey). */
    private List<Component> surveyLines(Site site) {
        EconomyEngine eco = towns().economy();
        List<ChunkKey> chunks = civ.culture().preview(site.center(), 1);
        BiomeTable.Values sum = BiomeTable.Values.ZERO;
        int unknown = 0;
        for (ChunkKey chunk : chunks) {
            String b = eco.biomeCache().biome(chunk);
            if (b == null) unknown++;
            else sum = sum.plus(eco.biomes().values(b));
        }
        return civ.messages().lines("civ.found.survey", Messages.arg("chunks", chunks.size()),
                Messages.number("hammers", sum.hammers()), Messages.number("growth", sum.growth()),
                Messages.number("happiness", sum.happiness()), Messages.number("beakers", sum.beakers()),
                Messages.arg("unknown", unknown));
    }

    // --- themes ---------------------------------------------------------------------------------

    private List<String> themes(Player p) {
        List<String> result = new ArrayList<>();
        String type = civ.balance().file("civ").getString("town-hall-type", "town_hall");
        for (Map<?, ?> entry : civ.balance().file("civ").getMapList("themes")) {
            String id = String.valueOf(entry.get("id"));
            Object perm = entry.get("permission");
            if (perm != null && !p.hasPermission(String.valueOf(perm))) continue;
            if (!id.equals(civ.settings().defaultTheme()) && !civ.templates().exists(id, type)) continue;
            result.add(id);
        }
        if (!result.contains(civ.settings().defaultTheme())) result.addFirst(civ.settings().defaultTheme());
        return result;
    }

    private void chooseTheme(Player p, Consumer<String> then) {
        List<String> themes = themes(p);
        if (themes.size() <= 1) {
            then.accept(themes.getFirst());
            return;
        }
        new PagedMenu<String>(civ.messages().component("civ.found.theme-title")) {
            @Override
            protected List<String> entries(Player viewer) {
                return themes;
            }

            @Override
            protected ItemStack icon(Player viewer, String theme) {
                String key = "civ.theme." + theme;
                return Items.of(Material.PAINTING).name(civ.messages().has(key) ? civ.messages().component(key) : Component.text(theme)).build();
            }

            @Override
            protected Consumer<InventoryClickEvent> action(Player viewer, String theme) {
                return e -> {
                    viewer.closeInventory();
                    then.accept(theme);
                };
            }
        }.open(p);
    }

    // --- founding flag --------------------------------------------------------------------------

    private void useFlag(Player p) throws CivException {
        Resident r = civ.state().resident(p);
        if (r == null) throw new CivException("error.internal");
        checkFounder(p, r);
        Site site = site(p);
        checkSite(site, null);
        Messages m = civ.messages();
        askName(p, "civ.found.civ-name", null, civName -> {
            if (civ.state().civByName(civName) != null) throw new CivException("civ.found.name-taken", Messages.arg("name", civName));
            askTag(p, tag -> {
                if (civ.state().civByTag(tag) != null) throw new CivException("civ.found.tag-taken", Messages.arg("tag", tag));
                askName(p, "civ.found.town-name", null, townName -> {
                    if (civ.state().townByName(townName) != null) throw new CivException("town.name-taken", Messages.arg("name", townName));
                    chooseTheme(p, theme -> {
                        List<Component> body = new ArrayList<>(m.lines("civ.found.confirm-body", Messages.arg("civ", civName),
                                Messages.arg("tag", tag), Messages.arg("town", townName),
                                Messages.money("balance", civ.balance().coins("core", "civ.province-found-balance", 25000))));
                        body.addAll(surveyLines(site));
                        Prompts.confirm(p, m.component("civ.found.confirm-title"), body, m.component("prompt.yes"), m.component("prompt.no"),
                                player -> run(player, () -> foundCivilization(player, civName, tag, townName, theme, site)));
                    });
                });
            });
        });
    }

    private void checkFounder(Player p, Resident r) throws CivException {
        if (r.hasTown()) throw new CivException("civ.found.in-town");
        long required = civ.balance().coins("core", "civ.province-found-balance", 25000);
        if (!r.has(required)) throw new CivException("civ.found.balance", Messages.money("amount", required));
        ItemApi items = civ.apiOrNull(ItemApi.class);
        if (items == null || items.count(p, flagId()) < 1) throw new CivException("civ.found.no-flag");
    }

    @FunctionalInterface
    private interface Input {
        void accept(String value) throws CivException;
    }

    private void askName(Player p, String titleKey, Component error, Input then) {
        Messages m = civ.messages();
        List<Component> body = new ArrayList<>(m.lines(titleKey + "-body"));
        if (error != null) body.addFirst(error);
        Prompts.text(p, m.component(titleKey), body, m.component("civ.found.name-label"), "", 20, m.component("prompt.ok"),
                (player, value) -> {
                    try {
                        then.accept(Names.name(value));
                    } catch (CivException e) {
                        askName(player, titleKey, m.component(e.key(), e.args()), then);
                    }
                });
    }

    private void askTag(Player p, Input then) {
        Messages m = civ.messages();
        Prompts.text(p, m.component("civ.found.tag"), m.lines("civ.found.tag-body"), m.component("civ.found.tag-label"), "", 5,
                m.component("prompt.ok"), (player, value) -> {
                    try {
                        then.accept(Names.tag(value));
                    } catch (CivException e) {
                        m.send(player, e.key(), e.args());
                        askTag(player, then);
                    }
                });
    }

    private void foundCivilization(Player p, String civName, String tag, String townName, String theme, Site site) throws CivException {
        Resident r = civ.state().resident(p);
        checkFounder(p, r);
        checkSite(site, null);
        if (civ.state().civByName(civName) != null) throw new CivException("civ.found.name-taken", Messages.arg("name", civName));
        if (civ.state().civByTag(tag) != null) throw new CivException("civ.found.tag-taken", Messages.arg("tag", tag));
        if (civ.state().townByName(townName) != null) throw new CivException("town.name-taken", Messages.arg("name", townName));
        CampModule camps = civ.apiOrNull(CampModule.class);
        if (camps != null) camps.leaveForTown(r);
        GovernmentModule gov = civ.module(GovernmentModule.class);
        Civilization c = new Civilization(UUID.randomUUID().toString(), civName, tag, p.getUniqueId());
        c.government(gov.startId());
        c.taxes(civ.balance().getDouble("core", "civ.default-taxes", 0.10));
        c.science(civ.balance().getDouble("core", "civ.default-science", 0.50));
        civ.state().addCiv(c);
        Town town = towns().service().create(townName, c, r, site.center(), theme);
        c.capitalId(town.id());
        civ.state().save(c);
        try {
            placeTownHall(p, town, site, theme);
            ItemApi items = civ.api(ItemApi.class);
            if (!items.take(p, flagId(), 1)) throw new CivException("civ.found.no-flag");
        } catch (CivException | RuntimeException e) {
            towns().service().deleteCiv(c);
            if (e instanceof CivException ce) throw ce;
            civ.logger().log(Level.SEVERE, "Founding " + civName + " failed", e);
            throw new CivException("error.internal");
        }
        new CivFoundedEvent(c.id()).call();
        civ.culture().recompute(true);
        civ.stats().invalidate();
        towns().production().invalidate();
        Channels.global("civ.found.announce", Messages.arg("civ", civName), Messages.arg("town", townName), Messages.arg("name", p.getName()));
    }

    /** Claims the footprint (locked) and starts the town hall (built with hammers, spec §5.2). */
    private void placeTownHall(Player p, Town town, Site site, String theme) throws CivException {
        for (ChunkKey chunk : site.box().chunks()) {
            if (civ.state().claim(chunk) != null) continue;
            Claim claim = new Claim(chunk, town.id());
            claim.locked(true);
            civ.state().addClaim(claim);
        }
        StructureApi api = civ.apiOrNull(StructureApi.class);
        if (api == null) {
            civ.logger().warning("No structure module: town " + town.name() + " was founded without a town hall");
            return;
        }
        String type = civ.balance().file("civ").getString("town-hall-type", "town_hall");
        api.place(p, town, type, site.origin(), site.rotation(), theme, false, true);
    }

    // --- settler --------------------------------------------------------------------------------

    private NamespacedKey civKey() {
        return new NamespacedKey(civ.plugin(), civ.balance().file("civ").getString("items.settler-civ-key", "civ"));
    }

    private void checkSettler(Player p, Resident r, Civilization c) throws CivException {
        Town own = civ.state().townOf(r);
        if (own == null || c == null) throw new CivException("error.not-in-civ");
        if (c.isProvince()) throw new CivException("civ.settler.province");
        for (Town t : civ.state().towns()) {
            if (t.isOfficial(p.getUniqueId())) throw new CivException("civ.settler.official", Messages.arg("town", t.name()));
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        ItemApi items = civ.apiOrNull(ItemApi.class);
        if (items == null || items.count(p, settlerId()) < 1) throw new CivException("civ.settler.no-item");
        if (items.is(hand, settlerId())) {
            String bound = hand.getPersistentDataContainer().get(civKey(), PersistentDataType.STRING);
            if (bound != null && !bound.equals(c.id())) throw new CivException("civ.settler.other-civ");
        }
    }

    private void useSettler(Player p) throws CivException {
        Resident r = civ.state().resident(p);
        Civilization c = civ.state().civOf(r);
        checkSettler(p, r, c);
        Site site = site(p);
        checkSite(site, c);
        Messages m = civ.messages();
        chooseTheme(p, theme -> askName(p, "civ.settler.town-name", null, townName -> {
            if (civ.state().townByName(townName) != null) throw new CivException("town.name-taken", Messages.arg("name", townName));
            List<Component> body = new ArrayList<>(m.lines("civ.settler.confirm-body", Messages.arg("town", townName)));
            body.addAll(surveyLines(site));
            Prompts.confirm(p, m.component("civ.settler.confirm-title"), body, m.component("prompt.yes"), m.component("prompt.no"),
                    player -> run(player, () -> requestSettler(player, c, townName, theme, site)));
        }));
    }

    /** The settler needs a leader's approval (spec §6.1 step 6); leaders found immediately. */
    private void requestSettler(Player p, Civilization c, String townName, String theme, Site site) throws CivException {
        Resident r = civ.state().resident(p);
        checkSettler(p, r, c);
        checkSite(site, c);
        if (c.isLeader(p.getUniqueId())) {
            foundTown(p, c, townName, theme, site);
            return;
        }
        List<UUID> leaders = new ArrayList<>();
        leaders.add(c.owner());
        leaders.addAll(c.leaders());
        leaders.removeIf(id -> Bukkit.getPlayer(id) == null);
        if (leaders.isEmpty()) throw new CivException("civ.settler.no-leaders");
        UUID founder = p.getUniqueId();
        civ.module(ResidentModule.class).requests().ask("settler", "settler:" + founder, leaders, founder,
                civ.messages().component("civ.settler.question", Messages.arg("name", p.getName()), Messages.arg("town", townName),
                        Messages.arg("x", site.center().x()), Messages.arg("z", site.center().z())),
                Duration.ofSeconds(civ.balance().getInt("civ", "settler-request-seconds", 300)), responder -> {
                    Player f = Bukkit.getPlayer(founder);
                    if (f == null) throw new CivException("civ.settler.founder-offline");
                    Civilization now = civ.state().civOf(f);
                    if (now == null || !now.id().equals(c.id()) || !c.isLeader(responder.getUniqueId())) {
                        throw new CivException("civ.settler.stale");
                    }
                    foundTown(f, c, townName, theme, site);
                }, responder -> {
                    Player f = Bukkit.getPlayer(founder);
                    if (f != null) civ.messages().send(f, "civ.settler.denied", Messages.arg("name", responder.getName()));
                });
        civ.messages().send(p, "civ.settler.sent");
    }

    private void foundTown(Player p, Civilization c, String townName, String theme, Site site) throws CivException {
        Resident r = civ.state().resident(p);
        checkSettler(p, r, c);
        checkSite(site, c);
        if (civ.state().townByName(townName) != null) throw new CivException("town.name-taken", Messages.arg("name", townName));
        Town old = civ.state().townOf(r);
        Set<String> oldGroups = new HashSet<>();
        for (Map.Entry<String, Set<UUID>> e : old.groups().entrySet()) if (e.getValue().contains(r.uuid())) oldGroups.add(e.getKey());
        Town town = towns().service().create(townName, c, r, site.center(), theme);
        try {
            placeTownHall(p, town, site, theme);
            if (!civ.api(ItemApi.class).take(p, settlerId(), 1)) throw new CivException("civ.settler.no-item");
        } catch (CivException | RuntimeException e) {
            towns().service().delete(town, null);
            // Put the founder back where they were (no rejoin cooldown).
            Town back = civ.state().town(old.id());
            if (back != null && !r.hasTown()) {
                for (String g : oldGroups) back.groups().computeIfAbsent(g, k -> new HashSet<>()).add(r.uuid());
                r.townId(back.id());
                civ.state().save(r);
                civ.state().save(back);
            }
            if (e instanceof CivException ce) throw ce;
            civ.logger().log(Level.SEVERE, "Settler town " + townName + " failed", e);
            throw new CivException("error.internal");
        }
        civ.culture().recompute(true);
        civ.stats().invalidate();
        towns().production().invalidate();
        Channels.civ(c, "civ.settler.founded", Messages.arg("town", townName), Messages.arg("name", p.getName()));
        Channels.global("civ.settler.announce", Messages.arg("town", townName), Messages.arg("civ", c.name()));
    }
}
