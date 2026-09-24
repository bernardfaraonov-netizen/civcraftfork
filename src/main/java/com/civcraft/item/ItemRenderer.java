package com.civcraft.item;

import com.civcraft.core.text.Format;
import com.civcraft.core.text.Messages;
import com.civcraft.item.def.ArmorClass;
import com.civcraft.item.def.GearStats;
import com.civcraft.item.def.ItemDef;
import com.civcraft.item.def.ItemFlag;
import com.civcraft.item.def.ItemKind;
import com.civcraft.item.def.Realm;
import com.civcraft.item.def.SharpenType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Builds item stacks from definitions using 1.21 data components and renders their name and lore
 * (static definition lines plus per-stack sharpening, enchantments, SoulBound and durability).
 */
public final class ItemRenderer {

    /** Lang keys whose text ends up in item lore; their hash is part of the render revision. */
    private static final List<String> LORE_KEYS = List.of("items.lore.header", "items.lore.damage",
            "items.lore.bow-damage", "items.lore.armor", "items.lore.health", "items.lore.speed", "items.lore.tech",
            "items.lore.sharpen-attack", "items.lore.sharpen-defense", "items.lore.soulbound", "items.lore.durability",
            "items.lore.realm-valley", "items.lore.no-repair", "items.lore.single-use", "items.lore.scroll",
            "items.lore.enchant", "items.lore.keep-on-death");

    private final ItemRegistry registry;
    private final Messages messages;
    private final Map<String, String> rarityColors;
    private final Function<String, String> techName;
    private final int salt;

    public ItemRenderer(ItemRegistry registry, Messages messages, Map<String, String> rarityColors,
                        Function<String, String> techName) {
        this.registry = registry;
        this.messages = messages;
        this.rarityColors = rarityColors;
        this.techName = techName;
        StringBuilder sb = new StringBuilder();
        for (String key : LORE_KEYS) sb.append(messages.has(key) ? messages.raw(key) : key).append('\n');
        rarityColors.forEach((k, v) -> sb.append(k).append(v));
        this.salt = sb.toString().hashCode();
    }

    public int revision(ItemDef def) {
        return def.revision(salt);
    }

    // ------------------------------------------------------------------------------------ creation

    public ItemStack create(ItemDef def, int amount) {
        ItemStack stack = ItemStack.of(def.material());
        applyStatic(stack, def);
        for (Map.Entry<String, Integer> e : def.customEnchants().entrySet()) {
            ItemData.customEnchant(stack, e.getKey(), e.getValue());
        }
        render(stack, def);
        stack.setAmount(Math.max(1, Math.min(def.stack(), amount)));
        return stack;
    }

