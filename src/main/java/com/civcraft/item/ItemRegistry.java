package com.civcraft.item;

import com.civcraft.item.def.ItemDef;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** All custom item definitions and the vanilla material groups used by recipes. */
public final class ItemRegistry {

    private final Map<String, ItemDef> defs = new LinkedHashMap<>();
    private final Map<String, List<Material>> groups = new LinkedHashMap<>();

    public void register(ItemDef def) {
        defs.put(def.id(), def);
    }

    public ItemDef get(String id) {
        return id == null ? null : defs.get(id);
    }

    public boolean exists(String id) {
        return defs.containsKey(id);
    }

    public Collection<ItemDef> all() {
        return Collections.unmodifiableCollection(defs.values());
    }

    /** Definition of a stack, or null for vanilla items and items whose definition was removed. */
    public ItemDef def(ItemStack stack) {
        return get(ItemData.id(stack));
    }

    public Map<String, List<Material>> groups() {
        return groups;
    }

    void clear() {
        defs.clear();
        groups.clear();
    }
}
