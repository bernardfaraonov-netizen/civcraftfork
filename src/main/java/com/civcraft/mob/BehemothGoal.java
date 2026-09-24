package com.civcraft.mob;

import com.civcraft.CivCraft;
import com.civcraft.model.Resident;
import com.civcraft.pve.PveKeys;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import java.util.EnumSet;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * Behemoth ability (spec 04 §10.2): while angry, every 6 / 5.5 / 5 / 4.5 s it hits every player within
 * 5.75 blocks; 30 % of slams are strong (more damage and knockback). Knockback factor 1.315. The mob is
 * passive — it only becomes angry at a player who hits it, for a limited time.
 */
final class BehemothGoal implements Goal<Mob> {

    static final GoalKey<Mob> KEY = GoalKey.of(Mob.class, new NamespacedKey("civcraft", "behemoth_slam"));

    private final CivCraft civ;
    private final MobService mobs;
    private final Mob mob;
    private final MobDef def;
    private int cooldown;

    BehemothGoal(CivCraft civ, MobService mobs, Mob mob, MobDef def) {
        this.civ = civ;
        this.mobs = mobs;
        this.mob = mob;
        this.def = def;
        this.cooldown = intervalTicks();
    }

    private int intervalTicks() {
        double seconds = mobs.config().slamIntervalSeconds[def.tier().level() - 1];
        return Math.max(10, (int) Math.round(seconds * 20));
    }

    private boolean angry() {
        Long until = mob.getPersistentDataContainer().get(PveKeys.AGGRO_UNTIL, PersistentDataType.LONG);
        return until != null && until > System.currentTimeMillis() && mob.getTarget() instanceof Player;
    }

    @Override
    public boolean shouldActivate() {
        return angry();
    }

    @Override
    public boolean shouldStayActive() {
        return angry();
    }

    @Override
    public void stop() {
        if (mob.getTarget() != null && !angry()) mob.setTarget(null);
    }

    @Override
    public void tick() {
        if (--cooldown > 0) return;
        cooldown = intervalTicks();
        MobConfig c = mobs.config();
        boolean strong = ThreadLocalRandom.current().nextDouble(100) < c.strongChance;
        AttributeInstance attack = mob.getAttribute(Attribute.ATTACK_DAMAGE);
        double damage = (attack == null ? def.damage() : attack.getValue()) * (strong ? c.strongMultiplier : 1);
        double push = c.knockback * (strong ? c.strongMultiplier : 1);
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, strong ? 0.6f : 0.9f);
        mob.getWorld().spawnParticle(Particle.SWEEP_ATTACK, mob.getLocation().add(0, 1, 0), 8, c.slamRadius / 2, 0.3, c.slamRadius / 2);
        DamageSource source = DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(mob).withDirectEntity(mob).build();
        for (LivingEntity near : mob.getLocation().getNearbyLivingEntities(c.slamRadius)) {
            if (!(near instanceof Player player) || player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR) continue;
            Resident r = civ.state().resident(player);
            if (r != null && r.isPvpProtected()) continue;
            double before = player.getHealth();
            player.damage(damage, source);
            if (player.getHealth() >= before && player.getNoDamageTicks() <= 0) continue; // cancelled by protection
            Vector dir = player.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
            if (dir.lengthSquared() < 1e-4) dir = new Vector(ThreadLocalRandom.current().nextDouble(-1, 1), 0, ThreadLocalRandom.current().nextDouble(-1, 1));
            dir.normalize().multiply(0.5 * push).setY(0.35 * push);
            player.setVelocity(player.getVelocity().add(dir));
        }
    }

    @Override
    public GoalKey<Mob> getKey() {
        return KEY;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.UNKNOWN_BEHAVIOR);
    }
}
