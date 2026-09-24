package com.civcraft.item.gear;

import com.civcraft.CivCraft;
import com.civcraft.core.text.Messages;
import com.civcraft.religion.artifact.ArtifactApi;
import com.civcraft.item.ItemData;
import com.civcraft.item.ItemRegistry;
import com.civcraft.item.ItemRenderer;
import com.civcraft.item.ItemRules;
import com.civcraft.item.def.ItemDef;
import com.civcraft.item.def.ItemFlag;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Death rules (spec 04 §8.2, §7.1, §3.3): SoulBound items, artifacts, units flagged keep-on-death and
 * CivCraft gear stay in the inventory; gear loses a share of its max durability instead of dropping and is
 * destroyed at zero. Protective chances (Arsenal, trade resources, the «Укреплённые инструменты» artifact)
 * add up; one roll per death protects all equipment.
 */
public final class DeathListener implements Listener {

    private final ItemRegistry registry;
    private final ItemRenderer renderer;
    private final ItemRules rules;
    private final CombatHooks hooks;
    private final Messages messages;
    private final CivCraft civ;

    public DeathListener(CivCraft civ, ItemRegistry registry, ItemRenderer renderer, ItemRules rules, CombatHooks hooks,
                         Messages messages) {
        this.civ = civ;
        this.registry = registry;
        this.renderer = renderer;
        this.rules = rules;
        this.hooks = hooks;
        this.messages = messages;
    }

    /** Summed chance that equipment keeps its durability on this death. */
    public double saveChance(Player player) {
        double chance = 0;
        for (CombatHooks.DurabilitySave save : hooks.durability) {
            double c = save.chance(player);
            if (Double.isFinite(c) && c > 0) chance += c;
        }
        ArtifactApi artifacts = civ.apiOrNull(ArtifactApi.class);
        if (artifacts != null) {
            double c = artifacts.deathDurabilitySaveChance(player);
            if (Double.isFinite(c) && c > 0) chance += c;
        } else {
            for (Map.Entry<String, Double> e : rules.artifactSaveChance.entrySet()) {
                if (has(player, e.getKey())) chance += e.getValue();
            }
        }
        return Math.min(1, chance);
    }

    private boolean has(Player player, String id) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (id.equals(ItemData.id(stack))) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) return;
        Player player = event.getEntity();
        boolean saved = ThreadLocalRandom.current().nextDouble() < saveChance(player);
        PlayerInventory inv = player.getInventory();
        List<ItemStack> drops = event.getDrops();
        boolean lost = false;
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack live = inv.getItem(slot);
            if (ItemData.empty(live)) continue;
            ItemDef def = registry.def(live);
            boolean keep = ItemData.soulbound(live) || (def != null && def.keptOnDeath());
            if (!keep) continue;
            ItemStack snapshot = live.clone();
            drops.remove(snapshot);
            if (def != null && def.isGear() && !saved && !def.has(ItemFlag.UNBREAKABLE)
                    && !def.has(ItemFlag.VANILLA_DURABILITY) && live.hasData(DataComponentTypes.MAX_DAMAGE)) {
                int max = live.getData(DataComponentTypes.MAX_DAMAGE);
                int loss = (int) Math.ceil(max * def.gear().deathLoss());
                int damage = live.getDataOrDefault(DataComponentTypes.DAMAGE, 0) + loss;
                if (loss > 0) lost = true;
                if (damage >= max) {
                    inv.setItem(slot, null);
                    messages.send(player, "items.death.destroyed", Messages.arg("item", renderer.name(def)));
                    continue;
                }
                live.setData(DataComponentTypes.DAMAGE, damage);
                renderer.render(live, def);
            }
            event.getItemsToKeep().add(live.clone());
        }
        if (saved) messages.send(player, "items.death.saved");
        else if (lost) messages.send(player, "items.death.durability-lost");
    }
}
