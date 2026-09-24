package com.civcraft.boss;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.command.AdminRegistry;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.text.Messages;
import com.civcraft.pve.AreaService;
import com.civcraft.pve.Args;
import com.civcraft.pve.ItemSpec;
import com.civcraft.pve.PveArea;
import com.civcraft.pve.PveKeys;
import com.civcraft.pve.PveModule;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** The Air valley world and its world boss (spec 04 §11). */
public final class ValleyModule implements Module, ValleyApi, Listener {

    public static final String AREA = "valley";

    private CivCraft civ;
    private YamlConfiguration cfg;
    private AreaService areas;
    private ValleyStats stats;
    private WorldBoss boss;
    private ValleyChests chests;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public String id() {
        return "valley";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("valley");
        cfg = civ.balance().file("valley");
        areas = civ.module(PveModule.class).areas();
        stats = new ValleyStats(civ);
        stats.load();
        boss = new WorldBoss(civ, areas, stats, section("boss"));
        chests = new ValleyChests(civ, areas, section("chests"), boss.times());
        chests.load();
    }

    private ConfigurationSection section(String path) {
        ConfigurationSection s = cfg.getConfigurationSection(path);
        return s == null ? new YamlConfiguration() : s;
    }

    @Override
    public void enable(CivCraft civ) {
        areas.register(AREA, AreaService.Policy.from(section("rules"), null),
                cfg.getString("world", "civ_valley"), cfg.getBoolean("load-world", true));
        civ.listen(this);
        civ.listen(boss);
        civ.listen(chests);
        civ.clock().everyMinute("valley-boss", boss::minute);
        civ.clock().everyMinute("valley-chests", chests::minute);
        civ.clock().everySecond("valley-boss-tick", boss::second);
        registerCommands();
        registerAdmin();
    }

    @Override
    public void disable(CivCraft civ) {
        boss.remove();
    }

    // --- API ---------------------------------------------------------------------------------------

    @Override
    public void enter(Player player) throws CivException {
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(player.getUniqueId());
        if (until != null && until > now) {
            throw new CivException("valley.cooldown", Messages.arg("seconds", (until - now + 999) / 1000));
        }
        CivException.check(!inValley(player), "valley.already-inside");
        checkArmor(player);
        int min = Math.max(0, cfg.getInt("teleport-cooldown-seconds.min", 30));
        int max = Math.max(min, cfg.getInt("teleport-cooldown-seconds.max", 45));
        cooldowns.values().removeIf(t -> t <= now);
        cooldowns.put(player.getUniqueId(), now + 1000L * (min + ThreadLocalRandom.current().nextInt(max - min + 1)));
        areas.enter(player, AREA, p -> civ.messages().send(p, "valley.entered"));
    }

    private void checkArmor(Player player) throws CivException {
        ConfigurationSection armor = cfg.getConfigurationSection("required-armor");
        if (armor == null) return;
        Map<EquipmentSlot, String> slots = Map.of(EquipmentSlot.HEAD, "helmet", EquipmentSlot.CHEST, "chestplate",
                EquipmentSlot.LEGS, "leggings", EquipmentSlot.FEET, "boots");
        for (Map.Entry<EquipmentSlot, String> e : slots.entrySet()) {
            List<String> ids = armor.getStringList(e.getValue());
            if (ids.isEmpty()) continue;
            ItemStack worn = player.getInventory().getItem(e.getKey());
            boolean ok = false;
            for (String id : ids) {
                if (ItemSpec.matches(worn, id)) {
                    ok = true;
                    break;
                }
            }
            CivException.check(ok, "valley.need-armor");
        }
    }

    @Override
    public boolean inValley(Player player) {
        return areas.isIn(player, AREA);
    }

    @Override
    public Instant nextBossSpawn() {
        return boss.nextSpawn();
    }

    @Override
    public Instant nextChestFill() {
        return chests.nextFill();
    }

    @Override
    public boolean bossAlive() {
        return boss.alive();
    }

