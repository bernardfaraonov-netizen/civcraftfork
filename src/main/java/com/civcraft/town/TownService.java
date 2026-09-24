package com.civcraft.town;

import com.civcraft.CivCraft;
import com.civcraft.chat.Channels;
import com.civcraft.chat.ChatModule;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.Durations;
import com.civcraft.diplomacy.DiplomacyApi;
import com.civcraft.event.CivDisbandedEvent;
import com.civcraft.event.ResidentTownChangedEvent;
import com.civcraft.event.StructureDestroyedEvent;
import com.civcraft.event.TownDisbandedEvent;
import com.civcraft.event.TownFoundedEvent;
import com.civcraft.event.TownOwnerChangedEvent;
import com.civcraft.model.Claim;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.model.TownStatus;
import com.civcraft.structure.StructureApi;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * All structural changes of towns: founding, membership, deletion and ownership transfers. Each
 * operation keeps every index consistent (resident ↔ town ↔ groups ↔ claims ↔ civ) and fires the
 * matching event exactly once.
 */
public final class TownService {

    private final CivCraft civ;
    private final TownModule module;

    TownService(CivCraft civ, TownModule module) {
        this.civ = civ;
        this.module = module;
    }

    // --- main buildings -------------------------------------------------------------------------

    public List<String> townHallTypes() {
        return civ.balance().file("town").getStringList("main-buildings.town-hall");
    }

    public List<String> capitolTypes() {
        return civ.balance().file("town").getStringList("main-buildings.capitol");
    }

    public boolean isMainBuilding(String type) {
        String t = type.toLowerCase(Locale.ROOT);
        return townHallTypes().contains(t) || capitolTypes().contains(t);
    }

    /** The town hall or capitol of a town (complete or under construction), or null. */
    public StructureApi.Placed mainBuilding(Town town) {
        StructureApi api = civ.apiOrNull(StructureApi.class);
        if (api == null) return null;
        StructureApi.Placed best = null;
        for (StructureApi.Placed p : api.of(town)) {
            if (!isMainBuilding(p.type())) continue;
            if (best == null || (p.complete() && !best.complete())) best = p;
        }
        return best;
    }

    /** Whether the town's main building is finished; true when no structure module is installed. */
    public boolean mainBuildingComplete(Town town) {
        StructureApi api = civ.apiOrNull(StructureApi.class);
        if (api == null) return true;
        StructureApi.Placed p = mainBuilding(town);
        return p != null && p.complete();
    }

    public boolean hasCompleteCapitol(Town town) {
        StructureApi api = civ.apiOrNull(StructureApi.class);
        if (api == null) return false;
        for (String type : capitolTypes()) {
            for (StructureApi.Placed p : api.of(town, type)) if (p.complete()) return true;
        }
        return false;
    }

    // --- founding -------------------------------------------------------------------------------

    /** Creates a town with the founder as resident and mayor. Claims and the town hall are placed by the caller. */
    public Town create(String name, Civilization owner, Resident founder, BlockPos center, String theme) {
        Town town = new Town(UUID.randomUUID().toString(), name, owner.id(), center);
        town.theme(theme);
        town.taxRate(0);
        civ.state().addTown(town);
        module.data(town);
        module.saveData(town);
        leaveCurrent(founder);
        town.residents().add(founder.uuid());
        town.mayors().add(founder.uuid());
        founder.townId(town.id());
        founder.selectedTownId(null);
        civ.state().save(founder);
        civ.state().save(town);
        civ.stats().invalidate();
        new TownFoundedEvent(town.id(), owner.id()).call();
        new ResidentTownChangedEvent(founder.uuid(), null, town.id()).call();
        return town;
    }

    /** Removes a resident from their current town before joining another one (founding, settler). */
    private void leaveCurrent(Resident r) {
        Town old = civ.state().townOf(r);
        if (old != null) removeResident(old, r, false, null, false);
    }

    // --- membership -----------------------------------------------------------------------------

    /** Rejoin cooldown (spec §6.5: 12 h after leaving a town of the same civilization). */
    public void checkRejoinCooldown(Resident r, Town target) throws CivException {
        if (target.civId() == null) return;
        Instant left = r.leftCiv().get(target.civId());
        if (left == null) return;
        Duration cooldown = Duration.ofHours(civ.balance().getInt("core", "resident.rejoin-cooldown-hours", 12));
        Duration remaining = Duration.between(Instant.now(), left.plus(cooldown));
        if (remaining.isNegative() || remaining.isZero()) {
            r.leftCiv().remove(target.civId());
            civ.state().save(r);
            return;
        }
        throw new CivException("town.join.cooldown", Messages.arg("time", Durations.format(remaining)));
    }

