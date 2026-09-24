package com.civcraft.pve;

import com.civcraft.CivCraft;
import com.civcraft.command.AdminRegistry;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.BlockPos;
import com.civcraft.model.Resident;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

/**
 * Special PvE worlds (spec 04 §11.1, §12): persistence of {@link PveArea}s, entering and leaving,
 * command bans, safe/PvP/boss zones, friendly fire, the "Home" button with a 10 s stand-still.
 */
public final class AreaService implements Listener {

    public static final String COLLECTION = "pve_areas";

    /** Per-area behaviour supplied by the owning module (valley, dungeon). */
    public interface Hooks {
        default void onEnter(Player player) {
        }

        default void onLeave(Player player) {
        }
    }

    /** Rules of one area, read from the owning module's balance section. */
    public static final class Policy {
        final List<String> bannedCommands = new ArrayList<>();
        boolean blockFriends = true;
        double pvpBonus;
        PveArea.ZoneType defaultZone = PveArea.ZoneType.PVP;
        int homeSeconds = 10;
        Hooks hooks = new Hooks() {
        };

        public static Policy from(ConfigurationSection c, Hooks hooks) {
            Policy p = new Policy();
            for (String s : c.getStringList("banned-commands")) p.bannedCommands.add(normalize(s));
            p.blockFriends = c.getBoolean("friends-no-damage", true);
            p.pvpBonus = Math.max(0, c.getDouble("pvp-damage-bonus", 0));
            try {
                p.defaultZone = PveArea.ZoneType.valueOf(c.getString("default-zone", "PVP").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                p.defaultZone = PveArea.ZoneType.PVP;
            }
            p.homeSeconds = Math.max(0, c.getInt("home-seconds", 10));
            if (hooks != null) p.hooks = hooks;
            return p;
        }
    }

    private record Countdown(String area, BlockPos start, int[] remaining) {
    }

    private final CivCraft civ;
    private final Map<String, PveArea> areas = new LinkedHashMap<>();
    private final Map<String, Policy> policies = new HashMap<>();
    private final Map<UUID, BlockPos[]> selections = new HashMap<>();
    private final Map<UUID, PveArea.ZoneType> lastZone = new HashMap<>();
    private final Map<UUID, Countdown> countdowns = new HashMap<>();

    public AreaService(CivCraft civ) {
        this.civ = civ;
    }

    public void load() {
        civ.store().createCollection(COLLECTION);
        for (PveArea a : civ.store().loadAll(COLLECTION, PveArea.class)) areas.put(a.id(), a);
    }

    /** Registers an area kind (creating an empty definition) and its rules. */
    public PveArea register(String id, Policy policy, String configuredWorld, boolean loadWorld) {
        PveArea area = areas.computeIfAbsent(id, PveArea::new);
        if (area.worldName() == null && configuredWorld != null && !configuredWorld.isBlank()) {
            area.worldName(configuredWorld);
        }
        policies.put(id, policy);
        if (loadWorld && area.worldName() != null && Bukkit.getWorld(area.worldName()) == null) {
            java.io.File folder = new java.io.File(Bukkit.getWorldContainer(), area.worldName());
            if (folder.isDirectory()) {
                civ.logger().info("Loading PvE world " + area.worldName() + " for area " + id);
                new WorldCreator(area.worldName()).createWorld();
            } else {
                civ.logger().warning("PvE area '" + id + "': world folder " + area.worldName()
                        + " does not exist; configure it with /civadmin area " + id + " setspawn");
            }
        }
        return area;
    }

    public PveArea area(String id) {
        return areas.get(id);
    }

    public void save(PveArea area) {
        civ.saves().save(COLLECTION, area);
    }

    /** The area whose world the player is in, or null. */
    public PveArea areaOf(World world) {
        if (world == null) return null;
        for (PveArea a : areas.values()) if (a.isIn(world)) return a;
        return null;
    }

    public boolean isIn(Player player, String areaId) {
        PveArea a = areas.get(areaId);
        return a != null && a.isIn(player.getWorld());
    }

    public PveArea.ZoneType zone(Player player) {
        PveArea a = areaOf(player.getWorld());
        if (a == null) return null;
        Policy p = policies.get(a.id());
        return a.zoneAt(player.getLocation(), p == null ? PveArea.ZoneType.PVP : p.defaultZone);
    }

    public PveArea.ZoneType zone(Location location) {
        PveArea a = areaOf(location.getWorld());
        if (a == null) return null;
        Policy p = policies.get(a.id());
        return a.zoneAt(location, p == null ? PveArea.ZoneType.PVP : p.defaultZone);
    }

    // --- entering / leaving -------------------------------------------------------------------------