    // --- rules -------------------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY && event.getCaught() instanceof Player
                && inValley(event.getPlayer())) {
            event.setCancelled(true);
            event.getHook().remove();
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHookDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof FishHook && event.getEntity() instanceof Player p && inValley(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        if (!inValley(victim)) return;
        if (boss.counting()) {
            stats.death(victim.getUniqueId(), victim.getName());
            Player killer = victim.getKiller();
            if (killer != null && !killer.equals(victim)) stats.kill(killer.getUniqueId(), killer.getName());
        }
        if (areas.zone(victim) != PveArea.ZoneType.PVP) return;
        // Soul shards always drop in the PvP zone (CL V1.10.4), even if they would otherwise be kept.
        String shard = cfg.getString("soul-shard-id", "mat_soul_shard");
        List<ItemStack> moved = new ArrayList<>();
        event.getItemsToKeep().removeIf(stack -> {
            if (ItemSpec.matches(stack, shard)) {
                moved.add(stack);
                return true;
            }
            return false;
        });
        if (event.getKeepInventory()) {
            ItemStack[] contents = victim.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                if (ItemSpec.matches(contents[i], shard)) {
                    moved.add(contents[i]);
                    victim.getInventory().setItem(i, null);
                }
            }
            for (ItemStack s : moved) victim.getWorld().dropItemNaturally(victim.getLocation(), s);
        } else {
            for (ItemStack s : moved) if (!event.getDrops().contains(s)) event.getDrops().add(s);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvpDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !inValley(victim) || !boss.counting()) return;
        Player attacker = event.getDamager() instanceof Player p ? p
                : event.getDamager() instanceof org.bukkit.entity.Projectile proj && proj.getShooter() instanceof Player p2 ? p2 : null;
        if (attacker != null && !attacker.equals(victim)) {
            stats.damage(attacker.getUniqueId(), attacker.getName(), event.getFinalDamage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (boss.inBossZone(p)) p.getPersistentDataContainer().set(PveKeys.LEFT_IN_BOSS_ZONE, PersistentDataType.BYTE, (byte) 1);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (!p.getPersistentDataContainer().has(PveKeys.LEFT_IN_BOSS_ZONE)) return;
        p.getPersistentDataContainer().remove(PveKeys.LEFT_IN_BOSS_ZONE);
        PveArea area = areas.area(AREA);
        if (area != null && area.isIn(p.getWorld()) && area.spawnLocation() != null) {
            p.teleportAsync(area.spawnLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    // --- commands ----------------------------------------------------------------------------------

    private void registerCommands() {
        LiteralArgumentBuilder<CommandSourceStack> root = Cmd.literal("valley")
                .executes(Cmd.run(ctx -> help(ctx.getSource().getSender())))
                .then(Cmd.literal("boss").executes(Cmd.run(ctx -> boss.describe(ctx.getSource().getSender()))))
                .then(Cmd.literal("top")
                        .executes(Cmd.run(ctx -> top(ctx.getSource().getSender(), "kills")))
                        .then(Cmd.arg("kind", StringArgumentType.word())
                                .suggests(Cmd.suggest(() -> List.of("kills", "deaths", "kd", "damage", "civs")))
                                .executes(Cmd.run(ctx -> top(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "kind"))))));
        civ.plugin().getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                e -> e.registrar().register(root.build(), civ.messages().plain("valley.command-description"), List.of("boss")));
    }

    private void help(CommandSender sender) {
        civ.messages().sendRaw(sender, "valley.help");
    }

    private void top(CommandSender sender, String kind) throws CivException {
        int limit = Math.max(1, cfg.getInt("top-size", 10));
        if (kind.equals("civs")) {
            civ.messages().sendRaw(sender, "valley.top.header-civs");
            int i = 1;
            for (ValleyStats.CivStat s : stats.topCivs(limit)) {
                civ.messages().sendRaw(sender, "valley.top.line", Messages.arg("n", i++), Messages.arg("name", s.name),
                        Messages.arg("value", s.wins));
            }
            return;
        }
        CivException.check(List.of("kills", "deaths", "kd", "damage").contains(kind), "valley.top.unknown");
        civ.messages().sendRaw(sender, "valley.top.header-" + kind);
        int i = 1;
        for (ValleyStats.PlayerStat s : stats.top(kind, limit)) {
            String value = switch (kind) {
                case "deaths" -> String.valueOf(s.deaths);
                case "kd" -> Format.number(s.kd());
                case "damage" -> Format.number(s.damage);
                default -> String.valueOf(s.kills);
            };
            civ.messages().sendRaw(sender, "valley.top.line", Messages.arg("n", i++), Messages.arg("name", s.name),
                    Messages.arg("value", value));
        }
    }

    private void registerAdmin() {
        AdminRegistry.add(Cmd.literal("valley")
                .then(Cmd.literal("tp").then(Args.onlinePlayer("player").executes(Cmd.run(ctx -> {
                    Player target = Args.player(ctx, "player");
                    cooldowns.remove(target.getUniqueId());
                    areas.enter(target, AREA, p -> civ.messages().send(p, "valley.entered"));
                    civ.messages().send(ctx.getSource().getSender(), "pve.admin.done");
                }))))
                .then(Cmd.literal("fillchests").executes(Cmd.run(ctx -> civ.messages().send(ctx.getSource().getSender(),
                        "valley.admin.filled", Messages.arg("count", chests.fill())))))
                .then(Cmd.literal("resetstats").executes(Cmd.run(ctx -> {
                    stats.reset();
                    civ.messages().send(ctx.getSource().getSender(), "pve.admin.done");
                }))));
        AdminRegistry.add(Cmd.literal("boss")
                .then(Cmd.literal("spawn").executes(Cmd.run(ctx -> {
                    CivException.check(!boss.alive(), "valley.admin.boss-alive");
                    CivException.check(boss.spawn(), "valley.admin.boss-failed");
                    civ.messages().send(ctx.getSource().getSender(), "pve.admin.done");
                })))
                .then(Cmd.literal("kill").executes(Cmd.run(ctx -> {
                    boss.remove();
                    civ.messages().send(ctx.getSource().getSender(), "pve.admin.done");
                }))));
    }
}
