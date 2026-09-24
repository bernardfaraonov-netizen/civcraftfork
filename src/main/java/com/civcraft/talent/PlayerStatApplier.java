package com.civcraft.talent;

import com.civcraft.CivCraft;
import com.civcraft.model.Civilization;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;

/**
 * Applies the civ stats {@code player_max_health} and {@code player_damage} (talent 10.2, the
 * Colossus + Chichen Itza ultra buff, trade goods...) to online players as transient attribute modifiers.
 * The values are sums of ADD modifiers in health points / damage points.
 */
public final class PlayerStatApplier implements Listener {

    public static final String MAX_HEALTH = "player_max_health";
    public static final String DAMAGE = "player_damage";

    private static final NamespacedKey HEALTH_KEY = new NamespacedKey("civcraft", "civ_max_health");
    private static final NamespacedKey DAMAGE_KEY = new NamespacedKey("civcraft", "civ_damage");

    private final CivCraft civ;

    public PlayerStatApplier(CivCraft civ) {
        this.civ = civ;
    }

    public void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }

    public void refresh(Player p) {
        Civilization c = civ.state().civOf(p);
        double health = c == null ? 0 : civ.stats().civ(c).get(MAX_HEALTH);
        double damage = c == null ? 0 : civ.stats().civ(c).get(DAMAGE);
        apply(p, Attribute.MAX_HEALTH, HEALTH_KEY, health);
        apply(p, Attribute.ATTACK_DAMAGE, DAMAGE_KEY, damage);
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        if (max != null && p.getHealth() > max.getValue()) p.setHealth(max.getValue());
    }

    private static void apply(Player p, Attribute attribute, NamespacedKey key, double amount) {
        AttributeInstance inst = p.getAttribute(attribute);
        if (inst == null) return;
        if (!Double.isFinite(amount)) amount = 0;
        AttributeModifier existing = inst.getModifier(key);
        if (existing != null && existing.getAmount() == amount) return;
        if (existing != null) inst.removeModifier(key);
        if (amount != 0) {
            inst.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER,
                    EquipmentSlotGroup.ANY));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        civ.tasks().later(20, () -> {
            if (event.getPlayer().isOnline()) refresh(event.getPlayer());
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        civ.tasks().nextTick(() -> {
            if (event.getPlayer().isOnline()) refresh(event.getPlayer());
        });
    }
}
