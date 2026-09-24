package com.civcraft.ruins;

import com.civcraft.CivCraft;
import com.civcraft.boss.ValleyApi;
import com.civcraft.core.text.Messages;
import com.civcraft.item.ItemApi;
import com.civcraft.mob.MobApi;
import com.civcraft.pve.PveItems;
import com.civcraft.pve.PveKeys;
import com.civcraft.pve.WarTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Items that come from ruins (spec 04 §15.2–15.4, §7.3): the lucky-block item, ruin weapons and tools,
 * recipe scrolls and their effects. They are PvE items ({@code pve:<id>} in loot tables).
 */
final class RuinItems implements Listener {

    static final String LUCKY = "lucky_block";
    static final List<String> RECIPES = List.of("ballista", "mind_breaker", "princes_knife", "paladin_sword",
            "leveller", "hermes_boots", "turtle_shell");
    private static final NamespacedKey TURTLE = new NamespacedKey("civcraft", "turtle_shell");

    private final CivCraft civ;
    private final ConfigurationSection cfg;
    private final Material luckyMaterial;
    private final Map<UUID, Long> poisonCooldown = new HashMap<>();
    private final Map<UUID, Long> levitationCooldown = new HashMap<>();
    private boolean breaking;

    RuinItems(CivCraft civ, ConfigurationSection cfg, Material luckyMaterial) {
        this.civ = civ;
        this.cfg = cfg;
        this.luckyMaterial = luckyMaterial;
    }

    void register() {
        PveItems.register(LUCKY, n -> make(luckyMaterial, LUCKY, n));
        PveItems.register("ruin_rat_stamen", n -> weapon(Material.IRON_SWORD, "ruin_rat_stamen", 1, Map.of()));
        PveItems.register("ruin_no_library", n -> weapon(Material.IRON_SWORD, "ruin_no_library", 1,
                Map.of(Enchantment.FIRE_ASPECT, 1)));
        PveItems.register("ruin_cleaver", n -> weapon(Material.IRON_SWORD, "ruin_cleaver", 1, Map.of()));
        PveItems.register("ruin_rempty_crusher", n -> weapon(Material.STONE_PICKAXE, "ruin_rempty_crusher", 0, Map.of()));
        PveItems.register("ruin_dragonglass_crusher", n -> weapon(Material.IRON_PICKAXE, "ruin_dragonglass_crusher", 0, Map.of()));
        PveItems.register("ruin_zarngifm_scratcher", n -> weapon(Material.WOODEN_SWORD, "ruin_zarngifm_scratcher", 0, Map.of()));
        PveItems.register("ruin_ternshni_blade", n -> weapon(Material.STONE_SWORD, "ruin_ternshni_blade", 0,
                Map.of(Enchantment.LOOTING, Math.max(1, cfg.getInt("weapons.ternshni-looting", 10)))));
        PveItems.register("ruin_fortune_pick", n -> {
            ItemStack s = weapon(Material.IRON_PICKAXE, "ruin_fortune_pick", 0, Map.of(Enchantment.FORTUNE, 2));
            ItemMeta m = s.getItemMeta();
            m.getPersistentDataContainer().set(PveKeys.NO_REPAIR, PersistentDataType.BYTE, (byte) 1);
            s.setItemMeta(m);
            return s;
        });
        PveItems.register("joke_potato", n -> {
            ItemStack s = make(Material.POTATO, "joke_potato", 1);
            ItemMeta m = s.getItemMeta();
            m.addEnchant(Enchantment.PROTECTION, 5, true);
            m.addEnchant(Enchantment.FIRE_PROTECTION, 5, true);
            m.addEnchant(Enchantment.BLAST_PROTECTION, 5, true);
            s.setItemMeta(m);
            return s;
        });
        for (String r : RECIPES) {
            PveItems.register("recipe_" + r, n -> {
                ItemStack s = make(Material.PAPER, "recipe_" + r, 1);
                ItemMeta m = s.getItemMeta();
                m.setEnchantmentGlintOverride(true);
                m.setMaxStackSize(1);
                s.setItemMeta(m);
                return s;
            });
        }
    }

