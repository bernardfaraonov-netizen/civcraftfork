package com.civcraft.religion.artifact;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.Durations;
import com.civcraft.core.util.Money;
import com.civcraft.model.Civilization;
import com.civcraft.model.RelationType;
import com.civcraft.model.Town;
import com.civcraft.religion.ReligionApi;
import com.civcraft.science.CivCommandGraft;
import com.civcraft.science.CivItems;
import com.civcraft.science.CivPerms;
import com.civcraft.science.ResearchApi;
import com.civcraft.science.WonderIndex;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Artifacts (spec 03 §8): definitions, production rules, religion-point purchases, the carry limit,
 * cooldowns, activated and passive effects, keep-on-death.
 */
public final class ArtifactModule implements Module, ArtifactApi, Listener {

    record Potion(PotionEffectType type, int amplifier) {
    }

    record Artifact(String id, String name, Material material, double coins, double hammers, int duration, int cooldown,
                    boolean single, boolean passive, String tech, String wonder, boolean religionPurchase,
                    boolean noHellValley, boolean noSoulArmor, List<Potion> potions, List<String> description,
                    ConfigurationSection config) {
    }

    private CivCraft civ;
    private WonderIndex wonders;
    private final Map<String, Artifact> artifacts = new LinkedHashMap<>();
    /** Player → artifact id → effect end (epoch ms) for timed activated effects. */
    private final Map<UUID, Map<String, Long>> active = new HashMap<>();
    private final Map<UUID, Location> lastPositions = new HashMap<>();
    private final Map<UUID, Long> pickupWarned = new HashMap<>();

    @Override
    public String id() {
        return "artifacts";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("artifacts");
        ConfigurationSection all = civ.balance().section("artifacts", "artifacts");
        for (String id : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(id);
            if (s == null) continue;
            Material m = Material.matchMaterial(s.getString("material", "PAPER"));
            List<Potion> potions = new ArrayList<>();
            for (Map<?, ?> p : s.getMapList("potions")) {
                PotionEffectType type = Registry.MOB_EFFECT.get(NamespacedKey.minecraft(
                        String.valueOf(p.get("type")).toLowerCase(Locale.ROOT)));
                if (type == null) {
                    civ.logger().warning("Artifact " + id + ": unknown potion " + p.get("type"));
                    continue;
                }
                int amp = p.get("amplifier") instanceof Number n ? n.intValue() : 0;
                potions.add(new Potion(type, Math.max(0, amp)));
            }
            ConfigurationSection req = s.getConfigurationSection("requires");
            artifacts.put(id, new Artifact(id, s.getString("name", id), m == null ? Material.PAPER : m,
                    Math.max(0, s.getDouble("coins")), Math.max(0, s.getDouble("hammers")), Math.max(0, s.getInt("duration")),
                    Math.max(0, s.getInt("cooldown")), "single".equalsIgnoreCase(s.getString("uses", "infinite")),
                    s.getBoolean("passive", false), req == null ? null : req.getString("tech"),
                    req == null ? null : req.getString("wonder"), s.getBoolean("religion-purchase", true),
                    s.getBoolean("no-hell-valley", false), s.getBoolean("no-soul-armor", false), potions,
                    s.getStringList("description"), s));
        }
    }

    @Override
    public void enable(CivCraft civ) {
        wonders = new WonderIndex(civ);
        civ.listen(this);
        CivItems items = CivItems.get(civ);
        for (Artifact a : artifacts.values()) {
            List<Component> lore = new ArrayList<>();
            for (String line : a.description()) lore.add(civ.messages().parse("<gray>" + line));
            lore.add(civ.messages().component("artifacts.lore-tag"));
            items.define(a.id(), a.material(), civ.messages().parse("<gold>" + a.name()), lore, 1);
            items.onUse(a.id(), (player, event) -> use(player, event, a));
        }
        long glutton = Math.max(1, civ.balance().getInt("artifacts", "glutton.interval-seconds", 5));
        civ.tasks().timer(20L * glutton, 20L * glutton, this::gluttonTick);
        CivCommandGraft.add(civ, this::command);
    }

