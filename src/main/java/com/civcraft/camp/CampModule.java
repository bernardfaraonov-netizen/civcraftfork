package com.civcraft.camp;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.chat.Channels;
import com.civcraft.clock.GameClock;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.core.ui.Holograms;
import com.civcraft.core.ui.Prompts;
import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.core.util.Cuboid;
import com.civcraft.core.util.Money;
import com.civcraft.economy.Ledger;
import com.civcraft.event.CultureChangedEvent;
import com.civcraft.item.ItemApi;
import com.civcraft.model.Camp;
import com.civcraft.model.Resident;
import com.civcraft.protection.Action;
import com.civcraft.protection.Guard;
import com.civcraft.protection.Verdict;
import com.civcraft.resident.Names;
import com.civcraft.template.Template;
import com.civcraft.template.TemplateService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Furnace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.sign.Side;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Camps (spec §4, §19.5): placement with the camp door, protection of structural blocks, coal upkeep of
 * the campfire, raids on the control block, upgrades (garden, crusher, longhouse), destruction when a
 * town's culture swallows the camp, undo and refresh.
 */
public final class CampModule implements Module, Listener {

    public static final String GARDEN = "garden";
    public static final String CRUSHER = "crusher";
    public static final String LONGHOUSE = "longhouse";

    private CivCraft civ;
    private final Map<String, CampLayout> layouts = new HashMap<>();
    private final Map<String, CampTerrain> terrains = new HashMap<>();
    private final Map<UUID, Long> lastHit = new HashMap<>();
    private final Map<String, Instant> raidAnnounced = new HashMap<>();
    private final Random random = new Random();
    private CampCommands commands;

    @Override
    public String id() {
        return "camp";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("camp");
        civ.store().createCollection(CampTerrain.COLLECTION);
        for (CampTerrain t : civ.store().loadAll(CampTerrain.COLLECTION, CampTerrain.class)) {
            if (civ.state().camp(t.storageId()) != null) terrains.put(t.storageId(), t);
            else civ.saves().delete(CampTerrain.COLLECTION, t.storageId());
        }
    }

    @Override
    public void enable(CivCraft civ) {
        for (Camp camp : List.copyOf(civ.state().camps())) {
            CampLayout layout = buildLayout(camp);
            if (layout == null) {
                civ.logger().warning("Camp " + camp.name() + " has no template; it is not protected");
                continue;
            }
            layouts.put(camp.id(), layout);
        }
        civ.listen(this);
        civ.protection().register(new CampGuard());
        civ.clock().hourly(GameClock.PRODUCTION, "camp-upkeep", this::hourly);
        civ.tasks().timer(40, 20L * civ.balance().getInt("camp", "crusher.cycle-seconds", 2), this::crusherTick);
        civ.tasks().later(40, () -> layouts.values().forEach(this::updateHologram));
        ItemApi items = civ.apiOrNull(ItemApi.class);
        if (items == null) civ.logger().warning("No item module: camp doors cannot be used");
        else items.onUse(doorId(), (player, event) -> {
            try {
                useDoor(player);
            } catch (CivException e) {
                civ.messages().send(player, e.key(), e.args());
            }
        });
        commands = new CampCommands(civ, this);
        commands.register();
    }

    @Override
    public void disable(CivCraft civ) {
        for (String id : layouts.keySet()) Holograms.remove("camp:" + id);
    }

    String doorId() {
        return civ.balance().file("camp").getString("items.camp-door", "camp_door");
    }

    CampLayout layout(Camp camp) {
        return layouts.get(camp.id());
    }

    Map<String, CampLayout> layouts() {
        return layouts;
    }

    private CampLayout buildLayout(Camp camp) {
        StructureRotation rotation;
        try {
            rotation = StructureRotation.valueOf(camp.rotation());
        } catch (RuntimeException e) {
            rotation = StructureRotation.NONE;
        }
        Template t = civ.templates().rotated(camp.theme() == null ? civ.settings().defaultTheme() : camp.theme(), templateId(), rotation);
        return t == null ? null : new CampLayout(camp, t);
    }

    private String templateId() {
        return civ.balance().file("camp").getString("template", "camp");
    }

    /** The camp whose box contains the block, or null. */
    public Camp campAt(BlockPos pos) {
        for (CampLayout l : layouts.values()) if (l.contains(pos)) return l.camp;
        return null;
    }

    int maxHp() {
        return civ.balance().getInt("camp", "control-hp", 70);
    }