    public void addResident(Town town, Resident r) throws CivException {
        if (r.hasTown()) throw new CivException("town.join.already-in-town");
        if (r.campId() != null) throw new CivException("town.join.in-camp");
        checkRejoinCooldown(r, town);
        town.residents().add(r.uuid());
        r.townId(town.id());
        r.selectedTownId(null);
        civ.state().save(r);
        civ.state().save(town);
        civ.stats().invalidate();
        module.production().invalidate();
        Channels.town(town, "town.join.joined", Messages.arg("name", r.name()));
        Civilization c = civ.state().civOf(town);
        if (c != null) module.civLog(c, "joined", r.name(), town.name());
        new ResidentTownChangedEvent(r.uuid(), null, town.id()).call();
    }

    /**
     * Removes a resident from a town: all protected groups of that town, owned plots return to the
     * town, rejoin cooldown starts, tax debt towards the town is cleared.
     */
    public void removeResident(Town town, Resident r, boolean evicted, String by, boolean cooldown) {
        UUID id = r.uuid();
        for (Set<UUID> members : town.groups().values()) members.remove(id);
        town.residents().remove(id);
        for (Claim claim : civ.state().claims(town)) {
            if (id.equals(claim.owner())) {
                claim.owner(null);
                claim.price(0);
                claim.resetPerms();
                claim.groups().clear();
                claim.groups().add(Town.RESIDENTS);
                civ.state().save(claim);
            }
        }
        r.townId(null);
        r.selectedTownId(null);
        r.debt(0);
        if (cooldown && town.civId() != null) r.leftCiv().put(town.civId(), Instant.now());
        civ.state().save(r);
        civ.state().save(town);
        civ.stats().invalidate();
        module.production().invalidate();
        ChatModule chat = civ.apiOrNull(ChatModule.class);
        if (chat != null) chat.reset(id);
        Civilization c = civ.state().civOf(town);
        if (c != null) {
            c.leaders().remove(id);
            c.advisers().remove(id);
            civ.state().save(c);
            module.civLog(c, evicted ? "evicted" : "left", r.name(), town.name());
        }
        if (evicted) Channels.town(town, "town.evict.announce", Messages.arg("name", r.name()), Messages.arg("by", by == null ? "-" : by));
        else Channels.town(town, "town.leave.announce", Messages.arg("name", r.name()));
        Player p = Bukkit.getPlayer(id);
        DiplomacyApi dip = civ.apiOrNull(DiplomacyApi.class);
        if (p != null && dip != null && dip.isWarTime() && c != null && dip.isAtWar(c.id())
                && civ.balance().file("town").getBoolean("kick-on-leave-during-war", true)) {
            // Spec §6.5: leaving or being evicted during a war removes the player from the war.
            civ.tasks().nextTick(() -> p.kick(civ.messages().component("town.leave.war-kick")));
        } else if (p != null && evicted) {
            civ.messages().send(p, "town.evict.you", Messages.arg("town", town.name()));
        }
        new ResidentTownChangedEvent(id, town.id(), null).call();
    }

    // --- deletion -------------------------------------------------------------------------------

    /** Deletes a town with its structures and claims; deletes the civilization when it was its last town. */
    public void delete(Town town, String reasonKey) {
        Civilization c = civ.state().civOf(town);
        deleteTownOnly(town, reasonKey);
        if (c != null) {
            if (civ.state().towns(c).isEmpty()) {
                deleteCiv(c);
            } else if (town.id().equals(c.capitalId())) {
                // A capital is never disbanded while other towns exist; this only happens through admin tools.
                Town next = civ.state().towns(c).stream().min((a, b) -> a.founded().compareTo(b.founded())).orElse(null);
                c.capitalId(next == null ? null : next.id());
                civ.state().save(c);
            }
        }
        civ.culture().recompute(true);
        civ.stats().invalidate();
        module.production().invalidate();
    }

