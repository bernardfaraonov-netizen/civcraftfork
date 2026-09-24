package com.civcraft.item;

import org.bukkit.entity.Player;

/** Optional: opens the custom-item recipe book GUI (implemented by the item module). */
public interface RecipeBookApi {

    void openRecipeBook(Player player);
}