    // --- placement ------------------------------------------------------------------------------

    private record Site(BlockPos origin, StructureRotation rotation, Template template) {
        Cuboid box() {
            return Cuboid.of(origin, template.sizeX(), template.sizeY(), template.sizeZ());
        }
    }

    private Site site(Player p) throws CivException {
        StructureRotation rotation = TemplateService.rotationFacing(p.getFacing());
        String theme = civ.settings().defaultTheme();
        Template t = civ.templates().rotated(theme, templateId(), rotation);
        if (t == null) throw new CivException("camp.place.no-template");
        Location l = p.getLocation();
        int px = l.getBlockX();
        int pz = l.getBlockZ();
        int gap = civ.balance().getInt("camp", "place-gap", 2);
        int ox;
        int oz;
        switch (p.getFacing()) {
            case NORTH -> {
                ox = px - t.sizeX() / 2;
                oz = pz - gap - t.sizeZ() + 1;
            }
            case SOUTH -> {
                ox = px - t.sizeX() / 2;
                oz = pz + gap;
            }
            case EAST -> {
                ox = px + gap;
                oz = pz - t.sizeZ() / 2;
            }
            default -> {
                ox = px - gap - t.sizeX() + 1;
                oz = pz - t.sizeZ() / 2;
            }
        }
        return new Site(new BlockPos(l.getWorld().getName(), ox, l.getBlockY(), oz), rotation, t);
    }

