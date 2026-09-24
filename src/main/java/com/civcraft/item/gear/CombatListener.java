package com.civcraft.item.gear;

import com.civcraft.item.ItemData;
import com.civcraft.item.ItemKeys;
import com.civcraft.item.ItemRegistry;
import com.civcraft.item.ItemRules;
import com.civcraft.item.def.ItemDef;
import com.civcraft.item.def.ItemKind;
import com.civcraft.item.def.SharpenType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.ToDoubleBiFunction;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * The CivCraft combat formula (spec 04 §5): weapon damage from the item definition (halved without the
 * civ's tech), attack sharpening with its caps, enchantment effects and module hooks; on players the 1.8
 * armor rule and soul armor halving. Runs on the main thread inside the damage event; no NMS.
 */
public final class CombatListener implements Listener {

    private final ItemRegistry registry;
    private final ItemRules rules;
    private final GearService gear;
    private final CombatHooks hooks;
    private final EnchantEffects effects;
    private final BiPredicate<Player, String> techCheck;
    private final Predicate<Player> highTech;
    private final ToDoubleBiFunction<Player, String> civStat;
    private final Realms realms;
    private final int maxAttack;

    public CombatListener(ItemRegistry registry, ItemRules rules, GearService gear, CombatHooks hooks,
                          EnchantEffects effects, BiPredicate<Player, String> techCheck, Predicate<Player> highTech,
                          ToDoubleBiFunction<Player, String> civStat, Realms realms, int maxAttack) {
        this.registry = registry;
        this.rules = rules;
        this.gear = gear;
        this.hooks = hooks;
        this.effects = effects;
        this.techCheck = techCheck;
        this.highTech = highTech;
        this.civStat = civStat;
        this.realms = realms;
        this.maxAttack = maxAttack;
    }

    // ------------------------------------------------------------------------------------ bows

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player) || !(event.getProjectile() instanceof AbstractArrow arrow)) return;
        ItemStack bow = event.getBow();
        ItemDef def = registry.def(bow);
        PersistentDataContainer pdc = arrow.getPersistentDataContainer();
        pdc.set(ItemKeys.ARROW_FORCE, PersistentDataType.FLOAT, event.getForce());
        if (def != null && def.kind() == ItemKind.BOW) {
            pdc.set(ItemKeys.ARROW_BOW, PersistentDataType.STRING, def.id());
            pdc.set(ItemKeys.ARROW_SHARPEN, PersistentDataType.INTEGER, ItemData.sharpen(bow));
            pdc.set(ItemKeys.ARROW_ENCHANTS, PersistentDataType.STRING, encode(ItemData.customEnchants(bow)));
        } else if (bow != null && rules.vanillaBows.containsKey(bow.getType())) {
            pdc.set(ItemKeys.ARROW_VANILLA, PersistentDataType.STRING, bow.getType().name());
            pdc.set(ItemKeys.ARROW_ENCHANTS, PersistentDataType.STRING, encode(ItemData.customEnchants(bow)));
        }
    }

    static String encode(Map<String, Integer> enchants) {
        StringBuilder sb = new StringBuilder();
        enchants.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ",").append(k).append('=').append(v));
        return sb.toString();
    }

    static Map<String, Integer> decode(String value) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (value == null || value.isEmpty()) return map;
        for (String part : value.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                map.put(part.substring(0, eq), Integer.parseInt(part.substring(eq + 1)));
            } catch (NumberFormatException ignored) {
                // malformed entry from an older version
            }
        }
        return map;
    }

    // ------------------------------------------------------------------------------------ damage

    private static boolean isBoss(Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(ItemKeys.WORLD_BOSS);
    }

    /**
     * Attacker side (NORMAL, so module rules at HIGH — mob defence, the world boss — see this damage):
     * weapon damage, sharpening, enchantment effects and attack hooks. The world boss is left to the PvE module.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!rules.combatEnabled || !(event.getEntity() instanceof LivingEntity victim) || isBoss(victim)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        Entity damager = event.getDamager();
        double damage = Double.NaN;

        if (damager instanceof Player attacker && (cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK)) {
            ItemStack weapon = attacker.getInventory().getItemInMainHand();
            damage = melee(attacker, weapon, event);
            if (!Double.isNaN(damage) && cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
                ItemDef def = registry.def(weapon);
                damage += sharpenBonus(attacker, def, ItemData.sharpen(weapon));
                damage = effects.onHit(attacker, victim, ItemData.customEnchants(weapon), false, damage);
                for (CombatHooks.AttackModifier m : hooks.attack) damage = m.apply(attacker, victim, weapon, damage);
            }
        } else if (damager instanceof AbstractArrow arrow && arrow.getShooter() instanceof Player attacker) {
            damage = ranged(attacker, arrow);
            if (!Double.isNaN(damage)) {
                PersistentDataContainer pdc = arrow.getPersistentDataContainer();
                ItemDef bow = registry.get(pdc.get(ItemKeys.ARROW_BOW, PersistentDataType.STRING));
                Integer level = pdc.get(ItemKeys.ARROW_SHARPEN, PersistentDataType.INTEGER);
                damage += sharpenBonus(attacker, bow, level == null ? 0 : level);
                damage = effects.onHit(attacker, victim, decode(pdc.get(ItemKeys.ARROW_ENCHANTS, PersistentDataType.STRING)),
                        true, damage);
                for (CombatHooks.AttackModifier m : hooks.attack) damage = m.apply(attacker, victim, null, damage);
            }
        }
        if (!Double.isNaN(damage)) event.setDamage(Math.max(0, damage));
    }

    /**
     * Victim side (HIGHEST, after every module adjusted the raw damage): the 1.8 armor rule, soul armor
     * halving and defense hooks, converted into the base damage the server's own armor formula turns into
     * the intended result. Hits from the world boss keep the flat damage the PvE module sets.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDefend(EntityDamageByEntityEvent event) {
        if (!rules.combatEnabled || !(event.getEntity() instanceof Player player) || !armorApplies(event.getCause())) return;
        Entity damager = event.getDamager();
        if (isBoss(damager) || (damager instanceof Projectile proj && proj.getShooter() instanceof Entity shooter
                && isBoss(shooter))) {
            return;
        }
        event.setDamage(defend(player, damager, event.getDamage()));
    }

    private static boolean armorApplies(EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || cause == EntityDamageEvent.DamageCause.PROJECTILE;
    }

    /** Base melee damage, or NaN to keep vanilla (fists and items without a CivCraft value). */
    private double melee(Player attacker, ItemStack weapon, EntityDamageByEntityEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return rules.sweepDamage;
        ItemDef def = registry.def(weapon);
        double base;
        boolean isWeapon = true;
        if (def != null) {
            if (!gear.works(def, attacker)) return rules.wrongRealmDamage;
            if (def.kind() == ItemKind.WEAPON) {
                base = withTech(attacker, def, def.gear().damage());
            } else if (def.kind() == ItemKind.TOOL) {
                base = def.gear().damage() > 0 ? withTech(attacker, def, def.gear().damage()) : rules.toolDamage;
            } else {
                base = 1.0;
                isWeapon = false;
            }
        } else {
            Double vanilla = ItemData.empty(weapon) ? null : rules.vanillaWeapons.get(weapon.getType());
            if (vanilla == null) return Double.NaN;
            if (realms.inValley(attacker)) return rules.wrongRealmDamage;
            base = vanilla;
        }
        // The formula replaces the attack attribute, so flat civ damage (talents...) is added back here.
        base += finite(civStat.applyAsDouble(attacker, rules.playerDamageStat));
        if (isWeapon) base += weaponStatBonus(attacker);
        if (event.isCritical()) base *= rules.critMultiplier;
        return base;
    }

    /** Arrow damage at the recorded draw force, or NaN for arrows shot before tagging existed. */
    private double ranged(Player attacker, AbstractArrow arrow) {
        PersistentDataContainer pdc = arrow.getPersistentDataContainer();
        Float force = pdc.get(ItemKeys.ARROW_FORCE, PersistentDataType.FLOAT);
        if (force == null) return Double.NaN;
        String bowId = pdc.get(ItemKeys.ARROW_BOW, PersistentDataType.STRING);
        if (bowId != null) {
            ItemDef def = registry.get(bowId);
            if (def == null || def.gear() == null) return Double.NaN;
            if (!gear.works(def, attacker)) return rules.wrongRealmDamage;
            double full = withTech(attacker, def, def.gear().damage()) + weaponStatBonus(attacker);
            return Math.max(rules.minDamage, CombatMath.arrowDamage(full, force));
        }
        String vanilla = pdc.get(ItemKeys.ARROW_VANILLA, PersistentDataType.STRING);
        Material material = vanilla == null ? null : Material.matchMaterial(vanilla);
        Double full = material == null ? null : rules.vanillaBows.get(material);
        if (full == null) return Double.NaN;
        if (realms.inValley(attacker)) return rules.wrongRealmDamage;
        return Math.max(rules.minDamage, CombatMath.arrowDamage(full + weaponStatBonus(attacker), force));
    }

    /** Civ weapon damage bonus (Технология будущего): not in the valley and not together with soul armor. */
    private double weaponStatBonus(Player attacker) {
        if (realms.inValley(attacker) || gear.anySoulPiece(attacker)) return 0;
        return finite(civStat.applyAsDouble(attacker, rules.weaponDamageStat));
    }

    private static double finite(double v) {
        return Double.isFinite(v) ? v : 0;
    }

    private double withTech(Player player, ItemDef def, double damage) {
        if (def.tech() == null || techCheck.test(player, def.tech())) return damage;
        double noTech = def.gear().noTechDamage();
        return Double.isNaN(noTech) ? Math.max(rules.minDamage, damage * rules.noTechMultiplier) : noTech;
    }

    /** Attack sharpening bonus with the caps of spec 04 §5.2, §5.4 and §6.1. */
    private double sharpenBonus(Player attacker, ItemDef weapon, int level) {
        if (weapon == null || weapon.gear() == null || weapon.gear().sharpen() != SharpenType.ATTACK || level <= 0) return 0;
        int cap = maxAttack;
        boolean soul = gear.fullSoulSet(attacker);
        if (weapon.kind() == ItemKind.WEAPON && !soul && !gear.fullT4Heavy(attacker)) {
            cap = Math.min(cap, rules.swordCapWithoutSet);
        }
        if (soul) cap = Math.min(cap, realms.inDungeon(attacker) ? rules.soulCapDungeon : rules.soulCap);
        int effective = CombatMath.effectiveSharpen(level, highTech.test(attacker), rules.unlockedCap, cap);
        return effective * rules.attackPerLevel;
    }

    /** Converts damage aimed at a player into the event's base damage (1.8 armor, soul armor, hooks). */
    private double defend(Player victim, Entity damager, double damage) {
        double armor = value(victim, Attribute.ARMOR);
        double toughness = value(victim, Attribute.ARMOR_TOUGHNESS);
        double after = CombatMath.afterArmor(damage, armor, rules.armorPerPoint, rules.armorMaxReduction);
        boolean soul = rules.soulHalvingFullSet ? gear.fullSoulSet(victim) : gear.anySoulPiece(victim);
        if (soul && after > rules.soulThreshold) after *= rules.soulMultiplier;
        for (CombatHooks.DefenseModifier m : hooks.defense) after = m.apply(victim, damager, after);
        return CombatMath.baseForVanilla(Math.max(0, after), armor, toughness);
    }

    private static double value(Player player, Attribute attribute) {
        AttributeInstance inst = player.getAttribute(attribute);
        return inst == null ? 0 : inst.getValue();
    }
}