    /**
     * Teleports the player into the area: remembers where to return, removes all potion effects
     * ("milk") and runs {@code arrive} after a successful teleport.
     */
    public void enter(Player player, String areaId, java.util.function.Consumer<Player> arrive) throws CivException {
        PveArea area = areas.get(areaId);
        Location spawn = area == null ? null : area.spawnLocation();
        CivException.check(spawn != null, "pve.area.not-configured");
        if (areaOf(player.getWorld()) == null) {
            player.getPersistentDataContainer().set(PveKeys.RETURN_POINT, PersistentDataType.STRING,
                    serialize(player.getLocation()));
        }
        player.teleportAsync(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok -> {
            if (!ok || !player.isOnline()) return;
            for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) {
                player.removePotionEffect(effect.getType());
            }
            if (arrive != null) arrive.accept(player);
        });
    }

    /** Sends the player back to where they entered from (or the main world spawn). */
    public void leave(Player player) {
        String raw = player.getPersistentDataContainer().get(PveKeys.RETURN_POINT, PersistentDataType.STRING);
        Location target = raw == null ? null : deserialize(raw);
        if (target == null || areaOf(target.getWorld()) != null) {
            World main = Bukkit.getWorld(civ.settings().mainWorld());
            if (main == null) main = Bukkit.getWorlds().getFirst();
            target = main.getSpawnLocation();
        }
        player.getPersistentDataContainer().remove(PveKeys.RETURN_POINT);
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    private static String serialize(Location l) {
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
    }

    private static Location deserialize(String s) {
        String[] p = s.split(";");
        if (p.length != 6) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- rules ---------------------------------------------------------------------------------------

    private static String normalize(String command) {
        String s = command.trim().toLowerCase(Locale.ROOT);
        while (s.startsWith("/")) s = s.substring(1);
        int space = s.indexOf(' ');
        String first = space < 0 ? s : s.substring(0, space);
        int colon = first.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        return s.replaceAll("\\s+", " ");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        PveArea area = areaOf(player.getWorld());
        if (area == null || player.hasPermission(AdminRegistry.PERMISSION)) return;
        Policy policy = policies.get(area.id());
        if (policy == null) return;
        String msg = normalize(event.getMessage());
        for (String banned : policy.bannedCommands) {
            if (msg.equals(banned) || msg.startsWith(banned + " ")) {
                event.setCancelled(true);
                civ.messages().send(player, "pve.area.command-banned");
                return;
            }
        }
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        PveArea area = areaOf(victim.getWorld());
        if (area == null) return;
        Policy policy = policies.get(area.id());
        if (policy == null) return;
        if (area.zoneAt(victim.getLocation(), policy.defaultZone) == PveArea.ZoneType.SAFE
                || area.zoneAt(attacker.getLocation(), policy.defaultZone) == PveArea.ZoneType.SAFE) {
            event.setCancelled(true);
            civ.messages().actionBar(attacker, "pve.area.safe-zone");
            return;
        }
        if (policy.blockFriends) {
            Resident a = civ.state().resident(attacker);
            Resident v = civ.state().resident(victim);
            if (a != null && v != null && (a.friends().contains(victim.getUniqueId()) || v.friends().contains(attacker.getUniqueId()))) {
                event.setCancelled(true);
                civ.messages().actionBar(attacker, "pve.area.friend");
                return;
            }
        }
        if (policy.pvpBonus > 0) event.setDamage(event.getDamage() + policy.pvpBonus);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) return;
        Player player = event.getPlayer();
        Countdown cd = countdowns.get(player.getUniqueId());
        if (cd != null && !BlockPos.of(event.getTo()).equals(cd.start())) {
            countdowns.remove(player.getUniqueId());
            civ.messages().actionBar(player, "pve.area.home-cancelled");
        }
        PveArea area = areaOf(event.getTo().getWorld());
        if (area == null) return;
        Policy policy = policies.get(area.id());
        PveArea.ZoneType zone = area.zoneAt(event.getTo(), policy == null ? PveArea.ZoneType.PVP : policy.defaultZone);
        PveArea.ZoneType before = lastZone.put(player.getUniqueId(), zone);
        if (before != zone) announceZone(player, zone);
    }

    private void announceZone(Player player, PveArea.ZoneType zone) {
        if (zone == null) return;
        civ.messages().actionBar(player, "pve.area.zone." + zone.name().toLowerCase(Locale.ROOT));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onButton(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        if (!Tag.BUTTONS.isTagged(block.getType())) return;
        PveArea area = areaOf(block.getWorld());
        if (area == null || !area.markers("button").contains(BlockPos.of(block))) return;
        Player player = event.getPlayer();
        Policy policy = policies.get(area.id());
        int seconds = policy == null ? 10 : policy.homeSeconds;
        if (seconds <= 0) {
            leave(player);
            return;
        }
        countdowns.put(player.getUniqueId(), new Countdown(area.id(), BlockPos.of(player.getLocation()), new int[]{seconds}));
        civ.messages().send(player, "pve.area.home-start", Messages.arg("seconds", seconds));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && countdowns.remove(player.getUniqueId()) != null) {
            civ.messages().actionBar(player, "pve.area.home-cancelled");
        }
    }

    /** Called every second by the owning module. */
    public void tick() {
        if (countdowns.isEmpty()) return;
        for (Map.Entry<UUID, Countdown> e : List.copyOf(countdowns.entrySet())) {
            Player player = Bukkit.getPlayer(e.getKey());
            Countdown cd = e.getValue();
            if (player == null || !isIn(player, cd.area())) {
                countdowns.remove(e.getKey());
                continue;
            }
            int left = --cd.remaining()[0];
            if (left <= 0) {
                countdowns.remove(e.getKey());
                leave(player);
            } else {
                civ.messages().actionBar(player, "pve.area.home-countdown", Messages.arg("seconds", left));
            }
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        PveArea from = areaOf(event.getFrom());
        PveArea to = areaOf(player.getWorld());
        if (from != null && from != to) {
            Policy p = policies.get(from.id());
            if (p != null) p.hooks.onLeave(player);
            lastZone.remove(player.getUniqueId());
            if (to == null) player.getPersistentDataContainer().remove(PveKeys.RETURN_POINT);
        }
        if (to != null && to != from) {
            Policy p = policies.get(to.id());
            if (p != null) p.hooks.onEnter(player);
            PveArea.ZoneType zone = zone(player);
            lastZone.put(player.getUniqueId(), zone);
            announceZone(player, zone);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PveArea area = areaOf(player.getWorld());
        if (area == null) return;
        Policy p = policies.get(area.id());
        if (p != null) p.hooks.onEnter(player);
        lastZone.put(player.getUniqueId(), zone(player));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastZone.remove(id);
        countdowns.remove(id);
        selections.remove(id);
    }

    // --- admin commands ------------------------------------------------------------------------------

    public void registerAdmin() {
        LiteralArgumentBuilder<CommandSourceStack> root = Cmd.literal("area");
        root.then(Cmd.arg("area", StringArgumentType.word())
                .suggests(Cmd.suggest(areas::keySet))
                .then(Cmd.literal("info").executes(Cmd.run(ctx -> info(ctx.getSource().getSender(), areaArg(ctx)))))
                .then(Cmd.literal("setspawn").executes(Cmd.player((p, ctx) -> {
                    PveArea a = areaArg(ctx);
                    a.spawn(p.getLocation());
                    save(a);
                    civ.messages().send(p, "pve.admin.area.spawn-set", Messages.arg("area", a.id()));
                })))
                .then(Cmd.literal("pos1").executes(Cmd.player((p, ctx) -> select(p, 0))))
                .then(Cmd.literal("pos2").executes(Cmd.player((p, ctx) -> select(p, 1))))
                .then(Cmd.literal("zone")
                        .then(Cmd.literal("add").then(Cmd.arg("name", StringArgumentType.word())
                                .then(Cmd.arg("type", StringArgumentType.word())
                                        .suggests(Cmd.suggest(() -> List.of("SAFE", "PVP", "BOSS")))
                                        .executes(Cmd.player((p, ctx) -> addZone(p, areaArg(ctx),
                                                StringArgumentType.getString(ctx, "name"),
                                                StringArgumentType.getString(ctx, "type")))))))
                        .then(Cmd.literal("remove").then(Cmd.arg("name", StringArgumentType.word())
                                .executes(Cmd.run(ctx -> {
                                    PveArea a = areaArg(ctx);
                                    String name = StringArgumentType.getString(ctx, "name");
                                    CivException.check(a.zones().removeIf(z -> z.name().equalsIgnoreCase(name)),
                                            "pve.admin.area.no-zone", Messages.arg("name", name));
                                    save(a);
                                    civ.messages().send(ctx.getSource().getSender(), "pve.admin.done");
                                })))))
                .then(Cmd.literal("marker")
                        .then(Cmd.literal("add").then(Cmd.arg("kind", StringArgumentType.word())
                                .suggests(Cmd.suggest(() -> List.of("rat", "chest", "boss", "button")))
                                .executes(Cmd.player((p, ctx) -> {
                                    PveArea a = areaArg(ctx);
                                    CivException.check(a.isIn(p.getWorld()), "pve.admin.area.wrong-world");
                                    String kind = StringArgumentType.getString(ctx, "kind").toLowerCase(Locale.ROOT);
                                    Block target = p.getTargetBlockExact(6);
                                    BlockPos pos = kind.equals("button") || kind.equals("chest")
                                            ? (target == null ? null : BlockPos.of(target)) : BlockPos.of(p.getLocation());
                                    CivException.check(pos != null, "pve.admin.area.look-at-block");
                                    a.addMarker(kind, pos);
                                    save(a);
                                    civ.messages().send(p, "pve.admin.area.marker-added", Messages.arg("kind", kind),
                                            Messages.arg("pos", pos.toString()), Messages.arg("count", a.markers(kind).size()));
                                }))))
                        .then(Cmd.literal("removenear").then(Cmd.arg("kind", StringArgumentType.word())
                                .executes(Cmd.player((p, ctx) -> {
                                    PveArea a = areaArg(ctx);
                                    String kind = StringArgumentType.getString(ctx, "kind").toLowerCase(Locale.ROOT);
                                    BlockPos here = BlockPos.of(p.getLocation());
                                    BlockPos best = null;
                                    for (BlockPos pos : a.markers(kind)) {
                                        if (best == null || pos.distanceSquared(here) < best.distanceSquared(here)) best = pos;
                                    }
                                    CivException.check(best != null && best.distanceSquared(here) <= 36, "pve.admin.area.no-marker");
                                    a.removeMarker(kind, best);
                                    save(a);
                                    civ.messages().send(p, "pve.admin.done");
                                }))))
                        .then(Cmd.literal("clear").then(Cmd.arg("kind", StringArgumentType.word())
                                .executes(Cmd.run(ctx -> {
                                    PveArea a = areaArg(ctx);
                                    a.clearMarkers(StringArgumentType.getString(ctx, "kind").toLowerCase(Locale.ROOT));
                                    save(a);
                                    civ.messages().send(ctx.getSource().getSender(), "pve.admin.done");
                                }))))));
        AdminRegistry.add(root);
    }

    private PveArea areaArg(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) throws CivException {
        String id = StringArgumentType.getString(ctx, "area").toLowerCase(Locale.ROOT);
        PveArea a = areas.get(id);
        CivException.check(a != null, "pve.admin.area.unknown", Messages.arg("area", id));
        return a;
    }

    private void select(Player p, int index) {
        BlockPos[] sel = selections.computeIfAbsent(p.getUniqueId(), k -> new BlockPos[2]);
        sel[index] = BlockPos.of(p.getLocation());
        civ.messages().send(p, "pve.admin.area.pos-set", Messages.arg("index", index + 1), Messages.arg("pos", sel[index].toString()));
    }

    private void addZone(Player p, PveArea a, String name, String typeName) throws CivException {
        BlockPos[] sel = selections.get(p.getUniqueId());
        CivException.check(sel != null && sel[0] != null && sel[1] != null, "pve.admin.area.no-selection");
        CivException.check(a.isIn(p.getWorld()) && sel[0].world().equals(a.worldName()) && sel[1].world().equals(a.worldName()),
                "pve.admin.area.wrong-world");
        PveArea.ZoneType type;
        try {
            type = PveArea.ZoneType.valueOf(typeName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CivException("pve.admin.area.bad-zone-type");
        }
        a.zones().removeIf(z -> z.name().equalsIgnoreCase(name));
        a.zones().add(new PveArea.Zone(name, type, sel[0], sel[1]));
        save(a);
        civ.messages().send(p, "pve.admin.done");
    }

    private void info(org.bukkit.command.CommandSender sender, PveArea a) {
        civ.messages().send(sender, "pve.admin.area.info", Messages.arg("area", a.id()),
                Messages.arg("world", String.valueOf(a.worldName())),
                Messages.arg("spawn", a.spawn() == null ? "—" : a.spawn().toString()),
                Messages.arg("loaded", civ.messages().plain(a.world() != null ? "pve.admin.yes" : "pve.admin.no")));
        for (PveArea.Zone z : a.zones()) {
            civ.messages().sendRaw(sender, "pve.admin.area.zone-line", Messages.arg("name", z.name()),
                    Messages.arg("type", z.type().name()), Messages.arg("bounds", z.bounds()));
        }
        for (Map.Entry<String, List<BlockPos>> e : a.allMarkers().entrySet()) {
            civ.messages().sendRaw(sender, "pve.admin.area.marker-line", Messages.arg("kind", e.getKey()),
                    Messages.arg("count", e.getValue().size()));
        }
    }
}
