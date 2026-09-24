package com.civcraft.mob;

import com.civcraft.CivCraft;
import com.civcraft.core.text.Messages;
import com.civcraft.pve.PveKeys;
import com.civcraft.worldevent.WorldEventApi;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Creates custom mobs on vanilla entities (no NMS): PDC tag with type and tier, attributes, name
 * plate with HP and Paper goals. Tracks the currently loaded custom mobs by UUID; entries are removed
 * on {@link EntityRemoveFromWorldEvent}, so unloaded or despawned mobs never leak (legacy bug D.43).
 */
public final class MobService implements Listener {

    private final CivCraft civ;
    private MobConfig config;
    private final Set<UUID> loaded = new HashSet<>();
    private final Map<UUID, Integer> idle = new HashMap<>();

    public MobService(CivCraft civ, MobConfig config) {
        this.civ = civ;
        this.config = config;
    }

    public MobConfig config() {
        return config;
    }

    public void config(MobConfig config) {
        this.config = config;
    }

    // --- identification --------------------------------------------------------------------------

    public static boolean isCustom(Entity e) {
        return e != null && e.getPersistentDataContainer().has(PveKeys.MOB_TYPE, PersistentDataType.STRING);
    }

    public static MobType type(Entity e) {
        if (e == null) return null;
        return MobType.parse(e.getPersistentDataContainer().get(PveKeys.MOB_TYPE, PersistentDataType.STRING));
    }

    public static MobTier tier(Entity e) {
        if (e == null) return null;
        Integer t = e.getPersistentDataContainer().get(PveKeys.MOB_TIER, PersistentDataType.INTEGER);
        return t == null ? null : MobTier.of(t);
    }

    public MobDef def(Entity e) {
        MobType type = type(e);
        MobTier tier = tier(e);
        return type == null || tier == null ? null : config.def(type, tier);
    }

    public Set<UUID> loaded() {
        return loaded;
    }

    // --- spawning --------------------------------------------------------------------------------

