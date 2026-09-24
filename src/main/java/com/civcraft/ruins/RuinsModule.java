package com.civcraft.ruins;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.command.AdminRegistry;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.BlockPos;
import com.civcraft.pve.Args;
import com.civcraft.pve.Give;
import com.civcraft.pve.PveItems;
import com.civcraft.storage.Stored;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Ruins, lucky blocks and scrolls (spec 04 §15). Ruins are generated with new chunks (one per cell of
 * N×N chunks) or placed by admins; each has one lucky block that is not restored during the phase.
 * Breaking it rolls the lucky loot. Lucky blocks from the world boss are items opened with a right click.
 */
public final class RuinsModule implements Module, Listener {

    static final String RUINS = "ruins";
    static final String STATS = "lucky_stats";

    static final class Ruin implements Stored {
        BlockPos lucky;
        String style;
        boolean broken;
        Instant created;
        UUID brokenBy;

        @Override
        public String storageId() {
            return lucky.toString();
        }
    }

    static final class LuckyStat implements Stored {
        UUID uuid;
        int broken;

        @Override
        public String storageId() {
            return uuid.toString();
        }
    }

    private CivCraft civ;
    private YamlConfiguration cfg;
    private Material luckyMaterial;
    private final Map<BlockPos, Ruin> ruins = new HashMap<>();
    private final Map<UUID, LuckyStat> stats = new HashMap<>();
    private final List<RuinPopulator> populators = new ArrayList<>();
    private RuinItems items;
    private Scrolls scrolls;
    private LuckyLoot loot;

    @Override
    public String id() {
        return "ruins";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("ruins");
        cfg = civ.balance().file("ruins");
        Material m = Material.matchMaterial(cfg.getString("lucky-block.material", "GILDED_BLACKSTONE"));
        luckyMaterial = m != null && m.isBlock() && m.isItem() ? m : Material.GILDED_BLACKSTONE;
        civ.store().createCollection(RUINS);
        civ.store().createCollection(STATS);
        for (Ruin r : civ.store().loadAll(RUINS, Ruin.class)) if (r.lucky != null) ruins.put(r.lucky, r);
        for (LuckyStat s : civ.store().loadAll(STATS, LuckyStat.class)) if (s.uuid != null) stats.put(s.uuid, s);
        items = new RuinItems(civ, cfg, luckyMaterial);
        items.register();
        scrolls = new Scrolls(civ, items, cfg);
        scrolls.register();
        scrolls.load();
    }

    @Override
    public void enable(CivCraft civ) {
        ConfigurationSection lucky = cfg.getConfigurationSection("lucky-block");
        loot = new LuckyLoot(lucky == null ? new YamlConfiguration() : lucky, civ.logger());
        civ.listen(this);
        civ.listen(items);
        civ.listen(scrolls);
        civ.stats().register(scrolls);
        civ.clock().everySecond("ruin-items", items::tick);
        civ.clock().everyMinute("ruin-scrolls", scrolls::minute);
        if (cfg.getBoolean("generation.enabled", true)) {
            List<String> worlds = cfg.getStringList("generation.worlds");
            if (worlds.isEmpty()) worlds = List.of(civ.settings().mainWorld());
            for (String name : worlds) {
                World w = Bukkit.getWorld(name);
                if (w == null) {
                    civ.logger().warning("ruins.yml: world " + name + " is not loaded; no ruins generated there");
                    continue;
                }
                RuinPopulator p = new RuinPopulator(cfg.getInt("generation.cell-chunks", 64),
                        cfg.getDouble("generation.chance-per-cell", 1.0),
                        cfg.getInt("generation.min-distance-from-spawn", 300), luckyMaterial,
                        cfg.getDouble("generation.festive-chance", 0.1));
                w.getPopulators().add(p);
                populators.add(p);
            }
            civ.tasks().timer(20, 20, this::drain);
        }
        registerAdmin();
    }

    @Override
    public void disable(CivCraft civ) {
        for (World w : Bukkit.getWorlds()) w.getPopulators().removeAll(populators);
        drain();
    }

    private void drain() {
        for (RuinPopulator p : populators) {
            BlockPos pos;
            int budget = 500;
            while (budget-- > 0 && (pos = p.poll()) != null) register(pos, "generated");
        }
    }

    private void register(BlockPos pos, String style) {
        if (ruins.containsKey(pos)) return;
        Ruin r = new Ruin();
        r.lucky = pos;
        r.style = style;
        r.created = Instant.now();
        ruins.put(pos, r);
        civ.saves().save(RUINS, r);
    }

    /** Lucky blocks broken by the player (for {@code /res settings}). */
    public int luckyBlocksBroken(UUID player) {
        LuckyStat s = stats.get(player);
        return s == null ? 0 : s.broken;
    }

    // --- lucky blocks ------------------------------------------------------------------------------

