package com.civcraft.fishing;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.pve.Give;
import com.civcraft.pve.ItemSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Fishing loot (spec 04 §10.4 "Рыбалка"): the vanilla catch is replaced by independently rolled
 * drops; several can come at once (the first replaces the hooked item, the rest go to the inventory).
 * With no hit the fallback item is caught. Handled at HIGH with ignoreCancelled (legacy bug B.30 ran on
 * MONITOR and duplicated rewards).
 */
public final class FishingModule implements Module, Listener {

    private boolean enabled;
    private final Set<String> worlds = new HashSet<>();
    private List<ItemSpec> drops = List.of();
    private ItemSpec fallback;

    @Override
    public String id() {
        return "fishing";
    }

    @Override
    public void load(CivCraft civ) {
        YamlConfiguration cfg = civ.balance().file("fishing");
        enabled = cfg.getBoolean("enabled", true);
        worlds.addAll(cfg.getStringList("worlds"));
        drops = ItemSpec.parseList(cfg.getList("drops"), civ.logger(), "fishing.yml drops");
        List<ItemSpec> fb = ItemSpec.parseList(cfg.getList("fallback"), civ.logger(), "fishing.yml fallback");
        fallback = fb.isEmpty() ? null : fb.getFirst();
    }

    @Override
    public void enable(CivCraft civ) {
        if (enabled) civ.listen(this);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)) return;
        if (!worlds.isEmpty() && !worlds.contains(event.getPlayer().getWorld().getName())) return;
        List<ItemStack> rolled = new ArrayList<>();
        for (ItemSpec spec : drops) {
            if (!spec.roll()) continue;
            ItemStack s = spec.build();
            if (s != null) rolled.add(s);
        }
        if (rolled.isEmpty()) {
            if (fallback == null) return;
            ItemStack s = fallback.build();
            if (s != null) caught.setItemStack(s);
            return;
        }
        caught.setItemStack(rolled.getFirst());
        for (int i = 1; i < rolled.size(); i++) Give.give(event.getPlayer(), rolled.get(i));
    }
}