    private void deleteTownOnly(Town town, String reasonKey) {
        new TownDisbandedEvent(town.id(), town.civId()).call();
        com.civcraft.structure.StructureModule structures = civ.apiOrNull(com.civcraft.structure.StructureModule.class);
        if (structures != null) {
            structures.removeAll(town, StructureDestroyedEvent.Cause.DISBANDED, false);
        } else {
            StructureApi api = civ.apiOrNull(StructureApi.class);
            if (api != null) {
                for (StructureApi.Placed p : List.copyOf(api.of(town))) {
                    try {
                        api.remove(p, false);
                    } catch (RuntimeException e) {
                        civ.logger().log(Level.WARNING, "Cannot remove structure " + p.id() + " of disbanded town " + town.name(), e);
                    }
                }
            }
        }
        List<Resident> residents = new ArrayList<>();
        for (UUID id : town.residents()) {
            Resident r = civ.state().resident(id);
            if (r != null) residents.add(r);
        }
        if (reasonKey != null) Channels.global(reasonKey, Messages.arg("town", town.name()));
        for (Resident r : residents) {
            Player p = Bukkit.getPlayer(r.uuid());
            if (p != null) civ.messages().send(p, "town.deleted.you", Messages.arg("town", town.name()));
            ChatModule chat = civ.apiOrNull(ChatModule.class);
            if (chat != null) chat.reset(r.uuid());
            r.debt(0);
        }
        for (Resident r : civ.state().residents()) {
            if (town.id().equals(r.selectedTownId())) {
                r.selectedTownId(null);
                civ.state().save(r);
            }
        }
        civ.state().removeTown(town);
        module.deleteData(town);
    }

    /** Deletes a civilization and all towns still belonging to it. */
    public void deleteCiv(Civilization c) {
        if (civ.state().civ(c.id()) == null) return;
        new CivDisbandedEvent(c.id()).call();
        for (Town t : List.copyOf(civ.state().towns(c))) deleteTownOnly(t, null);
        for (Town t : civ.state().towns()) {
            if (Objects.equals(t.nativeCivId(), c.id())) {
                t.nativeCivId(t.civId());
                civ.state().save(t);
            }
        }
        for (Civilization other : civ.state().civs()) {
            if (Objects.equals(other.conqueredBy(), c.id())) {
                other.conqueredBy(null);
                civ.state().save(other);
            }
        }
        civ.state().removeCiv(c);
        module.onCivDeleted(c);
        civ.culture().recompute(true);
        civ.stats().invalidate();
        module.production().invalidate();
    }

    // --- ownership ------------------------------------------------------------------------------

    /**
     * Moves a town to another civilization (market, gift, liberation, capitulation, revolution).
     * Residents stay in the town. Outlaws that are members of the new civilization are cleared. When
     * the civ owner lived in the town, they are moved to the old capital (spec §6.6). A civilization
     * left without towns is deleted.
     */
    public void transfer(Town town, Civilization to, TownStatus status, boolean destroyWonders) {
        Civilization from = civ.state().civOf(town);
        if (destroyWonders) module.destroyWonders(town);
        town.civId(to.id());
        town.status(status);
        if (status != TownStatus.CAPTURED) town.capturedAt(null);
        town.outlaws().removeIf(id -> com.civcraft.civ.CivPerms.isMember(to, id));
        for (UUID id : town.residents()) {
            Resident r = civ.state().resident(id);
            if (r != null && r.selectedTownId() != null) {
                r.selectedTownId(null);
                civ.state().save(r);
            }
        }
        civ.state().save(town);
        if (from != null && !from.id().equals(to.id())) {
            if (town.id().equals(from.capitalId())) {
                Town next = civ.state().towns(from).stream().findFirst().orElse(null);
                from.capitalId(next == null ? null : next.id());
            }
            from.leaders().removeIf(town.residents()::contains);
            from.advisers().removeIf(town.residents()::contains);
            if (town.residents().contains(from.owner())) {
                Town capital = civ.state().capital(from);
                Resident owner = civ.state().resident(from.owner());
                if (capital != null && owner != null && !capital.id().equals(town.id())) {
                    for (Set<UUID> members : town.groups().values()) members.remove(owner.uuid());
                    town.residents().remove(owner.uuid());
                    capital.residents().add(owner.uuid());
                    owner.townId(capital.id());
                    civ.state().save(owner);
                    civ.state().save(capital);
                }
            }
            civ.state().save(from);
        }
        civ.stats().invalidate();
        module.production().invalidate();
        new TownOwnerChangedEvent(town.id(), from == null ? null : from.id(), to.id()).call();
        if (from != null && !from.id().equals(to.id()) && civ.state().towns(from).isEmpty()) deleteCiv(from);
    }
}
