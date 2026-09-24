package com.civcraft.item.enhance;

import java.util.Set;

/**
 * An enchantment offered by a library, a wonder or a ruin scroll (spec 04 §7).
 *
 * @param id           catalog id (e.g. {@code efficiency_3}, {@code lightning}, {@code scroll_ballista})
 * @param vanillaKey   vanilla enchantment key, or null for a custom effect
 * @param customEffect custom effect id (lightning, roots, soulbound...), or null
 * @param level        level applied
 * @param targets      item classes it can go on
 * @param source       library, wonder, scroll or loot
 * @param incompatible catalog ids / custom effects that exclude this one
 * @param soulbound    also makes the item SoulBound (Hermes boots, Turtle shell)
 * @param overOthers   can be applied on top of other enchantments of the same kind (Feather Falling IV)
 * @param libraryLevel library level that offers it (0 = not a library enchantment)
 * @param setPrice     library: price to list it, hundredths
 * @param setHammers   library: hammers to list it
 * @param applyPrice   library: price per application, hundredths
 * @param wonder       wonder id that offers it, or null
 * @param wonderPrice  wonder: price per application, hundredths
 */
public record EnchantDef(String id, String vanillaKey, String customEffect, int level, Set<String> targets,
                         String source, Set<String> incompatible, boolean soulbound, boolean overOthers,
                         int libraryLevel, long setPrice, int setHammers, long applyPrice, String wonder,
                         long wonderPrice) {

    public boolean isVanilla() {
        return vanillaKey != null;
    }

    /** Key used for comparing levels: the vanilla key or the custom effect id. */
    public String effectKey() {
        return vanillaKey != null ? vanillaKey : customEffect;
    }
}
