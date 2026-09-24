package com.civcraft.plot;

import static com.civcraft.resident.CmdKit.arg;
import static com.civcraft.resident.CmdKit.lit;
import static com.civcraft.resident.CmdKit.word;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.chat.Channels;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.economy.Amounts;
import com.civcraft.economy.Ledger;
import com.civcraft.model.Claim;
import com.civcraft.model.Civilization;
import com.civcraft.model.PlotPerm;
import com.civcraft.model.PlotSubject;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.resident.CmdKit;
import com.civcraft.resident.Lookup;
import com.civcraft.storage.Stored;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Plots (spec §7.4): owners, groups, the permission matrix, sale and purchase, mob and fire toggles.
 * Rights are always checked against the town that owns the chunk (audit C-31). The plot value used
 * for the property tax is stored separately from the sale price, so negative or fake prices cannot
 * erase taxes (audit C-32).
 */
public final class PlotModule implements Module, Listener {

    public static final String COLLECTION = "plot_values";

    /** Value of a plot (last purchase or claim price), the base of the property tax. */
    public static final class PlotValue implements Stored {
        private ChunkKey chunk;
        private long value;

        private PlotValue() {
        }

        PlotValue(ChunkKey chunk, long value) {
            this.chunk = chunk;
            this.value = value;
        }

        @Override
        public String storageId() {
            return chunk.toString();
        }
    }

    private static final Set<CreatureSpawnEvent.SpawnReason> NATURAL = EnumSet.of(CreatureSpawnEvent.SpawnReason.NATURAL,
            CreatureSpawnEvent.SpawnReason.PATROL, CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION,
            CreatureSpawnEvent.SpawnReason.REINFORCEMENTS, CreatureSpawnEvent.SpawnReason.JOCKEY,
            CreatureSpawnEvent.SpawnReason.MOUNT, CreatureSpawnEvent.SpawnReason.TRAP, CreatureSpawnEvent.SpawnReason.RAID);

    private CivCraft civ;
    private final Map<ChunkKey, PlotValue> values = new HashMap<>();

    @Override
    public String id() {
        return "plot";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("plot");
        civ.store().createCollection(COLLECTION);
        for (PlotValue v : civ.store().loadAll(COLLECTION, PlotValue.class)) {
            if (civ.state().claim(v.chunk) != null) values.put(v.chunk, v);
            else civ.saves().delete(COLLECTION, v.storageId());
        }
    }

    @Override
    public void enable(CivCraft civ) {
        civ.listen(this);
        CmdKit.register(civ, this::tree, "Plot commands", List.of("p"));
    }

    // --- values ---------------------------------------------------------------------------------

    /** Property-tax base of the claim (hundredths): purchase price, else its claim price. */
    public long value(Claim claim) {
        PlotValue v = values.get(claim.chunk());
        return v == null ? 0 : v.value;
    }

    public void value(Claim claim, long cents) {
        PlotValue v = new PlotValue(claim.chunk(), Math.max(0, cents));
        values.put(claim.chunk(), v);
        civ.saves().save(COLLECTION, v);
    }

    public void forget(Claim claim) {
        if (values.remove(claim.chunk()) != null) civ.saves().delete(COLLECTION, claim.chunk().toString());
    }

    // --- rights ---------------------------------------------------------------------------------

    private Claim here(Player p) throws CivException {
        Claim claim = civ.state().claim(ChunkKey.of(p.getLocation()));
        if (claim == null) throw new CivException("plot.none");
        return claim;
    }

    private Town townOf(Claim claim) throws CivException {
        Town town = civ.state().town(claim.townId());
        if (town == null) throw new CivException("plot.none");
        return town;
    }

    private boolean isLeader(Player p, Town town) {
        Civilization c = civ.state().civOf(town);
        return c != null && c.isLeader(p.getUniqueId());
    }

    /** Owner of the plot; for plots without an owner: mayors, assistants and civ leaders of the owning town. */
    private void checkEdit(Player p, Claim claim) throws CivException {
        Town town = townOf(claim);
        if (claim.owner() != null) {
            if (claim.owner().equals(p.getUniqueId())) return;
        }
        if (town.isOfficial(p.getUniqueId()) || isLeader(p, town)) return;
        throw new CivException("plot.no-rights");
    }