    ItemStack make(Material material, String id, int amount, TagResolver... args) {
        ItemStack stack = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(civ.messages().component("ruins.item." + id + ".name", args).decoration(TextDecoration.ITALIC, false));
        String loreKey = "ruins.item." + id + ".lore";
        if (civ.messages().has(loreKey)) {
            List<Component> lore = new ArrayList<>();
            for (Component c : civ.messages().lines(loreKey, args)) lore.add(c.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
        }
        meta.getPersistentDataContainer().set(PveKeys.PVE_ITEM, PersistentDataType.STRING, id);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack weapon(Material material, String id, int tier, Map<Enchantment, Integer> enchants) {
        ItemStack s = make(material, id, 1);
        ItemMeta m = s.getItemMeta();
        m.getPersistentDataContainer().set(PveKeys.ITEM_TIER, PersistentDataType.INTEGER, tier);
        enchants.forEach((e, lvl) -> m.addEnchant(e, lvl, true));
        s.setItemMeta(m);
        return s;
    }

    // --- weapon effects ---------------------------------------------------------------------------

    private static String pveId(ItemStack stack) {
        return PveItems.id(stack);
    }

    private static String recipe(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(PveKeys.RECIPE, PersistentDataType.STRING);
    }

    private boolean inValley(Player p) {
        ValleyApi valley = civ.apiOrNull(ValleyApi.class);
        return valley != null && valley.inValley(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        String r = recipe(event.getBow());
        if (r != null && event.getEntity() instanceof Player) {
            event.getProjectile().getPersistentDataContainer().set(PveKeys.ARROW_RECIPE, PersistentDataType.STRING, r);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player shooter) {
            String r = proj.getPersistentDataContainer().get(PveKeys.ARROW_RECIPE, PersistentDataType.STRING);
            if ("mind_breaker".equals(r)) levitate(shooter, target, "recipes.mind-breaker");
            return;
        }
        if (!(event.getDamager() instanceof Player attacker)) return;
        ItemStack hand = attacker.getInventory().getItemInMainHand();
        String id = pveId(hand);
        if (id != null) {
            switch (id) {
                case "ruin_rat_stamen" -> {
                    long now = System.currentTimeMillis();
                    Long next = poisonCooldown.get(target.getUniqueId());
                    if (next == null || next <= now) {
                        int ticks = (int) Math.round(cfg.getDouble("weapons.poison-seconds", 2.5) * 20);
                        target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, ticks, 1));
                        poisonCooldown.put(target.getUniqueId(), now + Math.round(cfg.getDouble("weapons.poison-cooldown-seconds", 5) * 1000));
                    }
                }
                case "ruin_cleaver" -> {
                    MobApi mobs = civ.apiOrNull(MobApi.class);
                    if (mobs != null && mobs.isCustom(target)) {
                        event.setDamage(event.getDamage() + cfg.getDouble("weapons.cleaver-mob-bonus", 3));
                    }
                }
                case "ruin_zarngifm_scratcher" -> levitate(attacker, target, "recipes.mind-breaker");
                default -> {
                }
            }
        }
        String r = recipe(hand);
        if (r == null) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        switch (r) {
            case "princes_knife" -> {
                if (rnd.nextDouble(100) < cfg.getDouble("recipes.princes-knife.chance", 10)) {
                    target.getWorld().strikeLightningEffect(target.getLocation());
                    double extra = cfg.getDouble("recipes.princes-knife.damage", 4);
                    civ.tasks().nextTick(() -> {
                        if (target.isValid()) {
                            target.damage(extra, DamageSource.builder(DamageType.LIGHTNING_BOLT)
                                    .withCausingEntity(attacker).build());
                        }
                    });
                }
            }
            case "leveller" -> {
                boolean mainWorld = attacker.getWorld().getName().equals(civ.settings().mainWorld());
                if (mainWorld && rnd.nextDouble(100) < cfg.getDouble("recipes.leveller.chance", 10)) {
                    int ticks = (int) Math.round(cfg.getDouble("recipes.leveller.seconds", 3) * 20);
                    target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, ticks, 0));
                }
            }
            default -> {
            }
        }
    }

    private void levitate(Player source, LivingEntity target, String path) {
        if (inValley(source) || (target instanceof Player p && inValley(p))) return;
        long now = System.currentTimeMillis();
        Long next = levitationCooldown.get(source.getUniqueId());
        if (next != null && next > now) return;
        if (ThreadLocalRandom.current().nextDouble(100) >= cfg.getDouble(path + ".chance", 20)) return;
        int ticks = (int) Math.round(cfg.getDouble(path + ".seconds", 1.75) * 20);
        target.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, ticks, 0));
        // Soft landing afterwards: falling at a third of the levitation speed (CL V0.6.1).
        civ.tasks().later(ticks, () -> {
            if (target.isValid()) target.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 30, 0));
        });
        levitationCooldown.put(source.getUniqueId(), now + Math.round(cfg.getDouble(path + ".cooldown-seconds", 7.5) * 1000));
    }

    /** Every second: Hermes boots give Speed I; stale cooldowns are dropped. */
    void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if ("hermes_boots".equals(recipe(p.getInventory().getBoots()))) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 50, 0, true, false, true));
            }
        }
        long now = System.currentTimeMillis();
        poisonCooldown.values().removeIf(t -> t <= now);
        levitationCooldown.values().removeIf(t -> t <= now);
    }

    // --- crushers ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (breaking) return;
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        String id = pveId(tool);
        int depth;
        if ("ruin_rempty_crusher".equals(id)) {
            if (WarTime.active()) return;
            depth = 1;
        } else if ("ruin_dragonglass_crusher".equals(id)) {
            depth = Math.max(1, cfg.getInt("weapons.dragonglass-depth", 3));
        } else {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE) return;
        Block origin = event.getBlock();
        BlockFace facing = player.getFacing();
        float pitch = player.getLocation().getPitch();
        boolean vertical = Math.abs(pitch) > 50;
        BlockFace forward = vertical ? (pitch > 0 ? BlockFace.DOWN : BlockFace.UP) : facing;
        List<Block> targets = new ArrayList<>();
        for (int d = 0; d < depth; d++) {
            for (int a = -1; a <= 1; a++) {
                for (int b = -1; b <= 1; b++) {
                    if (d == 0 && a == 0 && b == 0) continue;
                    int dx = forward.getModX() * d;
                    int dy = forward.getModY() * d;
                    int dz = forward.getModZ() * d;
                    if (vertical) {
                        dx += a;
                        dz += b;
                    } else if (forward.getModX() != 0) {
                        dz += a;
                        dy += b;
                    } else {
                        dx += a;
                        dy += b;
                    }
                    targets.add(origin.getRelative(dx, dy, dz));
                }
            }
        }
        breaking = true;
        try {
            for (Block b : targets) {
                Material m = b.getType();
                if (m.isAir() || b.isLiquid() || m.getHardness() < 0 || m.getHardness() > 50 || !b.isPreferredTool(tool)) continue;
                if (player.getInventory().getItemInMainHand().getType().isAir()) break;
                player.breakBlock(b);
            }
        } finally {
            breaking = false;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onAnvil(PrepareAnvilEvent event) {
        ItemStack first = event.getInventory().getFirstItem();
        if (first != null && first.hasItemMeta() && first.getItemMeta().getPersistentDataContainer().has(PveKeys.NO_REPAIR)) {
            event.setResult(null);
        }
    }

    // --- recipe scrolls ---------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onApplyRecipe(InventoryClickEvent event) {
        if (event.getClick() != ClickType.LEFT || !(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getClickedInventory() instanceof PlayerInventory)) return;
        ItemStack cursor = event.getCursor();
        String id = pveId(cursor);
        if (id == null || !id.startsWith("recipe_")) return;
        ItemStack target = event.getCurrentItem();
        if (target == null || target.getType().isAir()) return;
        event.setCancelled(true);
        String recipe = id.substring("recipe_".length());
        String error = check(recipe, target);
        if (error != null) {
            civ.messages().send(player, error);
            return;
        }
        apply(recipe, target);
        event.setCurrentItem(target);
        cursor.setAmount(cursor.getAmount() - 1);
        event.getView().setCursor(cursor.getAmount() <= 0 ? null : cursor);
        civ.messages().send(player, "ruins.recipe.applied",
                Messages.arg("recipe", civ.messages().plain("ruins.item." + id + ".name")));
    }

    private String check(String recipe, ItemStack target) {
        Material m = target.getType();
        String name = m.name();
        boolean ok = switch (recipe) {
            case "ballista", "mind_breaker" -> m == Material.BOW || m == Material.CROSSBOW;
            case "princes_knife", "paladin_sword", "leveller" -> Tag.ITEMS_SWORDS.isTagged(m);
            case "hermes_boots" -> name.endsWith("_BOOTS");
            case "turtle_shell" -> name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS")
                    || name.endsWith("_BOOTS");
            default -> false;
        };
        if (!ok) return "ruins.recipe.wrong-item";
        int required = cfg.getInt("recipes.required-tier", 4);
        Integer tier = target.hasItemMeta()
                ? target.getItemMeta().getPersistentDataContainer().get(PveKeys.ITEM_TIER, PersistentDataType.INTEGER) : null;
        if (required > 0 && (tier == null || tier < required)) return "ruins.recipe.need-tier";
        if (recipe(target) != null) return "ruins.recipe.already";
        return null;
    }

    private void apply(String recipe, ItemStack target) {
        ItemMeta meta = target.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        // All enhancements (catalysts) are removed when a recipe is applied.
        for (String key : cfg.getStringList("recipes.clear-pdc-keys")) {
            NamespacedKey k = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
            if (k != null) pdc.remove(k);
        }
        pdc.set(PveKeys.RECIPE, PersistentDataType.STRING, recipe);
        switch (recipe) {
            case "ballista" -> meta.addEnchant(Enchantment.INFINITY, 1, true);
            case "paladin_sword" -> meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
            case "turtle_shell" -> {
                EquipmentSlotGroup group = slotGroup(target.getType());
                if (meta.getAttributeModifiers() == null || meta.getAttributeModifiers().isEmpty()) {
                    EquipmentSlot slot = target.getType().getEquipmentSlot();
                    target.getType().getDefaultAttributeModifiers(slot).forEach(meta::addAttributeModifier);
                }
                meta.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(TURTLE,
                        cfg.getDouble("recipes.turtle-shell-armor", 0.15), AttributeModifier.Operation.ADD_NUMBER, group));
            }
            default -> {
            }
        }
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(civ.messages().component("ruins.recipe.lore",
                Messages.arg("recipe", civ.messages().plain("ruins.item.recipe_" + recipe + ".name")))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        target.setItemMeta(meta);
        if (recipe.equals("hermes_boots") || recipe.equals("turtle_shell")) {
            ItemApi items = civ.apiOrNull(ItemApi.class);
            if (items != null) items.soulbind(target, true);
        }
    }

    private static EquipmentSlotGroup slotGroup(Material m) {
        String n = m.name();
        if (n.endsWith("_HELMET")) return EquipmentSlotGroup.HEAD;
        if (n.endsWith("_CHESTPLATE")) return EquipmentSlotGroup.CHEST;
        if (n.endsWith("_LEGGINGS")) return EquipmentSlotGroup.LEGS;
        return EquipmentSlotGroup.FEET;
    }
}
