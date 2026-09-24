package com.civcraft.item.gear;

import com.civcraft.core.task.Tasks;
import com.civcraft.item.ItemData;
import com.civcraft.item.ItemKeys;
import com.civcraft.item.ItemRegistry;
import com.civcraft.item.ItemRules;
import com.civcraft.item.def.ArmorClass;
import com.civcraft.item.def.GearStats;
import com.civcraft.item.def.ItemDef;
import com.civcraft.item.def.ItemKind;
import com.civcraft.item.def.Realm;
import com.civcraft.item.def.SharpenType;
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToDoubleBiFunction;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Applies worn CivCraft armor to the player as transient attribute modifiers (spec 04 §5.3–§5.4): armor
 * points, heavy-armor and soul-armor health, defense sharpening health, movement speed, Hermes boots,
 * Turtle shell, soul armor regeneration, realm restrictions (valley gear works only in the valley) and the
 * disabled attack cooldown. Armor items themselves carry no attributes, so every rule lives here.
 */
public final class GearService implements Listener {

    private static final String TURTLE_SHELL = "turtle_shell";
    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET};

    private final ItemRegistry registry;
    private final ItemRules rules;
    private final CombatHooks hooks;
    private final Tasks tasks;
    private final Predicate<Player> highTech;
    private final ToDoubleBiFunction<Player, String> civStat;
    private final Realms realms;
    private final Set<UUID> pending = new HashSet<>();
    private final Set<UUID> regenGiven = new HashSet<>();

    public GearService(ItemRegistry registry, ItemRules rules, CombatHooks hooks, Tasks tasks, Predicate<Player> highTech,
                       ToDoubleBiFunction<Player, String> civStat, Realms realms) {
        this.registry = registry;
        this.rules = rules;
        this.hooks = hooks;
        this.tasks = tasks;
        this.highTech = highTech;
        this.civStat = civStat;
        this.realms = realms;
    }

    /** Whether the gear works where the player is: valley gear only in the Air Valley, other gear only outside. */
    public boolean works(ItemDef def, Player player) {
        if (def == null || def.gear() == null) return true;
        boolean valley = realms.inValley(player);
        return def.gear().realm() == Realm.VALLEY ? valley : !valley;
    }

    /** Custom armor definition worn in the slot, or null. */
    public ItemDef worn(Player player, EquipmentSlot slot) {
        ItemDef def = registry.def(player.getInventory().getItem(slot));
        return def != null && def.kind() == ItemKind.ARMOR && def.gear().slot() == slot ? def : null;
    }

    public boolean fullSet(Player player, Predicate<ItemDef> test) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemDef def = worn(player, slot);
            if (def == null || !test.test(def) || !works(def, player)) return false;
        }
        return true;
    }

    public boolean fullSoulSet(Player player) {
        return fullSet(player, d -> d.gear().armorClass() == ArmorClass.SOUL);
    }

    public boolean anySoulPiece(Player player) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemDef def = worn(player, slot);
            if (def != null && def.gear().armorClass() == ArmorClass.SOUL) return true;
        }
        return false;
    }

    public boolean fullT4Heavy(Player player) {
        return fullSet(player, d -> d.gear().armorClass() == ArmorClass.HEAVY && d.tier() >= 4);
    }

    /** Recomputes the player's gear modifiers on the next tick (coalesces bursts of equipment events). */
    public void refresh(Player player) {
        if (!pending.add(player.getUniqueId())) return;
        tasks.nextTick(() -> {
            pending.remove(player.getUniqueId());
            if (player.isOnline()) recompute(player);
        });
    }

    public void recompute(Player player) {
        Tasks.checkMain();
        boolean dungeon = realms.inDungeon(player);
        boolean valley = realms.inValley(player);
        boolean highTechResearched = highTech.test(player);
        double perPiece = valley ? 0 : finite(civStat.applyAsDouble(player, rules.armorPerPieceStat));
        PlayerInventory inv = player.getInventory();
        double armor = 0;
        double health = 0;
        double speed = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack piece = inv.getItem(slot);
            if (ItemData.empty(piece)) continue;
            ItemDef def = worn(player, slot);
            if (def == null) {
                if (registry.def(piece) == null) {
                    for (CombatHooks.ArmorBonus bonus : hooks.armor) armor += finite(bonus.bonus(player, piece, null));
                }
                continue;
            }
            stripForeignAttributes(piece);
            if (!works(def, player)) continue;
            GearStats gear = def.gear();
            armor += gear.armor();
            if (gear.armorClass() != ArmorClass.SOUL) armor += perPiece;
            if (TURTLE_SHELL.equals(piece.getPersistentDataContainer().get(ItemKeys.RECIPE, PersistentDataType.STRING))) {
                armor += rules.turtleArmor;
            }
            for (CombatHooks.ArmorBonus bonus : hooks.armor) armor += finite(bonus.bonus(player, piece, def));
            health += gear.health();
            if (gear.sharpen() == SharpenType.DEFENSE) {
                int level = CombatMath.effectiveSharpen(ItemData.sharpen(piece), highTechResearched, rules.unlockedCap,
                        Integer.MAX_VALUE);
                health += Math.min(rules.defenseHealthCap, level * rules.defenseHealthPerLevel);
            }
            switch (gear.armorClass()) {
                case HEAVY -> speed += gear.speed() * (dungeon ? rules.dungeonHeavySpeedFactor : 1.0);
                case LIGHT, VALLEY -> speed += gear.speed();
                case SOUL -> { }
            }
        }
        boolean soul = fullSoulSet(player);
        if (soul) health += rules.soulFullSetHealth;
        if (valley) health = 0;

        set(player, Attribute.ARMOR, ItemKeys.MOD_ARMOR, armor, AttributeModifier.Operation.ADD_NUMBER);
        set(player, Attribute.MAX_HEALTH, ItemKeys.MOD_HEALTH, health, AttributeModifier.Operation.ADD_NUMBER);
        set(player, Attribute.MOVEMENT_SPEED, ItemKeys.MOD_SPEED, speed, AttributeModifier.Operation.ADD_SCALAR);
        set(player, Attribute.ATTACK_SPEED, ItemKeys.MOD_ATTACK_SPEED, rules.disableAttackCooldown ? 1020 : 0,
                AttributeModifier.Operation.ADD_NUMBER);
        if (player.getHealth() > maxHealth(player)) player.setHealth(maxHealth(player));
        regeneration(player, soul && rules.soulRegeneration);
    }

    /**
     * CivCraft armor carries no attributes of its own (this service applies them); modules that add item
     * attribute modifiers to an armor piece (the ruin «Turtle shell» recipe copies the base material's
     * vanilla armor) would stack vanilla armor on top, so such modifiers are removed.
     */
    private static void stripForeignAttributes(ItemStack piece) {
        ItemAttributeModifiers mods = piece.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (mods != null && !mods.modifiers().isEmpty()) {
            piece.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes().build());
        }
    }

    private static double maxHealth(Player player) {
        AttributeInstance inst = player.getAttribute(Attribute.MAX_HEALTH);
        return inst == null ? 20 : inst.getValue();
    }

    private static double finite(double v) {
        return Double.isFinite(v) ? v : 0;
    }

    private static void set(Player player, Attribute attribute, NamespacedKey key, double value,
                            AttributeModifier.Operation op) {
        AttributeInstance inst = player.getAttribute(attribute);
        if (inst == null) return;
        AttributeModifier current = inst.getModifier(key);
        if (current != null && current.getAmount() == value && current.getOperation() == op) return;
        if (current != null) inst.removeModifier(key);
        if (value != 0) inst.addTransientModifier(new AttributeModifier(key, value, op));
    }

    private void regeneration(Player player, boolean on) {
        UUID id = player.getUniqueId();
        if (on) {
            PotionEffect current = player.getPotionEffect(PotionEffectType.REGENERATION);
            if (current == null || (current.isInfinite() && current.getAmplifier() == 0)) {
                if (current == null) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, PotionEffect.INFINITE_DURATION,
                            0, true, false, true));
                }
                regenGiven.add(id);
            }
        } else if (regenGiven.remove(id)) {
            PotionEffect current = player.getPotionEffect(PotionEffectType.REGENERATION);
            if (current != null && current.isInfinite() && current.getAmplifier() == 0) {
                player.removePotionEffect(PotionEffectType.REGENERATION);
            }
        }
    }

    /** Safety net run every second: equipment can change in ways that fire no armor event. */
    public void tickAll(Iterable<? extends Player> players) {
        for (Player p : players) recompute(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmor(PlayerArmorChangeEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        regenGiven.remove(event.getPlayer().getUniqueId());
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pending.remove(id);
        if (regenGiven.remove(id)) {
            PotionEffect current = event.getPlayer().getPotionEffect(PotionEffectType.REGENERATION);
            if (current != null && current.isInfinite()) event.getPlayer().removePotionEffect(PotionEffectType.REGENERATION);
        }
    }
}