    private void checkSite(Player p, Site site) throws CivException {
        World w = site.origin().bukkitWorld();
        if (w == null || !civ.settings().isGameWorld(w)) throw new CivException("camp.place.world");
        Cuboid box = site.box();
        if (box.maxY() >= w.getMaxHeight() || box.minY() <= w.getMinHeight()) throw new CivException("camp.place.height");
        double minGround = civ.balance().getDouble("camp", "min-ground", 0.8);
        if (TemplateService.groundSupport(w, site.origin(), site.template().sizeX(), site.template().sizeZ()) < minGround) {
            throw new CivException("camp.place.ground", Messages.arg("percent", Math.round(minGround * 100)));
        }
        for (ChunkKey chunk : box.chunks()) {
            if (civ.state().claim(chunk) != null) throw new CivException("camp.place.claimed");
            if (civ.culture().owner(chunk) != null) throw new CivException("camp.place.culture");
        }
        int margin = civ.balance().getInt("camp", "min-camp-gap", 4);
        for (CampLayout l : layouts.values()) {
            if (l.box.expand(margin).intersects(box)) throw new CivException("camp.place.overlap", Messages.arg("camp", l.camp.name()));
        }
        Location spawn = w.getSpawnLocation();
        int minSpawn = civ.balance().getInt("camp", "min-spawn-distance", 100);
        BlockPos c = box.center();
        if (Math.hypot(c.x() - spawn.getX(), c.z() - spawn.getZ()) < minSpawn) throw new CivException("camp.place.spawn");
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType().isAir()) continue;
                    if (b.getState(false) instanceof Container) throw new CivException("camp.place.container");
                }
            }
        }
    }

    private void useDoor(Player p) throws CivException {
        Resident r = civ.state().resident(p);
        if (r == null) throw new CivException("error.internal");
        if (r.hasTown()) throw new CivException("camp.place.in-town");
        if (r.campId() != null) throw new CivException("camp.place.in-camp");
        Site site = site(p);
        checkSite(p, site);
        askName(p, site, null);
    }

    private void askName(Player p, Site site, Component error) {
        Messages m = civ.messages();
        List<Component> body = new ArrayList<>(m.lines("camp.place.name-body"));
        if (error != null) body.addFirst(error);
        Prompts.text(p, m.component("camp.place.name-title"), body, m.component("camp.place.name-label"), "", 20, m.component("prompt.ok"),
                (player, value) -> {
                    try {
                        String name = Names.name(value);
                        if (civ.state().campByName(name) != null) throw new CivException("camp.place.name-taken", Messages.arg("name", name));
                        create(player, site, name);
                    } catch (CivException e) {
                        if (e.key().startsWith("names.") || e.key().equals("camp.place.name-taken")) {
                            askName(player, site, m.component(e.key(), e.args()));
                        } else {
                            m.send(player, e.key(), e.args());
                        }
                    }
                });
    }

    private void create(Player p, Site site, String name) throws CivException {
        Resident r = civ.state().resident(p);
        if (r.hasTown()) throw new CivException("camp.place.in-town");
        if (r.campId() != null) throw new CivException("camp.place.in-camp");
        checkSite(p, site);
        ItemApi items = civ.apiOrNull(ItemApi.class);
        if (items == null || !items.take(p, doorId(), 1)) throw new CivException("camp.place.no-door");
        World w = site.origin().bukkitWorld();
        String id = UUID.randomUUID().toString();
        CampTerrain terrain = CampTerrain.capture(id, w, site.box());
        terrains.put(id, terrain);
        civ.saves().save(CampTerrain.COLLECTION, terrain);
        TemplateService.pasteAll(w, site.origin(), site.template());
        Camp camp = new Camp(id, name, p.getUniqueId(), site.origin(), site.rotation().name(), civ.settings().defaultTheme(), maxHp());
        camp.tag(name.length() > 5 ? name.substring(0, 5).toUpperCase(Locale.ROOT) : name.toUpperCase(Locale.ROOT));
        civ.state().addCamp(camp);
        r.campId(id);
        civ.state().save(r);
        CampLayout layout = new CampLayout(camp, site.template());
        layouts.put(id, layout);
        applyMarkers(layout, true);
        updateHologram(layout);
        civ.messages().send(p, "camp.place.done", Messages.arg("camp", name));
        Channels.global("camp.place.announce", Messages.arg("camp", name), Messages.arg("name", p.getName()));
    }

    // --- markers --------------------------------------------------------------------------------

    /**
     * Places the functional blocks of the markers according to the purchased upgrades. With
     * {@code force} everything is (re)placed; otherwise only missing or wrong blocks are fixed, so
     * furnace and chest contents survive a refresh.
     */
    void applyMarkers(CampLayout l, boolean force) {
        World w = l.camp.origin().bukkitWorld();
        if (w == null) return;
        Camp camp = l.camp;
        if (l.control != null) place(w, l.control.pos(), Material.OBSIDIAN, null, force);
        for (CampLayout.Point d : l.doors) placeDoor(w, d, force);
        for (CampLayout.Point f : l.furnaces) place(w, f.pos(), Material.FURNACE, f.facing(), force);
        for (CampLayout.Point f : l.fires) place(w, f.pos(), Material.FIRE, null, force);
        for (CampLayout.Point f : l.firepits) place(w, f.pos(), Material.FIRE, null, force);
        boolean garden = camp.upgrades().contains(GARDEN);
        for (CampLayout.Point g : l.growth) {
            Block b = w.getBlockAt(g.pos().x(), g.pos().y(), g.pos().z());
            Material want = garden ? Material.FARMLAND : Material.DIRT;
            if (force || b.getType() != want && !(garden && b.getType() == Material.FARMLAND)) b.setType(want, false);
        }
        for (CampLayout.Point s : l.gardenSigns) {
            if (garden) place(w, s.pos(), Material.AIR, null, true);
            else sign(w, s, "camp.sign.garden", force);
        }
        boolean crusher = camp.upgrades().contains(CRUSHER);
        for (CampLayout.Point s : new CampLayout.Point[]{l.sifterIn, l.sifterOut}) {
            if (s == null) continue;
            if (crusher) place(w, s.pos(), Material.CHEST, s.facing(), force);
            else sign(w, s, "camp.sign.crusher", force);
        }
        boolean longhouse = camp.upgrades().contains(LONGHOUSE);
        for (CampLayout.Point s : l.foodInputs) {
            if (longhouse) place(w, s.pos(), Material.CHEST, s.facing(), force);
            else sign(w, s, "camp.sign.longhouse", force);
        }
    }

    private void place(World w, BlockPos pos, Material type, String facing, boolean force) {
        Block b = w.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!force && b.getType() == type) return;
        if (b.getType() != type && b.getState(false) instanceof Container c) dropContents(c.getInventory(), b.getLocation());
        BlockData data = type.createBlockData();
        if (facing != null && data instanceof Directional d) {
            BlockFace face = face(facing);
            if (d.getFaces().contains(face)) d.setFacing(face);
        }
        b.setBlockData(data, false);
    }

    private void placeDoor(World w, CampLayout.Point p, boolean force) {
        Block bottom = w.getBlockAt(p.pos().x(), p.pos().y(), p.pos().z());
        Block top = bottom.getRelative(BlockFace.UP);
        if (!force && Tag.DOORS.isTagged(bottom.getType()) && Tag.DOORS.isTagged(top.getType())) return;
        BlockFace face = p.facing() == null ? BlockFace.NORTH : face(p.facing());
        Door lower = (Door) Material.OAK_DOOR.createBlockData();
        lower.setHalf(Bisected.Half.BOTTOM);
        if (lower.getFaces().contains(face)) lower.setFacing(face);
        Door upper = (Door) lower.clone();
        upper.setHalf(Bisected.Half.TOP);
        bottom.setBlockData(lower, false);
        top.setBlockData(upper, false);
    }

    private void sign(World w, CampLayout.Point p, String key, boolean force) {
        Block b = w.getBlockAt(p.pos().x(), p.pos().y(), p.pos().z());
        if (!force && Tag.SIGNS.isTagged(b.getType())) return;
        if (b.getState(false) instanceof Container c) dropContents(c.getInventory(), b.getLocation());
        BlockData data = Material.OAK_SIGN.createBlockData();
        if (p.facing() != null && data instanceof Rotatable rot) rot.setRotation(face(p.facing()));
        b.setBlockData(data, false);
        if (b.getState() instanceof Sign sign) {
            List<Component> lines = civ.messages().lines(key);
            for (int i = 0; i < Math.min(4, lines.size()); i++) sign.getSide(Side.FRONT).line(i, lines.get(i));
            sign.setWaxed(true);
            sign.update(true, false);
        }
    }

    private static BlockFace face(String facing) {
        try {
            return BlockFace.valueOf(facing.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return BlockFace.NORTH;
        }
    }

    void updateHologram(CampLayout l) {
        if (l.control == null || l.control.pos().bukkitWorld() == null) return;
        Location at = l.control.pos().center().add(0, 1.2, 0);
        Holograms.show("camp:" + l.camp.id(), at, civ.messages().component("camp.hologram",
                Messages.arg("camp", l.camp.name()), Messages.arg("hp", (int) Math.ceil(l.camp.hp())), Messages.arg("max", maxHp())));
    }

    /** Re-pastes missing structural blocks (ladders, doors, torches...) without touching player blocks (spec §4.1). */
    void refresh(CampLayout l) {
        World w = l.camp.origin().bukkitWorld();
        if (w == null) return;
        Template t = l.template;
        for (int y = 0; y < t.sizeY(); y++) {
            for (int z = 0; z < t.sizeZ(); z++) {
                for (int x = 0; x < t.sizeX(); x++) {
                    BlockData want = t.block(x, y, z);
                    if (want.getMaterial().isAir()) continue;
                    BlockPos pos = l.camp.origin().offset(x, y, z);
                    if (l.markers.contains(pos) || l.doorTops.contains(pos)) continue;
                    Block b = w.getBlockAt(pos.x(), pos.y(), pos.z());
                    if (b.getType().isAir() || b.isLiquid()) TemplateService.pasteBlock(w, l.camp.origin(), t, x, y, z);
                }
            }
        }
        applyMarkers(l, false);
        updateHologram(l);
    }

    // --- destruction ----------------------------------------------------------------------------

    private static void dropContents(Inventory inv, Location at) {
        for (ItemStack s : inv.getContents()) {
            if (s != null && !s.getType().isAir()) at.getWorld().dropItemNaturally(at.clone().add(0.5, 0.5, 0.5), s);
        }
        inv.clear();
    }

    /**
     * Removes a camp: every container in the box drops its items (spec §4.5 assumption), the original
     * terrain is restored and all members are released.
     */
    public void destroy(Camp camp, String reasonKey) {
        CampLayout l = layouts.remove(camp.id());
        Holograms.remove("camp:" + camp.id());
        World w = camp.origin().bukkitWorld();
        if (l != null && w != null) {
            Cuboid box = l.box;
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        Block b = w.getBlockAt(x, y, z);
                        if (b.getType().isAir()) continue;
                        BlockState state = b.getState(false);
                        if (state instanceof Container c) dropContents(c.getInventory(), b.getLocation());
                    }
                }
            }
            CampTerrain terrain = terrains.get(camp.id());
            if (terrain != null && civ.balance().file("camp").getBoolean("restore-terrain", true)) terrain.restore(w, box);
        }
        terrains.remove(camp.id());
        civ.saves().delete(CampTerrain.COLLECTION, camp.id());
        if (reasonKey != null) {
            Channels.camp(camp, reasonKey, Messages.arg("camp", camp.name()));
            Channels.global(reasonKey + "-global", Messages.arg("camp", camp.name()));
        }
        civ.state().removeCamp(camp);
    }

    /** A member founding a civilization or joining a town leaves the camp; a lone owner's camp is disbanded. */
    public void leaveForTown(Resident r) {
        Camp camp = civ.state().camp(r.campId());
        if (camp == null) return;
        camp.members().remove(r.uuid());
        r.campId(null);
        civ.state().save(r);
        if (camp.members().isEmpty()) {
            destroy(camp, "camp.destroyed.abandoned");
            return;
        }
        if (camp.owner().equals(r.uuid())) camp.owner(camp.members().iterator().next());
        civ.state().save(camp);
        Channels.camp(camp, "camp.left", Messages.arg("name", r.name()));
    }

    @EventHandler
    public void onCulture(CultureChangedEvent e) {
        for (CampLayout l : List.copyOf(layouts.values())) {
            for (ChunkKey chunk : l.chunks()) {
                if (e.changes().get(chunk) != null || civ.culture().owner(chunk) != null) {
                    destroy(l.camp, "camp.destroyed.culture");
                    break;
                }
            }
        }
    }

    // --- raids ----------------------------------------------------------------------------------

    /** Raid window: daily 2 h before the founding time of day, never in the first 22 h (spec §4.5). */
    public boolean raidOpen(Camp camp, Instant now) {
        if (camp.founded().plus(Duration.ofHours(civ.balance().getInt("camp", "raid.grace-hours", 22))).isAfter(now)) return false;
        ZonedDateTime t = now.atZone(civ.clock().zone());
        LocalTime at = camp.founded().atZone(civ.clock().zone()).toLocalTime();
        int hours = civ.balance().getInt("camp", "raid.window-hours", 2);
        for (LocalDate d : List.of(t.toLocalDate(), t.toLocalDate().plusDays(1))) {
            ZonedDateTime end = d.atTime(at).atZone(civ.clock().zone());
            ZonedDateTime start = end.minusHours(hours);
            if (!t.isBefore(start) && t.isBefore(end)) return true;
        }
        return false;
    }

    /** Start of the next raid window (or the current one). */
    public ZonedDateTime nextRaid(Camp camp) {
        Instant earliest = camp.founded().plus(Duration.ofHours(civ.balance().getInt("camp", "raid.grace-hours", 22)));
        ZonedDateTime now = ZonedDateTime.now(civ.clock().zone());
        LocalTime at = camp.founded().atZone(civ.clock().zone()).toLocalTime();
        int hours = civ.balance().getInt("camp", "raid.window-hours", 2);
        for (int i = -1; i < 4; i++) {
            ZonedDateTime end = now.toLocalDate().plusDays(i).atTime(at).atZone(civ.clock().zone());
            ZonedDateTime start = end.minusHours(hours);
            if (end.isAfter(now) && !end.toInstant().isBefore(earliest.plus(Duration.ofHours(hours)))) {
                return start.toInstant().isBefore(earliest) ? earliest.atZone(civ.clock().zone()) : start;
            }
        }
        return now.plusDays(1);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(BlockDamageEvent e) {
        BlockPos pos = BlockPos.of(e.getBlock());
        for (CampLayout l : layouts.values()) {
            if (l.control == null || !l.control.pos().equals(pos)) continue;
            e.setCancelled(true);
            Player p = e.getPlayer();
            Camp camp = l.camp;
            if (camp.members().contains(p.getUniqueId())) return;
            Resident r = civ.state().resident(p);
            if (r != null && r.isPvpProtected()) {
                civ.messages().actionBar(p, "resident.pvp-protected-self");
                return;
            }
            if (!raidOpen(camp, Instant.now())) {
                civ.messages().actionBar(p, "camp.raid.closed");
                return;
            }
            List<String> tools = civ.balance().file("camp").getStringList("raid.tools");
            if (!tools.contains(e.getItemInHand().getType().name())) {
                civ.messages().actionBar(p, "camp.raid.tool");
                return;
            }
            long now = System.currentTimeMillis();
            Long last = lastHit.get(p.getUniqueId());
            if (last != null && now - last < civ.balance().getInt("camp", "raid.hit-cooldown-ms", 400)) return;
            lastHit.put(p.getUniqueId(), now);
            camp.hp(Math.max(0, camp.hp() - 1));
            civ.state().save(camp);
            updateHologram(l);
            Instant announced = raidAnnounced.get(camp.id());
            if (announced == null || announced.plus(Duration.ofMinutes(10)).isBefore(Instant.now())) {
                raidAnnounced.put(camp.id(), Instant.now());
                Channels.camp(camp, "camp.raid.attacked", Messages.arg("name", p.getName()));
            }
            if (camp.hp() <= 0) destroy(camp, "camp.destroyed.raid");
            return;
        }
    }

    // --- hourly upkeep & longhouse --------------------------------------------------------------

    private void hourly() {
        for (CampLayout l : List.copyOf(layouts.values())) {
            try {
                if (!burnCoal(l)) continue;
                if (l.camp.upgrades().contains(LONGHOUSE)) longhouse(l);
            } catch (RuntimeException e) {
                civ.logger().log(Level.SEVERE, "Camp tick failed for " + l.camp.name(), e);
            }
        }
    }

    private List<FurnaceInventory> furnaces(CampLayout l) {
        List<FurnaceInventory> result = new ArrayList<>();
        World w = l.camp.origin().bukkitWorld();
        if (w == null) return result;
        for (CampLayout.Point f : l.furnaces) {
            if (w.getBlockAt(f.pos().x(), f.pos().y(), f.pos().z()).getState(false) instanceof Furnace furnace) result.add(furnace.getInventory());
        }
        return result;
    }

    private boolean isFuel(ItemStack s) {
        return s != null && civ.balance().file("camp").getStringList("coal.items").contains(s.getType().name());
    }

    /** Coal in both furnaces (fuel, smelting and result slots together). */
    int coal(CampLayout l) {
        int n = 0;
        for (FurnaceInventory inv : furnaces(l)) {
            for (ItemStack s : new ItemStack[]{inv.getFuel(), inv.getSmelting(), inv.getResult()}) if (isFuel(s)) n += s.getAmount();
        }
        return n;
    }

    /** Takes the hourly coal (spec §4.3); without enough coal the fire goes out and the camp is destroyed. */
    private boolean burnCoal(CampLayout l) {
        int need = civ.balance().getInt("camp", "coal.per-hour", 4);
        if (coal(l) < need) {
            destroy(l.camp, "camp.destroyed.coal");
            return false;
        }
        int left = need;
        for (FurnaceInventory inv : furnaces(l)) {
            for (int slot = 0; slot < 3 && left > 0; slot++) {
                ItemStack s = inv.getItem(slot);
                if (!isFuel(s)) continue;
                int take = Math.min(left, s.getAmount());
                s.setAmount(s.getAmount() - take);
                inv.setItem(slot, s.getAmount() <= 0 ? null : s);
                left -= take;
            }
        }
        World w = l.camp.origin().bukkitWorld();
        if (w != null) {
            // A campfire put out by hand or water lights up again (spec §4.3).
            for (CampLayout.Point f : l.firepits) {
                Block b = w.getBlockAt(f.pos().x(), f.pos().y(), f.pos().z());
                if (b.getType() != Material.FIRE) b.setType(Material.FIRE, false);
            }
            for (CampLayout.Point f : l.fires) {
                Block b = w.getBlockAt(f.pos().x(), f.pos().y(), f.pos().z());
                if (b.getType() != Material.FIRE) b.setType(Material.FIRE, false);
            }
        }
        int remaining = coal(l);
        int warnHours = civ.balance().getInt("camp", "coal.warn-hours", 6);
        if (remaining < need * warnHours) Channels.camp(l.camp, "camp.coal.low", Messages.arg("hours", remaining / need));
        return true;
    }

    private record LonghouseLevel(int level, int bread, double coins, int count, int tokens) {
    }

    private List<LonghouseLevel> longhouseLevels() {
        List<LonghouseLevel> result = new ArrayList<>();
        for (Map<?, ?> m : civ.balance().file("camp").getMapList("longhouse.levels")) {
            result.add(new LonghouseLevel(num(m.get("level"), 1), num(m.get("bread"), 1), dbl(m.get("coins")), num(m.get("count"), 0), num(m.get("tokens"), 1)));
        }
        if (result.isEmpty()) result.add(new LonghouseLevel(1, 1, 15, 5, 1));
        return result;
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    /**
     * Longhouse (spec §4.4): eats bread each hour, pays the owner and gives leadership tokens; enough
     * successful meals raise the level; starving lowers the progress (cottage-like assumption).
     */
    private void longhouse(CampLayout l) {
        Camp camp = l.camp;
        List<LonghouseLevel> levels = longhouseLevels();
        int index = Math.max(0, Math.min(levels.size() - 1, camp.longhouseLevel() - 1));
        LonghouseLevel lvl = levels.get(index);
        World w = camp.origin().bukkitWorld();
        List<Inventory> chests = new ArrayList<>();
        if (w != null) {
            for (CampLayout.Point p : l.foodInputs) {
                if (w.getBlockAt(p.pos().x(), p.pos().y(), p.pos().z()).getState(false) instanceof Container c) chests.add(c.getInventory());
            }
        }
        if (chests.isEmpty()) return;
        Material food = Material.matchMaterial(civ.balance().file("camp").getString("longhouse.food", "BREAD"));
        int available = 0;
        for (Inventory inv : chests) {
            for (ItemStack s : inv.getContents()) if (s != null && s.getType() == food && !s.hasItemMeta()) available += s.getAmount();
        }
        if (available < lvl.bread()) {
            if (camp.longhouseProgress() > 0) camp.longhouseProgress(camp.longhouseProgress() - 1);
            else if (camp.longhouseLevel() > 1) {
                camp.longhouseLevel(camp.longhouseLevel() - 1);
                camp.longhouseProgress(Math.max(0, levels.get(index - 1).count() - 1));
            }
            civ.state().save(camp);
            Channels.camp(camp, "camp.longhouse.starved", Messages.arg("level", camp.longhouseLevel()));
            return;
        }
        int left = lvl.bread();
        for (Inventory inv : chests) {
            for (int i = 0; i < inv.getSize() && left > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (s == null || s.getType() != food || s.hasItemMeta()) continue;
                int take = Math.min(left, s.getAmount());
                s.setAmount(s.getAmount() - take);
                inv.setItem(i, s.getAmount() <= 0 ? null : s);
                left -= take;
            }
        }
        Resident owner = civ.state().resident(camp.owner());
        long coins = Money.ofCoins(lvl.coins());
        if (owner != null) Ledger.credit(owner, coins);
        ItemApi items = civ.apiOrNull(ItemApi.class);
        String token = civ.balance().file("camp").getString("items.leadership-token", "token_of_leadership");
        if (items != null && items.exists(token) && lvl.tokens() > 0) {
            ItemStack stack = items.create(token, lvl.tokens());
            for (ItemStack rest : chests.getFirst().addItem(stack).values()) {
                Location at = l.foodInputs.getFirst().pos().center().add(0, 1, 0);
                at.getWorld().dropItemNaturally(at, rest);
            }
        }
        camp.leadershipTokens(camp.leadershipTokens() + lvl.tokens());
        boolean leveled = false;
        if (index < levels.size() - 1) {
            camp.longhouseProgress(camp.longhouseProgress() + 1);
            if (camp.longhouseProgress() >= lvl.count()) {
                camp.longhouseLevel(camp.longhouseLevel() + 1);
                camp.longhouseProgress(0);
                leveled = true;
            }
        }
        civ.state().save(camp);
        Channels.camp(camp, leveled ? "camp.longhouse.level-up" : "camp.longhouse.fed", Messages.money("coins", coins),
                Messages.arg("tokens", lvl.tokens()), Messages.arg("level", camp.longhouseLevel()));
    }

    // --- crusher --------------------------------------------------------------------------------

    /** Camp crusher: like the town crusher but without diamonds and emeralds (spec §4.4). One block per cycle. */
    private void crusherTick() {
        for (CampLayout l : layouts.values()) {
            if (!l.camp.upgrades().contains(CRUSHER) || l.sifterIn == null || l.sifterOut == null) continue;
            World w = l.camp.origin().bukkitWorld();
            if (w == null || !w.isChunkLoaded(l.sifterIn.pos().x() >> 4, l.sifterIn.pos().z() >> 4)) continue;
            if (!(w.getBlockAt(l.sifterIn.pos().x(), l.sifterIn.pos().y(), l.sifterIn.pos().z()).getState(false) instanceof Container in)) continue;
            if (!(w.getBlockAt(l.sifterOut.pos().x(), l.sifterOut.pos().y(), l.sifterOut.pos().z()).getState(false) instanceof Container out)) continue;
            crushOne(in.getInventory(), out.getInventory());
        }
    }

    private void crushOne(Inventory in, Inventory out) {
        if (out.firstEmpty() < 0) return;
        var inputs = civ.balance().section("camp", "crusher.inputs");
        for (int i = 0; i < in.getSize(); i++) {
            ItemStack s = in.getItem(i);
            if (s == null || s.hasItemMeta()) continue;
            if (!inputs.contains(s.getType().name())) continue;
            double mult = inputs.getDouble(s.getType().name(), 1);
            s.setAmount(s.getAmount() - 1);
            in.setItem(i, s.getAmount() <= 0 ? null : s);
            List<ItemStack> drops = new ArrayList<>();
            ItemApi items = civ.apiOrNull(ItemApi.class);
            for (Map<?, ?> loot : civ.balance().file("camp").getMapList("crusher.loot")) {
                double chance = dbl(loot.get("chance")) * mult;
                if (random.nextDouble() >= chance) continue;
                String id = String.valueOf(loot.get("item"));
                Material m = Material.matchMaterial(id);
                if (m != null) drops.add(ItemStack.of(m));
                else if (items != null && items.exists(id)) drops.add(items.create(id, 1));
            }
            if (drops.isEmpty()) drops.add(ItemStack.of(Material.GRAVEL));
            for (ItemStack d : drops) {
                for (ItemStack rest : out.addItem(d).values()) in.addItem(rest);
            }
            return;
        }
    }

    // --- crops ----------------------------------------------------------------------------------

    /**
     * Crops do not grow outside gardens and farms (spec §4.4): vanilla growth of farmland crops is
     * cancelled in the wilderness except on the garden plots of camps that bought the garden. Sugar
     * cane, cactus, melon and pumpkin stems grow everywhere; claims are left to the farm module.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        if (!civ.balance().file("camp").getBoolean("crops.block-wild-growth", true)) return;
        Material type = e.getBlock().getType();
        if (!Tag.CROPS.isTagged(type) || type == Material.MELON_STEM || type == Material.PUMPKIN_STEM) return;
        if (!civ.settings().isGameWorld(e.getBlock().getWorld())) return;
        if (civ.state().claim(ChunkKey.of(e.getBlock())) != null) return;
        BlockPos below = BlockPos.of(e.getBlock()).offset(0, -1, 0);
        for (CampLayout l : layouts.values()) {
            if (l.contains(below) && l.isGrowth(below) && l.camp.upgrades().contains(GARDEN)) return;
        }
        e.setCancelled(true);
    }

    // --- protection -----------------------------------------------------------------------------

    /**
     * Only the camp's structural blocks are protected; doors and structural containers open only for
     * members; blocks placed by players inside the camp are free (spec §4.2).
     */
    private final class CampGuard implements Guard {
        @Override
        public int priority() {
            return 400;
        }

        @Override
        public Verdict check(Player actor, Action action, Block block, Block source) {
            BlockPos pos = BlockPos.of(block);
            for (CampLayout l : layouts.values()) {
                if (!l.contains(pos)) continue;
                boolean structural = l.structural(pos);
                if (actor == null) {
                    if (!structural) return Verdict.PASS;
                    return action == Action.INTERACT ? Verdict.PASS : Verdict.deny(null);
                }
                boolean member = l.camp.members().contains(actor.getUniqueId());
                if (!structural) return Verdict.PASS;
                switch (action) {
                    case INTERACT, ITEMUSE -> {
                        return member ? Verdict.ALLOW : Verdict.deny("camp.protected-use");
                    }
                    case BREAK, PLACE -> {
                        if (member && action == Action.PLACE && l.isGrowth(pos)) return Verdict.ALLOW;
                        return Verdict.deny("camp.protected");
                    }
                    case ENTITY -> {
                        return Verdict.PASS;
                    }
                    default -> {
                        return Verdict.deny("camp.protected");
                    }
                }
            }
            return Verdict.PASS;
        }
    }

    // --- misc -----------------------------------------------------------------------------------

    Location teleportTarget(Camp camp) {
        CampLayout l = layouts.get(camp.id());
        BlockPos c = l == null ? camp.origin() : l.center();
        World w = c.bukkitWorld();
        if (w == null) return null;
        int y = w.getHighestBlockYAt(c.x(), c.z()) + 1;
        return new Location(w, c.x() + 0.5, y, c.z() + 0.5);
    }

    /** Gives the camp door back (used by /camp undo). */
    void giveDoor(Player p) {
        ItemApi items = civ.apiOrNull(ItemApi.class);
        if (items != null && items.exists(doorId())) items.give(p, items.create(doorId(), 1));
    }

    static Component none() {
        return Component.text("-");
    }

    Player online(UUID id) {
        return Bukkit.getPlayer(id);
    }
}
