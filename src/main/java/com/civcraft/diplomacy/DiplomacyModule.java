package com.civcraft.diplomacy;

import static com.civcraft.resident.CmdKit.arg;
import static com.civcraft.resident.CmdKit.lit;
import static com.civcraft.resident.CmdKit.word;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.chat.Channels;
import com.civcraft.civ.CivPerms;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.core.util.Durations;
import com.civcraft.event.PeaceEvent;
import com.civcraft.event.RelationChangedEvent;
import com.civcraft.event.WarDeclaredEvent;
import com.civcraft.gui.Items;
import com.civcraft.gui.Menu;
import com.civcraft.model.Claim;
import com.civcraft.model.Civilization;
import com.civcraft.model.Relation;
import com.civcraft.model.RelationType;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.model.TownStatus;
import com.civcraft.resident.CmdKit;
import com.civcraft.resident.Lookup;
import com.civcraft.resident.ResidentModule;
import com.civcraft.town.TownModule;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Diplomacy (spec §17): the relation state machine with expiries, mutual requests, unilateral war
 * and hostility declarations with their deadlines, liberation, capitulation and gifts. Relations are a
 * single record per pair of civilizations kept in {@code GameState}; every change and expiry goes
 * through {@link #setRelation} so the in-memory state, the database and the events stay consistent
 * (audit A-H4, A-H5).
 */
public final class DiplomacyModule implements Module, DiplomacyApi, Listener {

    /** A pending mutual request (peace, alliance, neutrality) or gift. */
    private record Pending(String from, String to, RelationType type, String giftTown, Instant expires, UUID requester) {
        String key() {
            return from + ">" + to;
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private CivCraft civ;
    private WarSchedule schedule;
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    @Override
    public String id() {
        return "diplomacy";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("diplomacy");
        schedule = new WarSchedule(civ.plugin().getConfig(), civ.clock().zone());
    }

    @Override
    public void enable(CivCraft civ) {
        civ.listen(this);
        civ.clock().everyMinute("relation-expiry", this::expire);
        // Repair relation records whose expiry was never set (older data).
        for (Relation r : List.copyOf(civ.state().relations())) {
            if (r.expires() == null && r.type() != RelationType.NEUTRAL) {
                r.expires(r.since().plus(lifetime(r.type())));
                civ.state().save(r);
            }
        }
    }

    public WarSchedule schedule() {
        return schedule;
    }

    // --- DiplomacyApi ---------------------------------------------------------------------------

    @Override
    public RelationType relation(String civA, String civB) {
        return civ.state().relation(civA, civB);
    }

    @Override
    public boolean isAtWar(String civA, String civB) {
        return civA != null && civB != null && !civA.equals(civB) && relation(civA, civB) == RelationType.WAR;
    }

    @Override
    public boolean isAtWar(String civId) {
        for (Relation r : civ.state().relations()) if (r.type() == RelationType.WAR && r.involves(civId)) return true;
        return false;
    }

    @Override
    public boolean isAggressor(String civId) {
        for (Relation r : civ.state().relations()) {
            if (r.type() == RelationType.WAR && r.involves(civId) && civId.equals(r.aggressor())) return true;
        }
        return false;
    }

    @Override
    public Set<String> enemies(String civId) {
        return related(civId, RelationType.WAR);
    }

    @Override
    public Set<String> allies(String civId) {
        return related(civId, RelationType.ALLY);
    }

    private Set<String> related(String civId, RelationType type) {
        Set<String> result = new HashSet<>();
        for (Relation r : civ.state().relations()) if (r.type() == type && r.involves(civId)) result.add(r.other(civId));
        return result;
    }

    @Override
    public boolean isWarTime() {
        for (com.civcraft.Module m : civ.modules()) {
            if (m instanceof WarTimeSource source) return source.isWarTime();
        }
        return schedule.isWarTime(Instant.now());
    }

    @Override
    public Instant nextWarStart() {
        Instant now = Instant.now();
        Instant current = schedule.currentStart(now);
        return current != null ? current : schedule.nextStart(now);
    }

    @Override
    public boolean isWarWithin(Duration window) {
        if (isWarTime()) return true;
        Instant next = schedule.nextStart(Instant.now());
        return next != null && !next.isAfter(Instant.now().plus(window));
    }

    @Override
    public boolean victoryRunning() {
        com.civcraft.victory.VictoryApi victory = civ.apiOrNull(com.civcraft.victory.VictoryApi.class);
        return victory != null && victory.anyCountdown();
    }

    private Duration lifetime(RelationType type) {
        String key = "lifetime-days." + type.name().toLowerCase(Locale.ROOT);
        return Duration.ofDays(civ.balance().getInt("diplomacy", key, switch (type) {
            case PEACE -> 7;
            case ALLY, HOSTILE -> 14;
            case WAR -> 21;
            default -> 0;
        }));
    }

    @Override
    public void setRelation(String civA, String civB, RelationType type, String aggressor) {
        if (civA.equals(civB)) return;
        RelationType old = relation(civA, civB);
        civ.state().setRelation(civA, civB, type, type == RelationType.WAR || type == RelationType.HOSTILE ? aggressor : null);
        if (type != RelationType.NEUTRAL) {
            civ.state().relationObject(civA, civB).ifPresent(r -> {
                Duration life = lifetime(type);
                r.expires(life.isZero() ? null : Instant.now().plus(life));
                civ.state().save(r);
            });
        }
        pending.remove(civA + ">" + civB);
        pending.remove(civB + ">" + civA);
        civ.stats().invalidate();
        if (old != type) {
            new RelationChangedEvent(civA, civB, old, type).call();
            if (type == RelationType.WAR) {
                String attacker = aggressor == null ? civA : aggressor;
                new WarDeclaredEvent(attacker, attacker.equals(civA) ? civB : civA).call();
            }
            if (old == RelationType.WAR) new PeaceEvent(civA, civB).call();
        }
    }

    @Override
    public void endWar(String civA, String civB) {
        if (!isAtWar(civA, civB)) return;
        setRelation(civA, civB, RelationType.PEACE, null);
        announce(civA, civB, RelationType.PEACE);
    }

    private void expire() {
        Instant now = Instant.now();
        for (Relation r : List.copyOf(civ.state().relations())) {
            if (r.expires() == null || r.expires().isAfter(now)) continue;
            setRelation(r.civA(), r.civB(), RelationType.NEUTRAL, null);
            announce(r.civA(), r.civB(), RelationType.NEUTRAL);
        }
        pending.values().removeIf(p -> p.expires().isBefore(now));
    }

    private void announce(String a, String b, RelationType type) {
        Civilization ca = civ.state().civ(a);
        Civilization cb = civ.state().civ(b);
        if (ca == null || cb == null) return;
        Channels.global("diplomacy.announce." + type.name().toLowerCase(Locale.ROOT), Messages.arg("a", ca.name()), Messages.arg("b", cb.name()));
    }

    // --- rules ----------------------------------------------------------------------------------

    private Civilization myCiv(Player p) throws CivException {
        Civilization c = civ.state().civOf(p);
        if (c == null) throw new CivException("error.not-in-civ");
        return c;
    }

    private boolean olderThan(Civilization c, int days) {
        return c.founded().plus(Duration.ofDays(days)).isBefore(Instant.now());
    }

    /** Province/civilization war rules (spec §5.3, §17). */
    private void checkWarPair(Civilization attacker, Civilization target) throws CivException {
        if (attacker.isProvince() == target.isProvince()) return;
        int days = civ.balance().getInt("diplomacy", "province-war-exception-days", 7);
        if (!attacker.isProvince() && target.isProvince() && olderThan(target, days) && olderThan(attacker, days)) return;
        throw new CivException("diplomacy.declare.province");
    }

    private void declare(Player p, String targetName, String typeName) throws CivException {
        Civilization mine = myCiv(p);
        CivPerms.check(p, mine, "dip");
        Civilization target = Lookup.civ(targetName);
        if (target.id().equals(mine.id())) throw new CivException("diplomacy.self");
        if (mine.isConquered() || target.isConquered()) throw new CivException("diplomacy.conquered");
        RelationType type = switch (typeName.toLowerCase(Locale.ROOT)) {
            case "war" -> RelationType.WAR;
            case "hostile" -> RelationType.HOSTILE;
            default -> throw new CivException("diplomacy.declare.bad-type");
        };
        RelationType current = relation(mine.id(), target.id());
        if (current == type) throw new CivException("diplomacy.already", Messages.arg("civ", target.name()));
        if (type == RelationType.HOSTILE && current == RelationType.WAR) throw new CivException("diplomacy.declare.at-war");
        if (type == RelationType.WAR) {
            boolean victory = victoryRunning();
            if (isWarTime()) throw new CivException("diplomacy.war-time");
            int hours = civ.balance().getInt("diplomacy", victory ? "declare-war-hours-before-victory" : "declare-war-hours-before", victory ? 24 : 72);
            if (isWarWithin(Duration.ofHours(hours))) throw new CivException("diplomacy.declare.too-late", Messages.arg("hours", hours));
            if (current == RelationType.ALLY && !victory
                    && isWarWithin(Duration.ofHours(civ.balance().getInt("diplomacy", "ally-declare-hours-before", 24)))) {
                throw new CivException("diplomacy.declare.ally-too-late");
            }
            checkWarPair(mine, target);
        }
        setRelation(mine.id(), target.id(), type, mine.id());
        announce(mine.id(), target.id(), type);
    }

    private void request(Player p, String targetName, String typeName) throws CivException {
        Civilization mine = myCiv(p);
        CivPerms.check(p, mine, "dip");
        Civilization target = Lookup.civ(targetName);
        if (target.id().equals(mine.id())) throw new CivException("diplomacy.self");
        RelationType type = switch (typeName.toLowerCase(Locale.ROOT)) {
            case "neutral" -> RelationType.NEUTRAL;
            case "peace" -> RelationType.PEACE;
            case "ally" -> RelationType.ALLY;
            default -> throw new CivException("diplomacy.request.bad-type");
        };
        validateRequest(mine, target, type);
        Pending req = new Pending(mine.id(), target.id(), type, null,
                Instant.now().plusSeconds(civ.balance().getInt("diplomacy", "request-seconds", 300)), p.getUniqueId());
        pending.put(req.key(), req);
        notifyTarget(req, civ.messages().component("diplomacy.request.question", Messages.arg("civ", mine.name()),
                Messages.arg("type", civ.messages().plain("diplomacy.type." + type.name().toLowerCase(Locale.ROOT)))));
        civ.messages().send(p, "diplomacy.request.sent", Messages.arg("civ", target.name()));
    }

    private void validateRequest(Civilization mine, Civilization target, RelationType type) throws CivException {
        RelationType current = relation(mine.id(), target.id());
        if (current == type) throw new CivException("diplomacy.already", Messages.arg("civ", target.name()));
        if (isWarTime() && civ.balance().file("diplomacy").getBoolean("no-diplomacy-during-war", true)) {
            throw new CivException("diplomacy.war-time");
        }
        if (type == RelationType.ALLY) {
            if (current == RelationType.WAR) throw new CivException("diplomacy.request.ally-at-war");
            int days = civ.balance().getInt("diplomacy", "ally-lock-days-before-war", 3);
            if ((isAtWar(mine.id()) || isAtWar(target.id())) && isWarWithin(Duration.ofDays(days))) {
                throw new CivException("diplomacy.request.ally-too-late");
            }
        }
    }

    /** Sends a request to the leaders (with /civ dip permission) of the target civ. */
    private void notifyTarget(Pending req, Component text) {
        Civilization target = civ.state().civ(req.to());
        if (target == null) return;
        List<UUID> deciders = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (CivPerms.isMember(target, online.getUniqueId()) && CivPerms.has(target, online.getUniqueId(), "dip")) {
                deciders.add(online.getUniqueId());
            }
        }
        Channels.civ(target, "diplomacy.request.incoming", Messages.arg("civ", civ.state().civ(req.from()) == null ? "?" : civ.state().civ(req.from()).name()));
        if (deciders.isEmpty()) return;
        civ.module(ResidentModule.class).requests().ask("diplomacy", "dip:" + req.key(), deciders, req.requester(), text,
                Duration.between(Instant.now(), req.expires()), responder -> accept(responder, req.from()), responder -> {
                    pending.remove(req.key());
                    Player requester = Bukkit.getPlayer(req.requester());
                    if (requester != null) civ.messages().send(requester, "diplomacy.request.denied", Messages.arg("civ", target.name()));
                });
    }

    private void accept(Player responder, String fromId) throws CivException {
        Civilization mine = myCiv(responder);
        CivPerms.check(responder, mine, "dip");
        Pending req = pending.get(fromId + ">" + mine.id());
        if (req == null || req.expires().isBefore(Instant.now())) throw new CivException("diplomacy.request.none");
        Civilization from = civ.state().civ(req.from());
        if (from == null) throw new CivException("diplomacy.request.none");
        if (req.giftTown() != null || req.type() == null) {
            acceptGift(responder, from, mine, req);
            return;
        }
        validateRequest(from, mine, req.type());
        pending.remove(req.key());
        setRelation(from.id(), mine.id(), req.type(), null);
        announce(from.id(), mine.id(), req.type());
    }

    private void respond(Player p, boolean yes) throws CivException {
        Civilization mine = myCiv(p);
        CivPerms.check(p, mine, "dip");
        Pending latest = null;
        for (Pending req : pending.values()) if (req.to().equals(mine.id()) && req.expires().isAfter(Instant.now())) latest = req;
        if (latest == null) throw new CivException("diplomacy.request.none");
        civ.module(ResidentModule.class).requests().cancel("dip:" + latest.key());
        if (yes) accept(p, latest.from());
        else {
            pending.remove(latest.key());
            civ.messages().send(p, "request.denied");
        }
    }

    private void dissolve(Player p, String targetName) throws CivException {
        Civilization mine = myCiv(p);
        CivPerms.check(p, mine, "dip");
        Civilization target = Lookup.civ(targetName);
        Relation r = civ.state().relationObject(mine.id(), target.id()).orElseThrow(() -> new CivException("diplomacy.dissolve.nothing"));
        switch (r.type()) {
            case WAR -> throw new CivException("diplomacy.dissolve.war");
            case HOSTILE -> {
                if (!mine.id().equals(r.aggressor())) throw new CivException("diplomacy.dissolve.not-declarer");
            }
            default -> {
            }
        }
        setRelation(mine.id(), target.id(), RelationType.NEUTRAL, null);
        announce(mine.id(), target.id(), RelationType.NEUTRAL);
    }

    // --- liberation, capitulation, gifts --------------------------------------------------------

    private void liberate(Player p, String townName) throws CivException {
        Civilization mine = myCiv(p);
        CivPerms.check(p, mine, "dip");
        Town town = Lookup.town(townName);
        if (!Objects.equals(town.civId(), mine.id()) || !town.isCaptured()) throw new CivException("diplomacy.liberate.not-captured");
        Civilization home = civ.state().civ(town.nativeCivId());
        if (home == null || home.id().equals(mine.id())) throw new CivException("diplomacy.liberate.no-home");
        TownStatus restore = town.statusBeforeCapture() == null ? TownStatus.NATIVE : town.statusBeforeCapture();
        town.statusBeforeCapture(null);
        civ.module(TownModule.class).service().transfer(town, home, restore, false);
        if (town.id().equals(home.capitalId()) && home.isConquered()) {
            home.conqueredBy(null);
            civ.state().save(home);
        }
        Channels.global("diplomacy.liberated", Messages.arg("town", town.name()), Messages.arg("civ", home.name()), Messages.arg("by", mine.name()));
    }

    private void capitulate(Player p, String townName) throws CivException {
        Civilization mine = myCiv(p);
        CivPerms.check(p, mine, "dip");
        Town town = Lookup.town(townName);
        if (!town.isCaptured() || !Objects.equals(town.nativeCivId(), mine.id())) throw new CivException("diplomacy.capitulate.not-ours");
        civ.module(TownModule.class).capitulate(town);
    }

    private void checkGiftWindow(Civilization a, Civilization b) throws CivException {
        if (isWarTime()) throw new CivException("diplomacy.war-time");
        if (isAtWar(a.id()) || isAtWar(b.id())) throw new CivException("diplomacy.gift.at-war");
        int days = civ.balance().getInt("diplomacy", victoryRunning() ? "gift-lock-days-victory" : "gift-lock-days", victoryRunning() ? 1 : 3);
        if (isWarWithin(Duration.ofDays(days))) throw new CivException("diplomacy.gift.too-late", Messages.arg("days", days));
    }

    private void giftTown(Player p, String townName, String civName) throws CivException {
        Civilization mine = myCiv(p);
        CivPerms.check(p, mine, "dipgift");
        Town town = Lookup.town(townName);
        Civilization target = Lookup.civ(civName);
        validateGiftTown(mine, town, target);
        Pending req = new Pending(mine.id(), target.id(), null, town.id(),
                Instant.now().plusSeconds(civ.balance().getInt("diplomacy", "request-seconds", 300)), p.getUniqueId());
        pending.put(req.key(), req);
        notifyTarget(req, civ.messages().component("diplomacy.gift.town-question", Messages.arg("civ", mine.name()), Messages.arg("town", town.name())));
        civ.messages().send(p, "diplomacy.request.sent", Messages.arg("civ", target.name()));
    }

    private void validateGiftTown(Civilization mine, Town town, Civilization target) throws CivException {
        if (!Objects.equals(town.civId(), mine.id())) throw new CivException("diplomacy.gift.not-ours");
        if (town.id().equals(mine.capitalId())) throw new CivException("diplomacy.gift.capital");
        if (town.isCaptured()) throw new CivException("diplomacy.gift.captured");
        if (target.id().equals(mine.id())) throw new CivException("diplomacy.self");
        if (target.isProvince()) throw new CivException("diplomacy.gift.province");
        checkGiftWindow(mine, target);
    }

    private void giftCiv(Player p, String civName) throws CivException {
        Civilization mine = myCiv(p);
        CivPerms.check(p, mine, "dipgift");
        Civilization target = Lookup.civ(civName);
        if (target.id().equals(mine.id())) throw new CivException("diplomacy.self");
        if (target.isProvince()) throw new CivException("diplomacy.gift.province");
        checkGiftWindow(mine, target);
        Pending req = new Pending(mine.id(), target.id(), null, null,
                Instant.now().plusSeconds(civ.balance().getInt("diplomacy", "request-seconds", 300)), p.getUniqueId());
        pending.put(req.key(), req);
        notifyTarget(req, civ.messages().component("diplomacy.gift.civ-question", Messages.arg("civ", mine.name())));
        civ.messages().send(p, "diplomacy.request.sent", Messages.arg("civ", target.name()));
    }

    /** Gifts are re-validated completely when accepted (audit C-16). All wonders are destroyed (spec §17). */
    private void acceptGift(Player responder, Civilization from, Civilization to, Pending req) throws CivException {
        pending.remove(req.key());
        TownModule towns = civ.module(TownModule.class);
        if (req.giftTown() != null) {
            Town town = civ.state().town(req.giftTown());
            if (town == null) throw new CivException("diplomacy.request.none");
            validateGiftTown(from, town, to);
            town.nativeCivId(to.id());
            towns.service().transfer(town, to, TownStatus.NATIVE, true);
            Channels.global("diplomacy.gift.town-done", Messages.arg("town", town.name()), Messages.arg("from", from.name()), Messages.arg("to", to.name()));
            return;
        }
        checkGiftWindow(from, to);
        String name = from.name();
        for (Town town : List.copyOf(civ.state().towns(from))) {
            if (town.isCaptured()) continue;
            town.nativeCivId(to.id());
            towns.service().transfer(town, to, TownStatus.NATIVE, true);
        }
        Civilization still = civ.state().civ(from.id());
        if (still != null && civ.state().towns(still).isEmpty()) towns.service().deleteCiv(still);
        Channels.global("diplomacy.gift.civ-done", Messages.arg("from", name), Messages.arg("to", to.name()));
    }

    // --- views ----------------------------------------------------------------------------------

    private void show(Player p, String name) throws CivException {
        Civilization c = name == null ? myCiv(p) : Lookup.civ(name);
        civ.messages().send(p, "diplomacy.show.header", Messages.arg("civ", c.name()));
        for (Relation r : civ.state().relations(c)) line(p, r, c.id());
    }

    private void line(Player p, Relation r, String perspective) {
        Civilization a = civ.state().civ(perspective == null ? r.civA() : perspective);
        Civilization b = civ.state().civ(perspective == null ? r.civB() : r.other(perspective));
        if (a == null || b == null) return;
        Civilization aggressor = r.aggressor() == null ? null : civ.state().civ(r.aggressor());
        civ.messages().sendRaw(p, "diplomacy.show.line", Messages.arg("a", a.name()), Messages.arg("b", b.name()),
                Messages.arg("type", civ.messages().plain("diplomacy.type." + r.type().name().toLowerCase(Locale.ROOT))),
                Messages.arg("aggressor", aggressor == null ? "" : civ.messages().plain("diplomacy.show.aggressor", Messages.arg("civ", aggressor.name()))),
                Messages.arg("expires", r.expires() == null ? "-" : TIME.format(r.expires().atZone(civ.clock().zone()))));
    }

    private void global(Player p, RelationType filter) {
        civ.messages().send(p, filter == null ? "diplomacy.global.header" : "diplomacy.global.header-" + filter.name().toLowerCase(Locale.ROOT));
        for (Relation r : civ.state().relations()) if (filter == null || r.type() == filter) line(p, r, null);
    }

    private final class DipMenu extends Menu {
        private final Civilization c;

        DipMenu(Civilization c) {
            super(6, Messages.get().component("diplomacy.menu.title", Messages.arg("civ", c.name())));
            this.c = c;
        }

        @Override
        protected void render(Player viewer) {
            int slot = 0;
            for (Pending req : List.copyOf(pending.values())) {
                if (!req.to().equals(c.id()) || slot >= 18) continue;
                Civilization from = civ.state().civ(req.from());
                if (from == null) continue;
                String type = req.type() == null ? (req.giftTown() == null ? "gift-civ" : "gift-town") : req.type().name().toLowerCase(Locale.ROOT);
                set(slot++, Items.of(Material.WRITABLE_BOOK).name(Messages.get().component("diplomacy.menu.request",
                                Messages.arg("civ", from.name()), Messages.arg("type", civ.messages().plain("diplomacy.type." + type))))
                        .lore(Messages.get().component("diplomacy.menu.request-lore")).build(), e -> {
                    try {
                        if (e.isLeftClick()) accept(viewer, req.from());
                        else {
                            pending.remove(req.key());
                            civ.messages().send(viewer, "request.denied");
                        }
                    } catch (CivException ex) {
                        civ.messages().send(viewer, ex.key(), ex.args());
                    }
                    refresh(viewer);
                });
            }
            slot = 18;
            for (Relation r : civ.state().relations(c)) {
                if (slot >= size()) break;
                Civilization other = civ.state().civ(r.other(c.id()));
                if (other == null) continue;
                Material icon = switch (r.type()) {
                    case WAR -> Material.RED_BANNER;
                    case HOSTILE -> Material.ORANGE_BANNER;
                    case ALLY -> Material.LIME_BANNER;
                    case PEACE -> Material.LIGHT_BLUE_BANNER;
                    default -> Material.WHITE_BANNER;
                };
                set(slot++, Items.of(icon).name(Component.text(other.name()))
                        .lore(Messages.get().component("diplomacy.menu.relation",
                                Messages.arg("type", civ.messages().plain("diplomacy.type." + r.type().name().toLowerCase(Locale.ROOT)))))
                        .build());
            }
        }
    }

    public LiteralArgumentBuilder<CommandSourceStack> dipCommand() {
        return lit("dip").executes(CmdKit.help("diplomacy.help"))
                .then(lit("menu").executes(Cmd.player((p, ctx) -> new DipMenu(myCiv(p)).open(p))))
                .then(lit("request").then(word("civ").suggests(CmdKit.civs()).then(word("type").suggests(CmdKit.values(List.of("neutral", "peace", "ally")))
                        .executes(Cmd.player((p, ctx) -> request(p, arg(ctx, "civ"), arg(ctx, "type")))))))
                .then(lit("declare").then(word("civ").suggests(CmdKit.civs()).then(word("type").suggests(CmdKit.values(List.of("hostile", "war")))
                        .executes(Cmd.player((p, ctx) -> declare(p, arg(ctx, "civ"), arg(ctx, "type")))))))
                .then(lit("dissolution").then(word("civ").suggests(CmdKit.civs()).executes(Cmd.player((p, ctx) -> dissolve(p, arg(ctx, "civ"))))))
                .then(lit("respond").then(word("answer").suggests(CmdKit.values(List.of("yes", "no")))
                        .executes(Cmd.player((p, ctx) -> respond(p, List.of("yes", "да", "y").contains(arg(ctx, "answer").toLowerCase(Locale.ROOT)))))))
                .then(lit("show").executes(Cmd.player((p, ctx) -> show(p, null)))
                        .then(word("civ").suggests(CmdKit.civs()).executes(Cmd.player((p, ctx) -> show(p, arg(ctx, "civ"))))))
                .then(lit("global").executes(Cmd.player((p, ctx) -> global(p, null))))
                .then(lit("wars").executes(Cmd.player((p, ctx) -> global(p, RelationType.WAR))))
                .then(lit("allies").executes(Cmd.player((p, ctx) -> global(p, RelationType.ALLY))))
                .then(lit("peaces").executes(Cmd.player((p, ctx) -> global(p, RelationType.PEACE))))
                .then(lit("hostiles").executes(Cmd.player((p, ctx) -> global(p, RelationType.HOSTILE))))
                .then(lit("liberate").then(word("town").suggests(CmdKit.towns()).executes(Cmd.player((p, ctx) -> liberate(p, arg(ctx, "town"))))))
                .then(lit("capitulate").then(word("town").suggests(CmdKit.towns()).executes(Cmd.player((p, ctx) -> capitulate(p, arg(ctx, "town"))))))
                .then(lit("gift")
                        .then(lit("town").then(word("town").suggests(CmdKit.towns()).then(word("civ").suggests(CmdKit.civs())
                                .executes(Cmd.player((p, ctx) -> giftTown(p, arg(ctx, "town"), arg(ctx, "civ")))))))
                        .then(lit("entireciv").then(word("civ").suggests(CmdKit.civs())
                                .executes(Cmd.player((p, ctx) -> giftCiv(p, arg(ctx, "civ")))))));
    }

    /** Human-readable time until the next war (for /civ time). */
    public String untilWar() {
        if (isWarTime()) return civ.messages().plain("diplomacy.war-now");
        Instant next = schedule.nextStart(Instant.now());
        return next == null ? "-" : Durations.format(Duration.between(Instant.now(), next));
    }

    // --- PvP in towns ---------------------------------------------------------------------------

    /**
     * PvP inside town claims is off between civilizations that are not at war (spec §17 NEUTRAL: "PvP
     * в городах выключено"), unless one side is an outlaw of that town. Camps and the wilderness are
     * always PvP. The war module may re-allow damage at a higher priority during war time.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = e.getDamager() instanceof Player p ? p
                : e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p2 ? p2 : null;
        if (attacker == null || attacker.equals(victim)) return;
        if (!civ.balance().file("diplomacy").getBoolean("town-pvp-protection", true)) return;
        Claim claim = civ.state().claim(ChunkKey.of(victim.getLocation()));
        if (claim == null) return;
        Town town = civ.state().town(claim.townId());
        if (town == null) return;
        if (town.outlaws().contains(victim.getUniqueId()) || town.outlaws().contains(attacker.getUniqueId())) return;
        Resident ra = civ.state().resident(attacker);
        Resident rv = civ.state().resident(victim);
        Town ta = civ.state().townOf(ra);
        Town tv = civ.state().townOf(rv);
        if (ta != null && tv != null && isAtWar(ta.civId(), tv.civId())) return;
        e.setCancelled(true);
        civ.messages().actionBar(attacker, "diplomacy.pvp-off");
    }

    /** Pending requests addressed to a civ (for /civ info). */
    public int pendingFor(Civilization c) {
        int n = 0;
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
            Pending req = it.next();
            if (req.expires().isBefore(Instant.now())) it.remove();
            else if (req.to().equals(c.id())) n++;
        }
        return n;
    }
}