    // --- commands -------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> tree() {
        return lit("plot").executes(CmdKit.help("plot.help"))
                .then(lit("help").executes(CmdKit.help("plot.help")))
                .then(lit("info").executes(Cmd.player((p, ctx) -> info(p))))
                .then(lit("setowner").then(word("player").suggests(CmdKit.players()).executes(Cmd.player((p, ctx) -> setOwner(p, arg(ctx, "player"))))))
                .then(lit("cleargroups").executes(Cmd.player((p, ctx) -> clearGroups(p))))
                .then(lit("addgroup").then(word("group").executes(Cmd.player((p, ctx) -> group(p, arg(ctx, "group"), true)))))
                .then(lit("removegroup").then(word("group").executes(Cmd.player((p, ctx) -> group(p, arg(ctx, "group"), false)))))
                .then(lit("perm").executes(Cmd.player((p, ctx) -> perms(p)))
                        .then(lit("set").then(word("perm").suggests(CmdKit.values(List.of("build", "destroy", "interact", "itemuse", "*")))
                                .then(word("subject").suggests(CmdKit.values(List.of("owner", "group", "others", "civ", "*")))
                                        .then(word("value").suggests(CmdKit.values(List.of("on", "off")))
                                                .executes(Cmd.player((p, ctx) -> setPerm(p, arg(ctx, "perm"), arg(ctx, "subject"), arg(ctx, "value")))))))))
                .then(lit("toggle").then(word("flag").suggests(CmdKit.values(List.of("mobs", "fire")))
                        .executes(Cmd.player((p, ctx) -> toggle(p, arg(ctx, "flag"))))))
                .then(lit("fs").then(word("price").executes(Cmd.player((p, ctx) -> forSale(p, arg(ctx, "price"))))))
                .then(lit("nfs").executes(Cmd.player((p, ctx) -> notForSale(p))))
                .then(lit("buy").executes(Cmd.player((p, ctx) -> buy(p))))
                .then(lit("farminfo").executes(Cmd.player((p, ctx) -> farm(p))))
                .then(lit("farm").executes(Cmd.player((p, ctx) -> farm(p))));
    }

    private void info(Player p) throws CivException {
        Claim claim = here(p);
        Town town = townOf(claim);
        Messages m = civ.messages();
        Resident owner = claim.owner() == null ? null : civ.state().resident(claim.owner());
        m.sendRaw(p, "plot.info.header", Messages.arg("x", claim.chunk().x()), Messages.arg("z", claim.chunk().z()));
        m.sendRaw(p, "plot.info.town", Messages.arg("town", town.name()),
                Messages.arg("owner", owner == null ? m.plain("plot.info.town-owned") : owner.name()));
        m.sendRaw(p, "plot.info.groups", Messages.arg("groups", claim.groups().isEmpty() ? "-" : String.join(", ", claim.groups())));
        m.sendRaw(p, "plot.info.flags", Messages.arg("mobs", m.plain(claim.mobs() ? "plot.on" : "plot.off")),
                Messages.arg("fire", m.plain(claim.fire() ? "plot.on" : "plot.off")),
                Messages.arg("locked", m.plain(claim.locked() ? "plot.yes" : "plot.no")));
        m.sendRaw(p, "plot.info.value", Messages.money("value", value(claim)));
        if (claim.forSale()) m.sendRaw(p, "plot.info.for-sale", Messages.money("price", claim.price()));
        perms(p);
    }

    private void perms(Player p) throws CivException {
        Claim claim = here(p);
        Messages m = civ.messages();
        m.sendRaw(p, "plot.perm.header");
        for (PlotSubject s : PlotSubject.values()) {
            List<Component> cells = new ArrayList<>();
            for (PlotPerm perm : PlotPerm.values()) {
                cells.add(m.component(claim.allowed(s, perm) ? "plot.perm.cell-on" : "plot.perm.cell-off",
                        Messages.arg("perm", perm.name().toLowerCase(Locale.ROOT))));
            }
            Component line = m.component("plot.perm.subject", Messages.arg("subject", s.name().toLowerCase(Locale.ROOT)));
            for (Component c : cells) line = line.append(Component.space()).append(c);
            p.sendMessage(line);
        }
    }

    private void setOwner(Player p, String name) throws CivException {
        Claim claim = here(p);
        checkEdit(p, claim);
        Town town = townOf(claim);
        if (name.equalsIgnoreCase("none")) {
            claim.owner(null);
            claim.price(0);
            civ.state().save(claim);
            civ.messages().send(p, "plot.owner.cleared");
            return;
        }
        Resident r = Lookup.resident(name);
        if (!town.isResident(r.uuid())) throw new CivException("plot.owner.not-resident", Messages.arg("name", r.name()));
        claim.owner(r.uuid());
        claim.price(0);
        civ.state().save(claim);
        civ.messages().send(p, "plot.owner.set", Messages.arg("name", r.name()));
        Channels.resident(r, "plot.owner.you", Messages.arg("x", claim.chunk().x()), Messages.arg("z", claim.chunk().z()));
    }

    private void clearGroups(Player p) throws CivException {
        Claim claim = here(p);
        checkEdit(p, claim);
        claim.groups().clear();
        civ.state().save(claim);
        civ.messages().send(p, "plot.group.cleared");
    }

    private void group(Player p, String name, boolean add) throws CivException {
        Claim claim = here(p);
        Town town = townOf(claim);
        // Civ leaders may add/remove groups on any plot of their towns (spec §7.4).
        if (!isLeader(p, town)) checkEdit(p, claim);
        String g = name.toLowerCase(Locale.ROOT);
        if (!town.groups().containsKey(g)) throw new CivException("town.group.unknown", Messages.arg("group", name));
        boolean changed = add ? claim.groups().add(g) : claim.groups().remove(g);
        if (!changed) throw new CivException(add ? "plot.group.already" : "plot.group.absent", Messages.arg("group", g));
        civ.state().save(claim);
        civ.messages().send(p, add ? "plot.group.added" : "plot.group.removed", Messages.arg("group", g));
    }

    private static Boolean parseSwitch(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "on", "yes", "true", "1", "да", "вкл" -> true;
            case "off", "no", "false", "0", "нет", "выкл" -> false;
            default -> null;
        };
    }

    private void setPerm(Player p, String permName, String subjectName, String valueText) throws CivException {
        Claim claim = here(p);
        checkEdit(p, claim);
        Boolean value = parseSwitch(valueText);
        if (value == null) throw new CivException("plot.perm.bad-value");
        List<PlotPerm> perms = new ArrayList<>();
        if (permName.equals("*")) perms.addAll(List.of(PlotPerm.values()));
        else {
            try {
                perms.add(PlotPerm.valueOf(permName.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new CivException("plot.perm.bad-perm");
            }
        }
        List<PlotSubject> subjects = new ArrayList<>();
        if (subjectName.equals("*")) subjects.addAll(List.of(PlotSubject.values()));
        else {
            try {
                subjects.add(PlotSubject.valueOf(subjectName.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new CivException("plot.perm.bad-subject");
            }
        }
        for (PlotSubject s : subjects) for (PlotPerm perm : perms) claim.set(s, perm, value);
        civ.state().save(claim);
        civ.messages().send(p, "plot.perm.set", Messages.arg("perm", permName), Messages.arg("subject", subjectName),
                Messages.arg("value", civ.messages().plain(value ? "plot.on" : "plot.off")));
    }

    private void toggle(Player p, String flag) throws CivException {
        Claim claim = here(p);
        checkEdit(p, claim);
        boolean value;
        switch (flag.toLowerCase(Locale.ROOT)) {
            case "mobs" -> {
                value = !claim.mobs();
                claim.mobs(value);
            }
            case "fire" -> {
                value = !claim.fire();
                claim.fire(value);
            }
            default -> throw new CivException("plot.toggle.unknown");
        }
        civ.state().save(claim);
        civ.messages().send(p, "plot.toggle.done", Messages.arg("flag", flag.toLowerCase(Locale.ROOT)),
                Messages.arg("value", civ.messages().plain(value ? "plot.on" : "plot.off")));
    }

    private void forSale(Player p, String priceText) throws CivException {
        Claim claim = here(p);
        checkEdit(p, claim);
        if (claim.locked()) throw new CivException("plot.sale.locked");
        long price = Amounts.require(priceText);
        long max = civ.balance().coins("plot", "max-price", 10_000_000);
        if (price > max) throw new CivException("plot.sale.max", Messages.money("max", max));
        claim.price(price);
        civ.state().save(claim);
        civ.messages().send(p, "plot.sale.listed", Messages.money("price", price));
    }

    private void notForSale(Player p) throws CivException {
        Claim claim = here(p);
        checkEdit(p, claim);
        if (!claim.forSale()) throw new CivException("plot.sale.not-listed");
        claim.price(0);
        civ.state().save(claim);
        civ.messages().send(p, "plot.sale.unlisted");
    }

    private void buy(Player p) throws CivException {
        Claim claim = here(p);
        Town town = townOf(claim);
        if (!claim.forSale()) throw new CivException("plot.sale.not-listed");
        if (!town.isResident(p.getUniqueId())) throw new CivException("plot.buy.not-resident", Messages.arg("town", town.name()));
        if (p.getUniqueId().equals(claim.owner())) throw new CivException("plot.buy.own");
        Resident buyer = civ.state().resident(p);
        long price = claim.price();
        Resident seller = claim.owner() == null ? null : civ.state().resident(claim.owner());
        if (seller != null) Ledger.pay(buyer, seller, price);
        else Ledger.residentToTown(buyer, town, price);
        claim.owner(p.getUniqueId());
        claim.price(0);
        if (civ.balance().file("plot").getBoolean("buy-clears-groups", true)) claim.groups().clear();
        civ.state().save(claim);
        value(claim, price);
        civ.messages().send(p, "plot.buy.done", Messages.money("price", price));
        if (seller != null) Channels.resident(seller, "plot.buy.sold", Messages.arg("name", p.getName()), Messages.money("price", price));
    }

    private void farm(Player p) throws CivException {
        FarmInfoApi api = civ.apiOrNull(FarmInfoApi.class);
        if (api == null) throw new CivException("plot.farm.unavailable");
        List<Component> lines = api.farmInfo(p, ChunkKey.of(p.getLocation()));
        if (lines == null) throw new CivException("plot.farm.none");
        lines.forEach(p::sendMessage);
    }

    // --- mob toggle -----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Enemy) || !NATURAL.contains(e.getSpawnReason())) return;
        Claim claim = civ.state().claim(ChunkKey.of(e.getLocation()));
        if (claim != null && !claim.mobs()) e.setCancelled(true);
    }
}
