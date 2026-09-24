package com.civcraft.item.gear;

import com.civcraft.item.def.ItemDef;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Extension points other modules use to add their combat bonuses without touching the formula:
 * the Air Valley (+2 PvP damage), "Технология будущего" (+1 damage, +0.25 armor per piece), the lighthouse,
 * artifacts, the Arsenal's durability protection and similar.
 */
public final class CombatHooks {

    /** Changes outgoing damage of a player (after weapon damage and sharpening). */
    @FunctionalInterface
    public interface AttackModifier {
        double apply(Player attacker, LivingEntity victim, ItemStack weapon, double damage);
    }

    /** Changes incoming damage of a player after armor (towers, artifacts...). */
    @FunctionalInterface
    public interface DefenseModifier {
        double apply(Player victim, Entity damager, double damage);
    }

    /** Extra armor points for one worn piece ({@code def} is null for vanilla armor). */
    @FunctionalInterface
    public interface ArmorBonus {
        double bonus(Player player, ItemStack piece, ItemDef def);
    }

    /** Extra chance (0..1) that equipment keeps its durability on death. */
    @FunctionalInterface
    public interface DurabilitySave {
        double chance(Player player);
    }

    final List<AttackModifier> attack = new CopyOnWriteArrayList<>();
    final List<DefenseModifier> defense = new CopyOnWriteArrayList<>();
    final List<ArmorBonus> armor = new CopyOnWriteArrayList<>();
    final List<DurabilitySave> durability = new CopyOnWriteArrayList<>();

    public void addAttackModifier(AttackModifier modifier) {
        attack.add(modifier);
    }

    public void addDefenseModifier(DefenseModifier modifier) {
        defense.add(modifier);
    }

    /** Call {@code ItemModule.gear().refresh(player)} when the bonus of an online player changes. */
    public void addArmorBonus(ArmorBonus bonus) {
        armor.add(bonus);
    }

    public void addDurabilitySave(DurabilitySave save) {
        durability.add(save);
    }
}
