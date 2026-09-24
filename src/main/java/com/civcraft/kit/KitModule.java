package com.civcraft.kit;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.command.AdminRegistry;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.Durations;
import com.civcraft.item.ItemApi;
import com.civcraft.pve.Args;
import com.civcraft.pve.Give;
import com.civcraft.pve.ItemSpec;
import com.civcraft.pve.PveKeys;
import com.civcraft.storage.Stored;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Kits (spec 04 §19, spec 01 §20.2): {@code /kit [name]} with per-kit permission
 * ({@code civcraft.kit.<name>}, granted to the vip/pro/premium groups) and persisted cooldowns, plus
 * the starter kit for new players (soulbound except the fishing rod).
 */
public final class KitModule implements Module, Listener {

    static final String COLLECTION = "kit_cooldowns";

    record Kit(String name, String permission, Duration cooldown, List<ItemSpec> items) {
    }

    static final class Cooldowns implements Stored {
        UUID uuid;
        Map<String, Instant> until = new HashMap<>();

        @Override
        public String storageId() {
            return uuid.toString();
        }
    }

    private CivCraft civ;
    private YamlConfiguration cfg;
    private final Map<String, Kit> kits = new LinkedHashMap<>();
    private final Map<UUID, Cooldowns> cooldowns = new HashMap<>();

    @Override
    public String id() {
        return "kits";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("kits");
        cfg = civ.balance().file("kits");
        ConfigurationSection section = cfg.getConfigurationSection("kits");
        if (section != null) {
            for (String name : section.getKeys(false)) {
                ConfigurationSection k = section.getConfigurationSection(name);
                if (k == null) continue;
                Duration cd = Durations.parse(k.getString("cooldown", "1d"));
                if (cd == null) {
                    civ.logger().warning("kits.yml: bad cooldown for kit " + name);
                    cd = Duration.ofDays(1);
                }
                kits.put(name, new Kit(name, k.getString("permission", "civcraft.kit." + name), cd,
                        ItemSpec.parseList(k.getList("items"), civ.logger(), "kits.yml " + name)));
            }
        }
        civ.store().createCollection(COLLECTION);
        for (Cooldowns c : civ.store().loadAll(COLLECTION, Cooldowns.class)) {
            if (c.uuid != null) {
                if (c.until == null) c.until = new HashMap<>();
                cooldowns.put(c.uuid, c);
            }
        }
    }

    @Override
    public void enable(CivCraft civ) {
        civ.listen(this);
        registerCommands();
        registerAdmin();
    }

    // --- starter kit -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!cfg.getBoolean("starter.enabled", true)) return;
        if (player.getPersistentDataContainer().has(PveKeys.STARTER_KIT)) return;
        if (player.hasPlayedBefore() && cfg.getBoolean("starter.only-new-players", true)) {
            player.getPersistentDataContainer().set(PveKeys.STARTER_KIT, PersistentDataType.BYTE, (byte) 1);
            return;
        }
        player.getPersistentDataContainer().set(PveKeys.STARTER_KIT, PersistentDataType.BYTE, (byte) 1);
        ItemApi items = civ.apiOrNull(ItemApi.class);
        boolean soulbound = cfg.getBoolean("starter.soulbound", true);
        for (ItemSpec spec : ItemSpec.parseList(cfg.getList("starter.items"), civ.logger(), "kits.yml starter")) {
            ItemStack s = spec.build();
            if (s == null) continue;
            if (soulbound && items != null && !spec.flag("no-soulbound")) items.soulbind(s, true);
            Give.give(player, s);
        }
    }

    // --- /kit --------------------------------------------------------------------------------------

    private void registerCommands() {
        LiteralArgumentBuilder<CommandSourceStack> root = Cmd.literal("kit")
                .executes(Cmd.player((p, ctx) -> list(p)))
                .then(Cmd.arg("name", StringArgumentType.word())
                        .suggests(Cmd.suggest(kits::keySet))
                        .executes(Cmd.player((p, ctx) -> claim(p, StringArgumentType.getString(ctx, "name"), false))));
        civ.plugin().getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                e -> e.registrar().register(root.build(), civ.messages().plain("kits.command-description"), List.of("kits")));
    }

    private void list(Player p) {
        civ.messages().send(p, "kits.header");
        for (Kit kit : kits.values()) {
            if (!p.hasPermission(kit.permission())) continue;
            Instant until = cooldownUntil(p.getUniqueId(), kit.name());
            if (until == null) {
                civ.messages().sendRaw(p, "kits.line-ready", Messages.arg("kit", kit.name()));
            } else {
                civ.messages().sendRaw(p, "kits.line-wait", Messages.arg("kit", kit.name()),
                        Messages.arg("time", Durations.format(Duration.between(Instant.now(), until))));
            }
        }
    }

    private Instant cooldownUntil(UUID player, String kit) {
        Cooldowns c = cooldowns.get(player);
        Instant until = c == null ? null : c.until.get(kit);
        return until != null && until.isAfter(Instant.now()) ? until : null;
    }

    private void claim(Player p, String name, boolean force) throws CivException {
        Kit kit = kits.get(name);
        CivException.check(kit != null, "kits.unknown", Messages.arg("kit", name));
        if (!force) {
            CivException.check(p.hasPermission(kit.permission()), "kits.no-permission");
            Instant until = cooldownUntil(p.getUniqueId(), name);
            CivException.check(until == null, "kits.cooldown",
                    Messages.arg("time", Durations.format(Duration.between(Instant.now(), until == null ? Instant.now() : until))));
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemSpec spec : kit.items()) {
            ItemStack s = spec.build();
            if (s == null) continue;
            if (spec.flag("no-repair")) {
                ItemMeta m = s.getItemMeta();
                m.getPersistentDataContainer().set(PveKeys.NO_REPAIR, PersistentDataType.BYTE, (byte) 1);
                s.setItemMeta(m);
            }
            stacks.add(s);
        }
        for (ItemStack s : stacks) Give.give(p, s);
        if (!force) {
            Cooldowns c = cooldowns.computeIfAbsent(p.getUniqueId(), id -> {
                Cooldowns n = new Cooldowns();
                n.uuid = id;
                return n;
            });
            c.until.values().removeIf(t -> t.isBefore(Instant.now()));
            c.until.put(name, Instant.now().plus(kit.cooldown()));
            civ.saves().save(COLLECTION, c);
        }
        civ.messages().send(p, "kits.received", Messages.arg("kit", name));
    }

    private void registerAdmin() {
        AdminRegistry.add(Cmd.literal("kit")
                .then(Cmd.literal("give").then(Args.onlinePlayer("player").then(Cmd.arg("name", StringArgumentType.word())
                        .suggests(Cmd.suggest(kits::keySet))
                        .executes(Cmd.run(ctx -> {
                            claim(Args.player(ctx, "player"), StringArgumentType.getString(ctx, "name"), true);
                            civ.messages().send(ctx.getSource().getSender(), "pve.admin.done");
                        })))))
                .then(Cmd.literal("reset").then(Args.onlinePlayer("player").executes(Cmd.run(ctx -> {
                    Player target = Args.player(ctx, "player");
                    Cooldowns c = cooldowns.remove(target.getUniqueId());
                    if (c != null) civ.saves().delete(COLLECTION, c.storageId());
                    civ.messages().send(ctx.getSource().getSender(), "pve.admin.done");
                })))));
    }
}
