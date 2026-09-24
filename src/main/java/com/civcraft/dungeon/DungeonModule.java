package com.civcraft.dungeon;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.command.AdminRegistry;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.BlockPos;
import com.civcraft.mob.ClearLagEvent;
import com.civcraft.mob.MobModule;
import com.civcraft.mob.MobService;
import com.civcraft.mob.MobTier;
import com.civcraft.mob.MobType;
import com.civcraft.pve.AreaService;
import com.civcraft.pve.Args;
import com.civcraft.pve.PveArea;
import com.civcraft.pve.PveKeys;
import com.civcraft.pve.PveModule;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Dungeon / sewer world (spec 04 §12): entry with cooldown, blindness and effect removal, the shared
 * valley rules (banned commands, friends, safe zones, Home button), Depth Strider disabled, and rats
 * that live on admin-placed markers — one rat per marker, respawned 60 s after death and immediately
 * after every ClearLag.
 */
public final class DungeonModule implements Module, DungeonApi, Listener {

    public static final String AREA = "dungeon";
    private static final NamespacedKey NO_WATER_WALK = new NamespacedKey("civcraft", "dungeon_no_depth_strider");

    private CivCraft civ;
    private AreaService areas;
    private YamlConfiguration cfg;
    /** Marker index → living rat. Bounded by the number of markers. */
    private final Map<Integer, UUID> rats = new HashMap<>();
    /** Marker index → earliest respawn time (millis). */
    private final Map<Integer, Long> respawnAt = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public String id() {
        return "dungeon";
    }

    @Override
    public void load(CivCraft civ) {
        civ.messages().include("dungeon");
        cfg = civ.balance().file("dungeon");
    }

    @Override
    public void enable(CivCraft civ) {
        this.civ = civ;
        this.areas = civ.module(PveModule.class).areas();
        AreaService.Policy policy = AreaService.Policy.from(cfg.getConfigurationSection("rules") == null
                ? new YamlConfiguration() : cfg.getConfigurationSection("rules"), new AreaService.Hooks() {
            @Override
            public void onEnter(Player player) {
                if (cfg.getBoolean("rules.disable-depth-strider", true)) disableWaterWalking(player);
            }

            @Override
            public void onLeave(Player player) {
                restoreWaterWalking(player);
            }
        });
        areas.register(AREA, policy, cfg.getString("world", "civ_dungeon"), cfg.getBoolean("load-world", true));
        civ.listen(this);
        int period = Math.max(1, cfg.getInt("rats.check-seconds", 5));
        civ.tasks().timer(20L * period, 20L * period, this::spawnRats);
        registerAdmin();
    }

    @Override
    public void disable(CivCraft civ) {
        for (UUID id : rats.values()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        rats.clear();
    }

    // --- entry -------------------------------------------------------------------------------------

    @Override
    public void enter(Player player) throws CivException {
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(player.getUniqueId());
        if (until != null && until > now) {
            throw new CivException("dungeon.cooldown", Messages.arg("seconds", (until - now + 999) / 1000));
        }
        CivException.check(!inDungeon(player), "dungeon.already-inside");
        cooldowns.values().removeIf(t -> t <= now);
        cooldowns.put(player.getUniqueId(), now + 1000L * Math.max(0, cfg.getInt("teleport-cooldown-seconds", 30)));
        int blind = (int) Math.round(cfg.getDouble("blindness-seconds", 3) * 20);
        areas.enter(player, AREA, p -> {
            if (blind > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blind, 0));
            civ.messages().send(p, "dungeon.entered");
        });
    }

    @Override
    public boolean inDungeon(Player player) {
        return areas.isIn(player, AREA);
    }

    private void disableWaterWalking(Player player) {
        AttributeInstance a = player.getAttribute(Attribute.WATER_MOVEMENT_EFFICIENCY);
        if (a == null || a.getModifier(NO_WATER_WALK) != null) return;
        a.addTransientModifier(new AttributeModifier(NO_WATER_WALK, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                EquipmentSlotGroup.ANY));
    }

    private void restoreWaterWalking(Player player) {
        AttributeInstance a = player.getAttribute(Attribute.WATER_MOVEMENT_EFFICIENCY);
        if (a != null) a.removeModifier(NO_WATER_WALK);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // The modifier is transient; nothing is persisted. Keep the cooldown map small.
        long now = System.currentTimeMillis();
        cooldowns.values().removeIf(t -> t <= now);
    }

    // --- rats --------------------------------------------------------------------------------------

    private void spawnRats() {
        PveArea area = areas.area(AREA);
        if (area == null || area.world() == null) return;
        MobService mobs = civ.module(MobModule.class).service();
        List<BlockPos> markers = area.markers("rat");
        int max = Math.max(0, cfg.getInt("rats.max-alive", 60));
        long now = System.currentTimeMillis();
        rats.entrySet().removeIf(e -> {
            Entity rat = Bukkit.getEntity(e.getValue());
            return rat == null || !rat.isValid() || e.getKey() >= markers.size();
        });
        for (int i = 0; i < markers.size() && rats.size() < max; i++) {
            if (rats.containsKey(i) || respawnAt.getOrDefault(i, 0L) > now) continue;
            BlockPos pos = markers.get(i);
            var world = area.world();
            if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) continue;
            LivingEntity rat = mobs.spawn(MobType.RAT, MobTier.LESSER,
                    new org.bukkit.Location(world, pos.x() + 0.5, pos.y(), pos.z() + 0.5));
            if (rat == null) continue;
            // Not saved with the chunk: an unloaded marker simply respawns its rat when loaded again.
            rat.setPersistent(false);
            rat.getPersistentDataContainer().set(PveKeys.RAT_MARKER, PersistentDataType.INTEGER, i);
            rats.put(i, rat.getUniqueId());
        }
    }

    @EventHandler
    public void onRatGone(EntityRemoveFromWorldEvent event) {
        Entity e = event.getEntity();
        Integer marker = e.getPersistentDataContainer().get(PveKeys.RAT_MARKER, PersistentDataType.INTEGER);
        if (marker == null || !e.getUniqueId().equals(rats.get(marker))) return;
        rats.remove(marker);
        if (e instanceof LivingEntity l && l.isDead()) {
            int seconds = Math.max(0, cfg.getInt("rats.respawn-seconds", 60));
            int jitter = Math.max(0, cfg.getInt("rats.respawn-jitter-seconds", 10));
            respawnAt.put(marker, System.currentTimeMillis() + 1000L * (seconds + (jitter > 0 ? ThreadLocalRandom.current().nextInt(jitter + 1) : 0)));
        }
    }

    @EventHandler
    public void onClearLag(ClearLagEvent event) {
        // "Right after ClearLag the rats always respawn" (CL V1.10.1).
        respawnAt.clear();
        civ.tasks().nextTick(this::spawnRats);
    }

    // --- admin -------------------------------------------------------------------------------------

    private void registerAdmin() {
        AdminRegistry.add(Cmd.literal("dungeon")
                .then(Cmd.literal("tp").then(Args.onlinePlayer("player")
                        .executes(Cmd.run(ctx -> {
                            Player target = Args.player(ctx, "player");
                            cooldowns.remove(target.getUniqueId());
                            enter(target);
                            civ.messages().send(ctx.getSource().getSender(), "pve.admin.done");
                        }))))
                .then(Cmd.literal("respawnrats").executes(Cmd.run(ctx -> {
                    respawnAt.clear();
                    spawnRats();
                    civ.messages().send(ctx.getSource().getSender(), "dungeon.admin.rats", Messages.arg("count", rats.size()));
                }))));
    }
}