    private ConfigurationSection cfg() {
        return civ.balance().file("artifacts");
    }

    // --- ArtifactApi ------------------------------------------------------------------------------

    @Override
    public Set<String> ids() {
        return Collections.unmodifiableSet(artifacts.keySet());
    }

    @Override
    public boolean exists(String id) {
        return artifacts.containsKey(id);
    }

    @Override
    public String name(String id) {
        Artifact a = artifacts.get(id);
        return a == null ? id : a.name();
    }

    @Override
    public double hammers(String id) {
        Artifact a = artifacts.get(id);
        return a == null ? 0 : a.hammers();
    }

    @Override
    public long coinPrice(String id, Town town) {
        Artifact a = artifacts.get(id);
        if (a == null) return 0;
        double coins = a.coins();
        if (town != null && wonders.count(town, wonders.type("mall")) > 0) coins *= 1 - cfg().getDouble("mall-discount", 0.25);
        return Money.ofCoins(coins);
    }

    @Override
    public double religionPrice(String id) {
        Artifact a = artifacts.get(id);
        ReligionApi religion = civ.apiOrNull(ReligionApi.class);
        if (a == null || !a.religionPurchase() || religion == null) return -1;
        return religion.purchasePrice(a.hammers());
    }

    @Override
    public void checkCanProduce(Civilization c, Town town, String id) throws CivException {
        Artifact a = artifacts.get(id);
        if (a == null) throw new CivException("artifacts.error.unknown", Messages.arg("name", id));
        String tavern = cfg().getString("required-structure", "tavern");
        if (wonders.api() != null && tavern != null && !tavern.isEmpty() && wonders.count(town, wonders.type(tavern)) == 0) {
            throw new CivException("artifacts.error.no-tavern");
        }
        if (a.tech() != null) {
            ResearchApi research = civ.apiOrNull(ResearchApi.class);
            if (research != null && !research.hasTech(c, a.tech())) {
                throw new CivException("artifacts.error.tech", Messages.arg("tech", research.techName(a.tech())));
            }
        }
        if (a.wonder() != null && !wonders.owns(c, a.wonder())) {
            throw new CivException("artifacts.error.wonder", Messages.arg("wonder",
                    civ.messages().has("artifacts.wonder." + a.wonder()) ? civ.messages().raw("artifacts.wonder." + a.wonder()) : a.wonder()));
        }
    }

    @Override
    public ItemStack create(String id, boolean singleUse) {
        ItemStack stack = CivItems.get(civ).create(id, 1);
        if (singleUse) CivItems.markSingleUse(stack);
        return stack;
    }