    private Ruin intact(Block block) {
        Ruin r = ruins.get(BlockPos.of(block));
        return r != null && !r.broken && block.getType() == luckyMaterial ? r : null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Ruin r = intact(event.getBlock());
        if (r == null) return;
        Player player = event.getPlayer();
        event.setDropItems(false);
        r.broken = true;
        r.brokenBy = player.getUniqueId();
        civ.saves().save(RUINS, r);
        LuckyStat s = stats.computeIfAbsent(player.getUniqueId(), id -> {
            LuckyStat n = new LuckyStat();
            n.uuid = id;
            return n;
        });
        s.broken++;
        civ.saves().save(STATS, s);
        Location at = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        List<ItemStack> drops = loot.roll();
        for (ItemStack stack : drops) at.getWorld().dropItemNaturally(at, stack);
        civ.messages().send(player, "ruins.lucky.opened", Messages.arg("count", drops.size()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> intact(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> intact(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> intact(b) != null)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> intact(b) != null)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (intact(event.getBlock()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUseLuckyItem(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack stack = event.getItem();
        if (!RuinItems.LUCKY.equals(PveItems.id(stack))) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        stack.setAmount(stack.getAmount() - 1);
        List<ItemStack> drops = loot.roll();
        for (ItemStack s : drops) Give.give(player, s);
        civ.messages().send(player, "ruins.lucky.opened", Messages.arg("count", drops.size()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlaceLuckyItem(BlockPlaceEvent event) {
        String id = PveItems.id(event.getItemInHand());
        if (id != null) event.setCancelled(true);
    }

    // --- admin -------------------------------------------------------------------------------------

    private void registerAdmin() {
        AdminRegistry.add(Cmd.literal("ruins")
                .then(Cmd.literal("place").executes(Cmd.player((p, ctx) -> place(p))))
                .then(Cmd.literal("near").then(Cmd.arg("radius", IntegerArgumentType.integer(1, 10000))
                        .executes(Cmd.player((p, ctx) -> near(p, IntegerArgumentType.getInteger(ctx, "radius"))))))
                .then(Cmd.literal("stats").executes(Cmd.run(ctx -> {
                    long broken = ruins.values().stream().filter(r -> r.broken).count();
                    civ.messages().send(ctx.getSource().getSender(), "ruins.admin.stats",
                            Messages.arg("total", ruins.size()), Messages.arg("broken", broken));
                }))));
        AdminRegistry.add(Cmd.literal("pveitem")
                .then(Args.onlinePlayer("player").then(Cmd.arg("item", StringArgumentType.word())
                        .suggests(Cmd.suggest(PveItems::ids))
                        .executes(Cmd.run(ctx -> give(ctx, 1)))
                        .then(Cmd.arg("amount", IntegerArgumentType.integer(1, 64))
                                .executes(Cmd.run(ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))))));
    }

    private void give(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx,
                      int amount) throws CivException {
        Player target = Args.player(ctx, "player");
        String id = StringArgumentType.getString(ctx, "item");
        CivException.check(PveItems.exists(id), "ruins.admin.unknown-item", Messages.arg("id", id));
        for (int i = 0; i < amount; i++) {
            ItemStack s = PveItems.create(id, 1);
            if (s != null) Give.give(target, s);
        }
        civ.messages().send(ctx.getSource().getSender(), "pve.admin.done");
    }

    private void place(Player p) throws CivException {
        World w = p.getWorld();
        RuinBuilder.Sink sink = new RuinBuilder.Sink() {
            @Override
            public Material get(int x, int y, int z) {
                return w.getBlockAt(x, y, z).getType();
            }

            @Override
            public void set(int x, int y, int z, Material material) {
                if (y > w.getMinHeight() && y < w.getMaxHeight()) w.getBlockAt(x, y, z).setType(material, false);
            }

            @Override
            public int surface(int x, int z) {
                return w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            }

            @Override
            public String biome(int x, int y, int z) {
                NamespacedKey key = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME)
                        .getKey(w.getBlockAt(x, y, z).getBiome());
                return key == null ? "" : key.getKey();
            }
        };
        Random rnd = new Random();
        int size = 7 + 2 * rnd.nextInt(3);
        Location l = p.getLocation();
        RuinBuilder.Built built = RuinBuilder.build(sink, l.getBlockX() + 2, l.getBlockZ() + 2, size, luckyMaterial,
                cfg.getDouble("generation.festive-chance", 0.1), rnd);
        CivException.check(built != null, "ruins.admin.bad-ground");
        register(new BlockPos(w.getName(), built.x(), built.y(), built.z()), built.style().name().toLowerCase(java.util.Locale.ROOT));
        civ.messages().send(p, "ruins.admin.placed", Messages.arg("pos", built.x() + " " + built.y() + " " + built.z()));
    }

    private void near(Player p, int radius) {
        BlockPos here = BlockPos.of(p.getLocation());
        long r2 = (long) radius * radius;
        int shown = 0;
        for (Ruin r : ruins.values()) {
            if (!r.lucky.world().equals(here.world()) || r.lucky.distanceSquared(here) > r2) continue;
            civ.messages().sendRaw(p, "ruins.admin.line", Messages.arg("pos", r.lucky.x() + " " + r.lucky.y() + " " + r.lucky.z()),
                    Messages.arg("state", civ.messages().plain(r.broken ? "ruins.admin.broken" : "ruins.admin.intact")));
            if (++shown >= 20) break;
        }
        if (shown == 0) civ.messages().send(p, "ruins.admin.none");
    }
}