    private void applyStatic(ItemStack stack, ItemDef def) {
        if (def.model() != null) stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(def.model()));
        if (def.modelData() != null) {
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(def.modelData()));
        }
        if (def.glint()) stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        GearStats gear = def.gear();
        if (gear != null && gear.durability() > 0) {
            stack.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
            stack.setData(DataComponentTypes.MAX_DAMAGE, gear.durability());
            stack.setData(DataComponentTypes.DAMAGE, 0);
        } else if (gear == null || !def.has(ItemFlag.VANILLA_DURABILITY)) {
            if (stack.hasData(DataComponentTypes.MAX_DAMAGE) && def.stack() > 1) {
                stack.unsetData(DataComponentTypes.MAX_DAMAGE);
                stack.unsetData(DataComponentTypes.DAMAGE);
            }
            stack.setData(DataComponentTypes.MAX_STACK_SIZE, def.stack());
        }
        if (def.has(ItemFlag.UNBREAKABLE)) stack.setData(DataComponentTypes.UNBREAKABLE);
        if (def.color() >= 0) stack.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(Color.fromRGB(def.color())));

        // Remove vanilla behaviour that would let the item act as its base material.
        if (def.kind() != ItemKind.ARMOR && stack.hasData(DataComponentTypes.EQUIPPABLE)) {
            stack.unsetData(DataComponentTypes.EQUIPPABLE);
        }
        if (!def.has(ItemFlag.CONSUMABLE)) {
            if (stack.hasData(DataComponentTypes.CONSUMABLE)) stack.unsetData(DataComponentTypes.CONSUMABLE);
            if (stack.hasData(DataComponentTypes.FOOD)) stack.unsetData(DataComponentTypes.FOOD);
        }
        if (stack.hasData(DataComponentTypes.ENCHANTABLE)) stack.unsetData(DataComponentTypes.ENCHANTABLE);
        if (stack.hasData(DataComponentTypes.REPAIRABLE)) stack.unsetData(DataComponentTypes.REPAIRABLE);

        // Custom attributes: only weapons carry attack damage; armor stats are applied by the gear service.
        ItemAttributeModifiers.Builder attributes = ItemAttributeModifiers.itemAttributes();
        if (gear != null && def.kind() == ItemKind.WEAPON) {
            attributes.addModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(ItemKeys.MOD_WEAPON,
                    Math.max(0, gear.damage() - 1), AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        }
        stack.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, attributes.build());
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.DYED_COLOR,
                        DataComponentTypes.UNBREAKABLE).build());

        for (Map.Entry<String, Integer> e : def.enchants().entrySet()) {
            Enchantment enchantment = enchantment(e.getKey());
            if (enchantment != null) stack.addUnsafeEnchantment(enchantment, e.getValue());
        }
        int rev = revision(def);
        stack.editPersistentDataContainer(pdc -> {
            pdc.set(ItemKeys.ITEM, PersistentDataType.STRING, def.id());
            pdc.set(ItemKeys.REVISION, PersistentDataType.INTEGER, rev);
            if (gear != null) pdc.set(ItemKeys.TIER, PersistentDataType.INTEGER, def.tier());
        });
    }

    public static Enchantment enchantment(String key) {
        NamespacedKey k = NamespacedKey.fromString(key);
        if (k == null) return null;
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(k);
    }

    /**
     * Returns an up-to-date copy of a custom stack whose definition changed since it was made (keeping
     * sharpening, enchantments, SoulBound, renames and wear), or null when the stack is current or vanilla.
     */
    public ItemStack refreshed(ItemStack stack) {
        ItemDef def = registry.def(stack);
        if (def == null || ItemData.revision(stack) == revision(def)) return null;
        ItemStack fresh = create(def, stack.getAmount());
        ItemData.customEnchants(stack).forEach((k, v) -> ItemData.customEnchant(fresh, k, v));
        // Keep every other piece of per-stack data (sharpening, SoulBound, single use, ruin recipe, keys of
        // other modules); the fresh id/revision/tier win.
        fresh.editPersistentDataContainer(pdc -> stack.getPersistentDataContainer().copyTo(pdc, false));
        stack.getEnchantments().forEach((e, lvl) -> {
            if (fresh.getEnchantmentLevel(e) < lvl) fresh.addUnsafeEnchantment(e, lvl);
        });
        if (stack.hasData(DataComponentTypes.CUSTOM_NAME)) {
            fresh.setData(DataComponentTypes.CUSTOM_NAME, stack.getData(DataComponentTypes.CUSTOM_NAME));
        }
        if (stack.hasData(DataComponentTypes.DAMAGE) && fresh.hasData(DataComponentTypes.MAX_DAMAGE)) {
            int max = fresh.getData(DataComponentTypes.MAX_DAMAGE);
            fresh.setData(DataComponentTypes.DAMAGE, Math.min(max - 1, stack.getData(DataComponentTypes.DAMAGE)));
        }
        render(fresh, def);
        return fresh;
    }

    // ------------------------------------------------------------------------------------ rendering

    public Component name(ItemDef def) {
        return messages.parse(color(def) + def.name()).decoration(TextDecoration.ITALIC, false);
    }

    public String color(ItemDef def) {
        return rarityColors.getOrDefault(def.rarity(), "<white>");
    }

    /** Re-renders name and lore of a custom stack from its definition and per-stack data. */
    public void render(ItemStack stack) {
        ItemDef def = registry.def(stack);
        if (def != null) render(stack, def);
    }

    public void render(ItemStack stack, ItemDef def) {
        int sharpen = ItemData.sharpen(stack);
        Component name = name(def);
        if (sharpen > 0) name = name.append(Component.text(" +" + sharpen));
        stack.setData(DataComponentTypes.ITEM_NAME, name);

        List<Component> lore = new ArrayList<>();
        String categoryName = messages.has("items.category." + def.category())
                ? messages.raw("items.category." + def.category()) : def.category();
        lore.add(line("items.lore.header", Messages.arg("category", messages.parse(categoryName)),
                Messages.arg("tier", def.tier() > 0 ? "T" + def.tier() : "—")));
        for (String l : def.lore()) lore.add(messages.parse(l).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));

        GearStats gear = def.gear();
        if (gear != null) {
            if (def.kind() == ItemKind.WEAPON) {
                lore.add(line("items.lore.damage", Messages.number("value", gear.damage())));
            } else if (def.kind() == ItemKind.BOW) {
                lore.add(line("items.lore.bow-damage", Messages.number("value", gear.damage())));
            }
            if (def.kind() == ItemKind.ARMOR) {
                lore.add(line("items.lore.armor", Messages.number("value", gear.armor())));
                if (gear.health() > 0) lore.add(line("items.lore.health", Messages.number("value", gear.health())));
                if (gear.speed() != 0 && gear.armorClass() != ArmorClass.SOUL) {
                    lore.add(line("items.lore.speed", Messages.arg("value", Format.signedPercent(gear.speed()))));
                }
            }
            if (sharpen > 0) {
                lore.add(line(gear.sharpen() == SharpenType.DEFENSE ? "items.lore.sharpen-defense" : "items.lore.sharpen-attack",
                        Messages.arg("level", sharpen)));
            }
            if (gear.realm() == Realm.VALLEY) lore.add(line("items.lore.realm-valley"));
        }
        addEnchantLines(stack, lore);
        String recipe = stack.getPersistentDataContainer().get(ItemKeys.RECIPE, PersistentDataType.STRING);
        if (recipe != null) {
            String key = "ruins.item.recipe_" + recipe + ".name";
            lore.add(line("items.lore.scroll", Messages.arg("name",
                    messages.has(key) ? messages.component(key) : Component.text(recipe))));
        }
        if (def.tech() != null) lore.add(line("items.lore.tech", Messages.arg("tech", techName.apply(def.tech()))));
        if (ItemData.soulbound(stack) || def.has(ItemFlag.SOULBOUND)) {
            lore.add(line("items.lore.soulbound"));
        } else if (def.has(ItemFlag.KEEP_ON_DEATH)) {
            lore.add(line("items.lore.keep-on-death"));
        }
        if (def.has(ItemFlag.NO_REPAIR)) lore.add(line("items.lore.no-repair"));
        if (def.singleUse() || ItemData.singleUse(stack)) lore.add(line("items.lore.single-use"));
        if (gear != null && stack.hasData(DataComponentTypes.MAX_DAMAGE) && !def.has(ItemFlag.UNBREAKABLE)) {
            int max = stack.getData(DataComponentTypes.MAX_DAMAGE);
            int damage = stack.getDataOrDefault(DataComponentTypes.DAMAGE, 0);
            lore.add(line("items.lore.durability", Messages.arg("current", max - damage), Messages.arg("max", max)));
        }
        stack.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
    }

    /** Lore for vanilla stacks that carry CivCraft data (SoulBound starter tools, wonder enchantments). */
    public void renderVanilla(ItemStack stack) {
        List<Component> lore = new ArrayList<>();
        addEnchantLines(stack, lore);
        if (ItemData.soulbound(stack)) lore.add(line("items.lore.soulbound"));
        if (lore.isEmpty()) {
            if (stack.hasData(DataComponentTypes.LORE)) stack.unsetData(DataComponentTypes.LORE);
        } else {
            stack.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        }
    }

    private void addEnchantLines(ItemStack stack, List<Component> lore) {
        for (Map.Entry<String, Integer> e : ItemData.customEnchants(stack).entrySet()) {
            String key = "items.enchant-name." + e.getKey();
            Component enchantName = messages.has(key) ? messages.component(key) : Component.text(e.getKey());
            lore.add(line("items.lore.enchant", Messages.arg("name", enchantName),
                    Messages.arg("level", e.getValue() > 1 ? " " + roman(e.getValue()) : "")));
        }
    }

    private Component line(String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... args) {
        return messages.component(key, args).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }
}