    @Override
    public boolean isActive(Player player, String id) {
        Map<String, Long> map = active.get(player.getUniqueId());
        if (map == null) return false;
        Long until = map.get(id);
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            map.remove(id);
            return false;
        }
        return true;
    }

    @Override
    public boolean carries(Player player, String id) {
        if (!withinLimit(player)) return false;
        for (ItemStack s : player.getInventory().getContents()) {
            if (id.equals(CivItems.id(s))) return true;
        }
        return false;
    }

    @Override
    public int maxCarried(Player player) {
        Civilization c = civ.state().civOf(player);
        if (c != null && wonders.owns(c, "statue_of_liberty")) return cfg().getInt("max-carried-statue-of-liberty", 4);
        return cfg().getInt("max-carried", 3);
    }

    @Override
    public double towerDamageMultiplier(Player player) {
        return isActive(player, "nanoplasts") ? 0.5 : 1.0;
    }

    @Override
    public boolean hiddenFromScouts(Player player) {
        return isActive(player, "invisibility_cap");
    }

    @Override
    public int structureDamageBonus(Player player, Civilization target) {
        if (!carries(player, "engineer")) return 0;
        if (target != null && wonders.owns(target, "council_of_eight")) return 0;
        return combinedBonus(player, "engineer", "conqueror");
    }

    @Override
    public int controlBlockDamageBonus(Player player, Civilization target) {
        if (!carries(player, "conqueror")) return 0;
        return combinedBonus(player, "conqueror", "engineer");
    }

    private int combinedBonus(Player player, String id, String other) {
        Artifact a = artifacts.get(id);
        int damage = a.config().getInt("damage", 1);
        if (carries(player, other) && ThreadLocalRandom.current().nextDouble() >= a.config().getDouble("combined-chance", 0.5)) {
            return 0;
        }
        return damage;
    }

    @Override
    public double deathDurabilitySaveChance(Player player) {
        Artifact a = artifacts.get("reinforced_tools");
        return a != null && carries(player, a.id()) ? a.config().getDouble("death-chance", 0.15) : 0;
    }

    // --- carry limit ------------------------------------------------------------------------------

    int carriedCount(Player player) {
        int n = 0;
        for (ItemStack s : player.getInventory().getContents()) {
            String id = CivItems.id(s);
            if (id != null && artifacts.containsKey(id)) n += s.getAmount();
        }
        return n;
    }

    boolean withinLimit(Player player) {
        return carriedCount(player) <= maxCarried(player);
    }

    private boolean inHellValley(World world) {
        List<String> worlds = cfg().getStringList("hell-valley-worlds");
        if (!worlds.isEmpty()) return worlds.contains(world.getName());
        return world.getEnvironment() == World.Environment.NETHER;
    }

    private boolean wearsSoulArmor(Player player) {
        String prefix = cfg().getString("soul-armor-prefix", "soul_armor");
        for (ItemStack s : player.getInventory().getArmorContents()) {
            String id = CivItems.id(s);
            if (id != null && id.startsWith(prefix)) return true;
        }
        return false;
    }

    // --- activation -------------------------------------------------------------------------------

    private NamespacedKey cooldownKey(String id) {
        return new NamespacedKey("civcraft", "artifact_cd_" + id);
    }

    private void use(Player player, PlayerInteractEvent event, Artifact a) {
        try {
            if (a.passive()) throw new CivException("artifacts.error.passive");
            if (!withinLimit(player)) {
                throw new CivException("artifacts.error.too-many", Messages.arg("max", maxCarried(player)));
            }
            if (a.noHellValley() && inHellValley(player.getWorld())) throw new CivException("artifacts.error.hell-valley");
            if (a.noSoulArmor() && wearsSoulArmor(player)) throw new CivException("artifacts.error.soul-armor");
            long now = System.currentTimeMillis();
            Long readyAt = player.getPersistentDataContainer().get(cooldownKey(a.id()), PersistentDataType.LONG);
            if (readyAt != null && readyAt > now) {
                throw new CivException("artifacts.error.cooldown", Messages.arg("time",
                        Durations.format(Duration.ofMillis(readyAt - now))));
            }
            ItemStack hand = event.getItem();
            if (hand == null) return;
            int ticks = a.duration() * 20;
            for (Potion p : a.potions()) {
                player.addPotionEffect(new PotionEffect(p.type(), ticks, p.amplifier(), false, true, true));
            }
            active.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).put(a.id(), now + a.duration() * 1000L);
            player.getPersistentDataContainer().set(cooldownKey(a.id()), PersistentDataType.LONG, now + a.cooldown() * 1000L);
            boolean consumed = a.single() || CivItems.isSingleUse(hand);
            if (consumed) hand.setAmount(hand.getAmount() - 1);
            civ.messages().send(player, consumed ? "artifacts.used-consumed" : "artifacts.used",
                    Messages.arg("name", a.name()), Messages.arg("time", Durations.format(Duration.ofSeconds(a.duration()))));
        } catch (CivException e) {
            civ.messages().send(player, e.key(), e.args());
        }
    }

    // --- listeners --------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getPlayer();
        Map<String, Long> map = active.get(dead.getUniqueId());
        if (map != null) map.remove("invisibility_cap");
        if (!event.getKeepInventory()) {
            for (var it = event.getDrops().iterator(); it.hasNext(); ) {
                ItemStack s = it.next();
                String id = CivItems.id(s);
                if (id != null && artifacts.containsKey(id)) {
                    it.remove();
                    event.getItemsToKeep().add(s);
                }
            }
        }
        Player killer = dead.getKiller();
        Artifact brand = artifacts.get("assassin_brand");
        if (killer == null || brand == null || !carries(killer, brand.id())) return;
        if (killer.getWorld().getEnvironment() != World.Environment.NORMAL) return;
        Civilization kc = civ.state().civOf(killer);
        Civilization dc = civ.state().civOf(dead);
        if (kc == null || dc == null) return;
        RelationType rel = civ.state().relation(kc.id(), dc.id());
        if (rel != RelationType.WAR && rel != RelationType.HOSTILE) return;
        AttributeInstance max = killer.getAttribute(Attribute.MAX_HEALTH);
        double cap = max == null ? 20 : max.getValue();
        killer.setHealth(Math.min(cap, killer.getHealth() + brand.config().getDouble("heal", 4)));
        civ.messages().actionBar(killer, "artifacts.brand-healed");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker && isActive(attacker, "archer")
                && !inHellValley(attacker.getWorld())) {
            event.setDamage(0);
            return;
        }
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter
                && isActive(shooter, "archer") && !inHellValley(shooter.getWorld())
                && event.getEntity() instanceof LivingEntity target) {
            Artifact a = artifacts.get("archer");
            target.setFireTicks(Math.max(target.getFireTicks(), a.config().getInt("fire-ticks", 100)));
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, a.config().getInt("slowness-ticks", 60),
                    a.config().getInt("slowness-amplifier", 0)));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        Artifact a = artifacts.get("reinforced_tools");
        if (a == null || !carries(event.getPlayer(), a.id())) return;
        double chance = carries(event.getPlayer(), "miner_talisman")
                ? a.config().getDouble("chance-with-talisman", 0.20) : a.config().getDouble("chance", 0.40);
        if (ThreadLocalRandom.current().nextDouble() < chance) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        String id = CivItems.id(event.getItem().getItemStack());
        if (id == null || !artifacts.containsKey(id)) return;
        if (carriedCount(player) + event.getItem().getItemStack().getAmount() <= maxCarried(player)) return;
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        Long last = pickupWarned.get(player.getUniqueId());
        if (last == null || now - last > 5000) {
            pickupWarned.put(player.getUniqueId(), now);
            civ.messages().actionBar(player, "artifacts.error.too-many", Messages.arg("max", maxCarried(player)));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastPositions.remove(id);
        pickupWarned.remove(id);
        Map<String, Long> map = active.get(id);
        if (map != null) {
            map.values().removeIf(until -> until < System.currentTimeMillis());
            if (map.isEmpty()) active.remove(id);
        }
    }

    private void gluttonTick() {
        Artifact a = artifacts.get("glutton_stash");
        if (a == null) return;
        ConfigurationSection g = cfg().getConfigurationSection("glutton");
        double minDist = g == null ? 2.0 : g.getDouble("min-distance", 2.0);
        int food = g == null ? 1 : g.getInt("food", 1);
        float saturation = (float) (g == null ? 1.0 : g.getDouble("saturation", 1.0));
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location now = p.getLocation();
            Location before = lastPositions.put(p.getUniqueId(), now);
            if (before == null || before.getWorld() != now.getWorld() || before.distanceSquared(now) < minDist * minDist) continue;
            if (!carries(p, a.id())) continue;
            p.setFoodLevel(Math.min(20, p.getFoodLevel() + food));
            p.setSaturation(Math.min(p.getFoodLevel(), p.getSaturation() + saturation));
        }
    }

    // --- commands ---------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> command() {
        return Cmd.literal("artifact")
                .executes(Cmd.player((p, ctx) -> new ArtifactMenu(this).open(p)))
                .then(Cmd.literal("list").executes(Cmd.player((p, ctx) -> new ArtifactMenu(this).open(p))))
                .then(Cmd.literal("buy").then(Cmd.arg("id", StringArgumentType.word())
                        .suggests(Cmd.suggest(() -> artifacts.keySet()))
                        .executes(Cmd.player((p, ctx) -> buy(p, StringArgumentType.getString(ctx, "id"))))))
                .then(Cmd.literal("give").requires(Cmd.perm("civcraft.admin"))
                        .then(Cmd.arg("id", StringArgumentType.word()).suggests(Cmd.suggest(() -> artifacts.keySet()))
                                .executes(Cmd.player((p, ctx) -> adminGive(p, StringArgumentType.getString(ctx, "id"), false)))
                                .then(Cmd.literal("single").executes(Cmd.player((p, ctx) ->
                                        adminGive(p, StringArgumentType.getString(ctx, "id"), true))))));
    }

    CivCraft civ() {
        return civ;
    }

    Map<String, Artifact> artifacts() {
        return artifacts;
    }

    /** Instant purchase with religion points (leaders; barracks and tavern in the town). */
    void buy(Player p, String id) throws CivException {
        Artifact a = artifacts.get(id);
        if (a == null) throw new CivException("artifacts.error.unknown", Messages.arg("name", id));
        Civilization c = CivPerms.civOf(civ, p);
        CivPerms.check(civ, p, c, CivPerms.RELIGION_PURCHASE);
        Town town = CivPerms.townOf(civ, p);
        if (!c.id().equals(town.civId())) throw new CivException("error.no-permission");
        if (wonders.api() != null) {
            for (String role : cfg().getStringList("religion-purchase-structures")) {
                if (wonders.count(town, wonders.type(role)) == 0) {
                    throw new CivException("artifacts.error.structure", Messages.arg("structure",
                            civ.messages().has("artifacts.structure." + role) ? civ.messages().raw("artifacts.structure." + role) : role));
                }
            }
        }
        checkCanProduce(c, town, id);
        double price = religionPrice(id);
        if (price < 0) throw new CivException("artifacts.error.no-religion-purchase");
        ReligionApi religion = civ.api(ReligionApi.class);
        if (!religion.spend(c, price)) {
            throw new CivException("artifacts.error.points", Messages.number("cost", price),
                    Messages.number("points", religion.points(c)));
        }
        CivItems.get(civ).give(p, create(id, false));
        CivPerms.tellCiv(civ, c, "artifacts.bought", Messages.arg("player", p.getName()), Messages.arg("name", a.name()),
                Messages.number("cost", price));
    }

    private void adminGive(Player p, String id, boolean single) throws CivException {
        if (!artifacts.containsKey(id)) throw new CivException("artifacts.error.unknown", Messages.arg("name", id));
        CivItems.get(civ).give(p, create(id, single));
    }

    Component requirementText(Artifact a) {
        if (a.wonder() != null) {
            return civ.messages().component("artifacts.req-wonder", Messages.arg("wonder",
                    civ.messages().has("artifacts.wonder." + a.wonder()) ? civ.messages().raw("artifacts.wonder." + a.wonder()) : a.wonder()));
        }
        if (a.tech() != null) {
            ResearchApi research = civ.apiOrNull(ResearchApi.class);
            return civ.messages().component("artifacts.req-tech", Messages.arg("tech",
                    research == null ? a.tech() : research.techName(a.tech())));
        }
        return Component.empty();
    }
}
