package com.civcraft.mob;

import com.civcraft.pve.ItemSpec;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/**
 * Stats of one mob type at one tier, from {@code balance/mobs.yml}.
 *
 * @param hp        max health (HP units)
 * @param damage    melee damage before armour
 * @param speed     multiplier of the entity's vanilla movement speed
 * @param defence   flat reduction of every incoming hit (rats: 20)
 * @param ignoreArmor melee damage bypasses the victim's armour (rats)
 */
public record MobDef(MobType type, MobTier tier, EntityType entity, double hp, double damage, double speed,
                     double armor, double defence, double scale, double followRange, double knockbackResistance,
                     long coinsMin, long coinsMax, int xp, boolean ignoreArmor, List<ItemSpec> drops,
                     Set<Material> keepVanilla) {
}
