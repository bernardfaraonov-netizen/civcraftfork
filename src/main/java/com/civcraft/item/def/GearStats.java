package com.civcraft.item.def;

import java.util.Set;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Combat numbers of a weapon, bow, armor piece or tool.
 *
 * @param damage       melee damage (weapons) or full-draw arrow damage (bows), HP
 * @param noTechDamage damage when the owner's civ lacks the item's tech; NaN = use the global multiplier
 * @param armor        armor points of this piece (1 point = 4% reduction, 1.8 formula)
 * @param armorClass   armor class or null for non-armor
 * @param slot         armor slot or null
 * @param health       extra max health of this piece, HP
 * @param speed        movement speed change of this piece as a fraction (+0.0125 = +1.25%)
 * @param sharpen      catalysts this item accepts
 * @param durability   max durability (0 = unbreakable / no durability bar)
 * @param deathLoss    share of max durability lost on each death
 * @param repairTier   tier used by the repair formula (0 = not repaired by the formula)
 * @param repairCost   fixed repair price in hundredths, or -1
 * @param realm        world group where the item works
 * @param effects      special on-hit / mining effects (poison, ignite, mob-bonus, crusher-1, crusher-3)
 */
public record GearStats(double damage, double noTechDamage, double armor, ArmorClass armorClass, EquipmentSlot slot,
                        double health, double speed, SharpenType sharpen, int durability, double deathLoss,
                        int repairTier, long repairCost, Realm realm, Set<String> effects) {

    public boolean hasEffect(String effect) {
        return effects.contains(effect);
    }
}
