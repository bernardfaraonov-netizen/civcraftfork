package com.civcraft.mob;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.command.AdminRegistry;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.pve.PveModule;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/** Custom mobs, their spawning and ClearLag (spec 04 §10, spec 01 §1). */
public final class MobModule implements Module, MobApi {

    private MobService mobs;
    private ClearLag clearLag;

    @Override
    public String id() {
        return "mobs";
    }

    @Override
    public void load(CivCraft civ) {
        civ.messages().include("mobs");
        mobs = new MobService(civ, new MobConfig(civ.balance().file("mobs"), civ.settings().mainWorld(), civ.logger()));
    }

    @Override
    public void enable(CivCraft civ) {
        civ.listen(mobs);
        civ.listen(new MobCombat(civ, mobs, civ.module(PveModule.class).coins()));
        MobSpawner spawner = new MobSpawner(civ, mobs);
        civ.listen(spawner);
        long interval = mobs.config().spawnIntervalTicks;
        civ.tasks().timer(interval, interval, spawner::run);
        civ.tasks().timer(100, 100, () -> mobs.housekeeping(5));
        clearLag = new ClearLag(civ, mobs);
        clearLag.start();
        mobs.scanLoaded();
        registerAdmin(civ);
    }

    private void registerAdmin(CivCraft civ) {
        var types = Cmd.suggest(() -> Arrays.stream(MobType.values()).map(MobType::id).toList());
        var tiers = Cmd.suggest(() -> Arrays.stream(MobTier.values()).map(MobTier::id).toList());
        AdminRegistry.add(Cmd.literal("mob")
                .then(Cmd.literal("spawn").then(Cmd.arg("type", StringArgumentType.word()).suggests(types)
                        .then(Cmd.arg("tier", StringArgumentType.word()).suggests(tiers)
                                .executes(Cmd.player((p, ctx) -> spawnCmd(civ, p, ctx, 1)))
                                .then(Cmd.arg("count", IntegerArgumentType.integer(1, 50))
                                        .executes(Cmd.player((p, ctx) -> spawnCmd(civ, p, ctx,
                                                IntegerArgumentType.getInteger(ctx, "count"))))))))
                .then(Cmd.literal("killall").then(Cmd.arg("radius", IntegerArgumentType.integer(1, 512))
                        .executes(Cmd.player((p, ctx) -> {
                            int r = IntegerArgumentType.getInteger(ctx, "radius");
                            List<LivingEntity> list = List.copyOf(p.getLocation().getNearbyLivingEntities(r, MobService::isCustom));
                            list.forEach(Entity::remove);
                            civ.messages().send(p, "mobs.admin.killed", Messages.arg("count", list.size()));
                        }))))
                .then(Cmd.literal("info").executes(Cmd.run(ctx -> civ.messages().send(ctx.getSource().getSender(),
                        "mobs.admin.info", Messages.arg("count", mobs.loaded().size()),
                        Messages.arg("biomes", mobs.config().biomes().size()))))));
        AdminRegistry.add(Cmd.literal("clearlag").executes(Cmd.run(ctx -> {
            int removed = clearLag.run();
            civ.messages().send(ctx.getSource().getSender(), "mobs.admin.cleared", Messages.arg("count", removed));
        })));
    }

    private void spawnCmd(CivCraft civ, org.bukkit.entity.Player p,
                          com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx,
                          int count) throws CivException {
        MobType type = MobType.parse(StringArgumentType.getString(ctx, "type"));
        MobTier tier = MobTier.parse(StringArgumentType.getString(ctx, "tier"));
        CivException.check(type != null && tier != null && mobs.config().def(type, tier) != null, "mobs.admin.unknown");
        Location at = p.getLocation();
        int spawned = 0;
        for (int i = 0; i < count; i++) if (mobs.spawn(type, tier, at) != null) spawned++;
        civ.messages().send(p, "mobs.admin.spawned", Messages.arg("count", spawned));
    }

    public MobService service() {
        return mobs;
    }

    @Override
    public LivingEntity spawn(MobType type, MobTier tier, Location at) {
        return mobs.spawn(type, tier, at);
    }

    @Override
    public boolean isCustom(Entity entity) {
        return MobService.isCustom(entity);
    }

    @Override
    public MobType type(Entity entity) {
        return MobService.type(entity);
    }

    @Override
    public MobTier tier(Entity entity) {
        return MobService.tier(entity);
    }

    @Override
    public Instant nextClearLag() {
        return clearLag == null ? null : clearLag.next();
    }
}