    public LivingEntity spawn(MobType type, MobTier tier, Location at) {
        MobDef def = config.def(type, tier);
        World world = at.getWorld();
        if (def == null || world == null || !world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return null;
        Entity entity = world.spawnEntity(at, def.entity(), CreatureSpawnEvent.SpawnReason.CUSTOM, e -> configure(e, def));
        if (!(entity instanceof LivingEntity living) || !entity.isValid()) return null;
        return living;
    }

    private void configure(Entity e, MobDef def) {
        if (!(e instanceof LivingEntity living)) return;
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        pdc.set(PveKeys.MOB_TYPE, PersistentDataType.STRING, def.type().id());
        pdc.set(PveKeys.MOB_TIER, PersistentDataType.INTEGER, def.tier().level());
        double healthPercent = eventValue("mob." + def.type().id() + ".health_percent");
        double damageAdd = eventValue("mob." + def.type().id() + ".damage_add");
        double hp = Math.max(1, def.hp() * (1 + healthPercent));
        setBase(living, Attribute.MAX_HEALTH, hp);
        setBase(living, Attribute.ATTACK_DAMAGE, def.damage() + damageAdd);
        multiplyBase(living, Attribute.MOVEMENT_SPEED, def.speed());
        setBase(living, Attribute.ARMOR, def.armor());
        setBase(living, Attribute.FOLLOW_RANGE, def.followRange());
        setBase(living, Attribute.KNOCKBACK_RESISTANCE, Math.min(1, def.knockbackResistance()));
        if (def.scale() > 0 && Math.abs(def.scale() - 1) > 1e-6) setBase(living, Attribute.SCALE, def.scale());
        setBase(living, Attribute.SPAWN_REINFORCEMENTS, 0);
        living.setHealth(hp);
        living.setRemoveWhenFarAway(def.type() != MobType.RAT);
        living.setCanPickupItems(false);
        EntityEquipment eq = living.getEquipment();
        if (eq != null) {
            eq.clear();
        }
        if (e instanceof Zombie zombie) {
            zombie.setShouldBurnInDay(false);
            zombie.setCanBreakDoors(false);
        }
        if (e instanceof Ageable ageable) {
            if (def.type() == MobType.ANGRY_YOBO) ageable.setBaby();
            else ageable.setAdult();
        }
        living.customName(nameplate(living, def));
        living.setCustomNameVisible(true);
        if (e instanceof Mob mob) applyGoals(mob, def);
    }

    private double eventValue(String key) {
        WorldEventApi events = civ.apiOrNull(WorldEventApi.class);
        return events == null ? 0 : events.global(key);
    }

    private static void setBase(LivingEntity e, Attribute attribute, double value) {
        AttributeInstance a = e.getAttribute(attribute);
        if (a != null) a.setBaseValue(value);
    }

    private static void multiplyBase(LivingEntity e, Attribute attribute, double factor) {
        AttributeInstance a = e.getAttribute(attribute);
        if (a != null) a.setBaseValue(a.getDefaultValue() * factor);
    }

    /** Behemoths are passive: their vanilla target goals are replaced by the slam goal. */
    void applyGoals(Mob mob, MobDef def) {
        if (def.type() != MobType.BEHEMOTH) return;
        var goals = Bukkit.getMobGoals();
        goals.removeAllGoals(mob, GoalType.TARGET);
        if (!goals.hasGoal(mob, BehemothGoal.KEY)) {
            goals.addGoal(mob, 1, new BehemothGoal(civ, this, mob, def));
        }
    }

    // --- name plate ------------------------------------------------------------------------------

    public net.kyori.adventure.text.Component nameplate(LivingEntity e, MobDef def) {
        AttributeInstance max = e.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = max == null ? def.hp() : max.getValue();
        return civ.messages().component("mobs.nameplate",
                Messages.arg("name", civ.messages().component("mobs.name." + def.type().id() + "." + def.tier().id())),
                Messages.arg("hp", String.valueOf((int) Math.ceil(Math.max(0, e.getHealth())))),
                Messages.arg("max", String.valueOf((int) Math.ceil(maxHp))));
    }

    public void refreshName(LivingEntity e) {
        MobDef def = def(e);
        if (def != null && e.isValid()) e.customName(nameplate(e, def));
    }

    // --- registry --------------------------------------------------------------------------------

    @EventHandler
    public void onAdd(EntityAddToWorldEvent event) {
        Entity e = event.getEntity();
        if (!isCustom(e)) return;
        loaded.add(e.getUniqueId());
        MobDef def = def(e);
        if (def == null) {
            // Type removed from the balance file: drop the orphan.
            civ.tasks().nextTick(e::remove);
            return;
        }
        if (def.type() == MobType.BEHEMOTH && e instanceof Mob mob) {
            civ.tasks().nextTick(() -> {
                if (mob.isValid()) applyGoals(mob, def);
            });
        }
    }

    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent event) {
        UUID id = event.getEntity().getUniqueId();
        loaded.remove(id);
        idle.remove(id);
    }

    /** Registers custom mobs that were already loaded when the plugin enabled. */
    public void scanLoaded() {
        for (World w : Bukkit.getWorlds()) {
            for (LivingEntity e : w.getLivingEntities()) {
                if (!isCustom(e)) continue;
                loaded.add(e.getUniqueId());
                MobDef def = def(e);
                if (def == null) e.remove();
                else if (e instanceof Mob mob) applyGoals(mob, def);
            }
        }
    }

    /**
     * Periodic housekeeping (every 5 s): Angry Yobo without a target despawn, mobs that wandered into
     * a town or camp are removed (spec: mobs never enter settlements).
     */
    public void housekeeping(int periodSeconds) {
        for (UUID id : Set.copyOf(loaded)) {
            Entity e = Bukkit.getEntity(id);
            if (e == null || !e.isValid()) {
                loaded.remove(id);
                idle.remove(id);
                continue;
            }
            MobType type = type(e);
            if (type == MobType.RAT) continue;
            if (civ.state().claim(com.civcraft.core.util.ChunkKey.of(e.getLocation())) != null || nearCamp(e.getLocation())) {
                e.remove();
                continue;
            }
            if (type == MobType.ANGRY_YOBO && e instanceof Mob mob) {
                if (mob.getTarget() == null || !mob.getTarget().isValid()) {
                    int t = idle.merge(id, periodSeconds, Integer::sum);
                    if (t >= config.angryIdleSeconds) e.remove();
                } else {
                    idle.remove(id);
                }
            }
        }
    }

    public boolean nearCamp(Location l) {
        if (config.campRadius <= 0) return false;
        long r2 = (long) config.campRadius * config.campRadius;
        for (var camp : civ.state().camps()) {
            var o = camp.origin();
            if (o == null || !o.world().equals(l.getWorld().getName())) continue;
            double dx = o.x() - l.getX();
            double dz = o.z() - l.getZ();
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }
}
