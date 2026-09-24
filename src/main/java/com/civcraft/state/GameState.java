package com.civcraft.state;

import com.civcraft.core.util.ChunkKey;
import com.civcraft.model.Camp;
import com.civcraft.model.Civilization;
import com.civcraft.model.Claim;
import com.civcraft.model.Relation;
import com.civcraft.model.RelationType;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.storage.DocumentStore;
import com.civcraft.storage.SaveQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * In-memory authority for residents, towns, civilizations, claims, camps and relations. Everything
 * is loaded at startup and mutated on the main thread only; changes are persisted through
 * {@link SaveQueue}.
 */
public final class GameState {

    public static final String RESIDENTS = "residents";
    public static final String TOWNS = "towns";
    public static final String CIVS = "civs";
    public static final String CLAIMS = "claims";
    public static final String CAMPS = "camps";
    public static final String RELATIONS = "relations";

    private final SaveQueue saves;
    private final Map<UUID, Resident> residents = new HashMap<>();
    private final Map<String, Resident> residentsByName = new HashMap<>();
    private final Map<String, Town> towns = new HashMap<>();
    private final Map<String, Town> townsByName = new HashMap<>();
    private final Map<String, Civilization> civs = new HashMap<>();
    private final Map<String, Civilization> civsByName = new HashMap<>();
    private final Map<String, Civilization> civsByTag = new HashMap<>();
    private final Map<ChunkKey, Claim> claims = new HashMap<>();
    private final Map<String, Camp> camps = new HashMap<>();
    private final Map<String, Camp> campsByName = new HashMap<>();
    private final Map<String, Relation> relations = new HashMap<>();

    public GameState(SaveQueue saves) {
        this.saves = saves;
    }

    public void load(DocumentStore store, Logger logger) {
        for (String c : List.of(RESIDENTS, TOWNS, CIVS, CLAIMS, CAMPS, RELATIONS)) store.createCollection(c);
        store.loadAll(RESIDENTS, Resident.class).forEach(this::indexResident);
        store.loadAll(CIVS, Civilization.class).forEach(this::indexCiv);
        for (Town town : store.loadAll(TOWNS, Town.class)) {
            if (town.civId() != null && !civs.containsKey(town.civId())) {
                logger.warning("Town " + town.name() + " references missing civ " + town.civId());
            }
            indexTown(town);
        }
        for (Claim claim : store.loadAll(CLAIMS, Claim.class)) {
            if (towns.containsKey(claim.townId())) claims.put(claim.chunk(), claim);
            else saves.delete(CLAIMS, claim.storageId());
        }
        store.loadAll(CAMPS, Camp.class).forEach(this::indexCamp);
        for (Relation r : store.loadAll(RELATIONS, Relation.class)) {
            if (civs.containsKey(r.civA()) && civs.containsKey(r.civB())) relations.put(r.storageId(), r);
            else saves.delete(RELATIONS, r.storageId());
        }
        // Repair dangling references instead of crashing (the legacy plugin threw NPEs here).
        for (Resident r : residents.values()) {
            if (r.townId() != null && !towns.containsKey(r.townId())) {
                logger.warning("Resident " + r.name() + " referenced missing town; cleared");
                r.townId(null);
                saves.save(RESIDENTS, r);
            }
            if (r.campId() != null && !camps.containsKey(r.campId())) {
                r.campId(null);
                saves.save(RESIDENTS, r);
            }
        }
        logger.info("Loaded " + residents.size() + " residents, " + civs.size() + " civilizations, "
                + towns.size() + " towns, " + claims.size() + " claims, " + camps.size() + " camps");
    }

    private static String norm(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    // --- residents ------------------------------------------------------------------------------

    private void indexResident(Resident r) {
        residents.put(r.uuid(), r);
        residentsByName.put(norm(r.name()), r);
    }

    public Resident resident(UUID uuid) {
        return residents.get(uuid);
    }

    public Resident resident(Player player) {
        return residents.get(player.getUniqueId());
    }

    public Resident resident(OfflinePlayer player) {
        return residents.get(player.getUniqueId());
    }

    public Resident residentByName(String name) {
        return residentsByName.get(norm(name));
    }

    public Collection<Resident> residents() {
        return Collections.unmodifiableCollection(residents.values());
    }

    public void addResident(Resident r) {
        indexResident(r);
        save(r);
    }

    public void renameResident(Resident r, String newName) {
        residentsByName.remove(norm(r.name()));
        r.name(newName);
        residentsByName.put(norm(newName), r);
        save(r);
    }

    public void save(Resident r) {
        saves.save(RESIDENTS, r);
    }

    // --- towns ----------------------------------------------------------------------------------

    private void indexTown(Town t) {
        towns.put(t.id(), t);
        townsByName.put(norm(t.name()), t);
    }

    public Town town(String id) {
        return id == null ? null : towns.get(id);
    }

    public Town townByName(String name) {
        return townsByName.get(norm(name));
    }

    public Collection<Town> towns() {
        return Collections.unmodifiableCollection(towns.values());
    }

    public List<Town> towns(Civilization civ) {
        List<Town> result = new ArrayList<>();
        for (Town t : towns.values()) if (civ.id().equals(t.civId())) result.add(t);
        return result;
    }

    public Town townOf(Resident r) {
        return r == null ? null : town(r.townId());
    }

    public Town townOf(Player p) {
        return townOf(resident(p));
    }

    public void addTown(Town t) {
        indexTown(t);
        save(t);
    }

    public void renameTown(Town t, String newName) {
        townsByName.remove(norm(t.name()));
        t.name(newName);
        townsByName.put(norm(newName), t);
        save(t);
    }

    public void removeTown(Town t) {
        towns.remove(t.id());
        townsByName.remove(norm(t.name()));
        claims.values().removeIf(c -> {
            if (c.townId().equals(t.id())) {
                saves.delete(CLAIMS, c.storageId());
                return true;
            }
            return false;
        });
        for (Resident r : residents.values()) {
            if (t.id().equals(r.townId())) {
                r.townId(null);
                save(r);
            }
            if (t.id().equals(r.selectedTownId())) {
                r.selectedTownId(null);
                save(r);
            }
        }
        saves.delete(TOWNS, t.id());
    }

    public void save(Town t) {
        saves.save(TOWNS, t);
    }

    // --- civilizations --------------------------------------------------------------------------

    private void indexCiv(Civilization c) {
        civs.put(c.id(), c);
        civsByName.put(norm(c.name()), c);
        if (c.tag() != null) civsByTag.put(norm(c.tag()), c);
    }

    public Civilization civ(String id) {
        return id == null ? null : civs.get(id);
    }

    public Civilization civByName(String name) {
        Civilization c = civsByName.get(norm(name));
        return c != null ? c : civsByTag.get(norm(name));
    }

    public Civilization civByTag(String tag) {
        return civsByTag.get(norm(tag));
    }

    public Collection<Civilization> civs() {
        return Collections.unmodifiableCollection(civs.values());
    }

    public Civilization civOf(Town t) {
        return t == null ? null : civ(t.civId());
    }

    public Civilization civOf(Resident r) {
        return civOf(townOf(r));
    }

    public Civilization civOf(Player p) {
        return civOf(resident(p));
    }

    public Town capital(Civilization c) {
        return town(c.capitalId());
    }

    public void addCiv(Civilization c) {
        indexCiv(c);
        save(c);
    }

    public void renameCiv(Civilization c, String newName, String newTag) {
        civsByName.remove(norm(c.name()));
        if (c.tag() != null) civsByTag.remove(norm(c.tag()));
        c.name(newName);
        c.tag(newTag);
        indexCiv(c);
        save(c);
    }

    public void removeCiv(Civilization c) {
        civs.remove(c.id());
        civsByName.remove(norm(c.name()));
        if (c.tag() != null) civsByTag.remove(norm(c.tag()));
        relations.values().removeIf(r -> {
            if (r.involves(c.id())) {
                saves.delete(RELATIONS, r.storageId());
                return true;
            }
            return false;
        });
        saves.delete(CIVS, c.id());
    }

    public void save(Civilization c) {
        saves.save(CIVS, c);
    }

    // --- claims ---------------------------------------------------------------------------------

    public Claim claim(ChunkKey key) {
        return claims.get(key);
    }

    public Collection<Claim> claims() {
        return Collections.unmodifiableCollection(claims.values());
    }

    public List<Claim> claims(Town t) {
        List<Claim> result = new ArrayList<>();
        for (Claim c : claims.values()) if (c.townId().equals(t.id())) result.add(c);
        return result;
    }

    public int claimCount(Town t) {
        int n = 0;
        for (Claim c : claims.values()) if (c.townId().equals(t.id())) n++;
        return n;
    }

    public void addClaim(Claim c) {
        claims.put(c.chunk(), c);
        save(c);
    }

    public void removeClaim(Claim c) {
        claims.remove(c.chunk());
        saves.delete(CLAIMS, c.storageId());
    }

    public void save(Claim c) {
        saves.save(CLAIMS, c);
    }

    // --- camps ----------------------------------------------------------------------------------

    private void indexCamp(Camp c) {
        camps.put(c.id(), c);
        campsByName.put(norm(c.name()), c);
    }

    public Camp camp(String id) {
        return id == null ? null : camps.get(id);
    }

    public Camp campByName(String name) {
        return campsByName.get(norm(name));
    }

    public Collection<Camp> camps() {
        return Collections.unmodifiableCollection(camps.values());
    }

    public void addCamp(Camp c) {
        indexCamp(c);
        save(c);
    }

    public void removeCamp(Camp c) {
        camps.remove(c.id());
        campsByName.remove(norm(c.name()));
        for (Resident r : residents.values()) {
            if (c.id().equals(r.campId())) {
                r.campId(null);
                save(r);
            }
        }
        saves.delete(CAMPS, c.id());
    }

    public void save(Camp c) {
        saves.save(CAMPS, c);
    }

    // --- relations ------------------------------------------------------------------------------

    public RelationType relation(String civA, String civB) {
        if (civA == null || civB == null) return RelationType.NEUTRAL;
        if (civA.equals(civB)) return RelationType.ALLY;
        Relation r = relations.get(Relation.key(civA, civB));
        return r == null ? RelationType.NEUTRAL : r.type();
    }

    public Optional<Relation> relationObject(String civA, String civB) {
        return Optional.ofNullable(relations.get(Relation.key(civA, civB)));
    }

    public Collection<Relation> relations() {
        return Collections.unmodifiableCollection(relations.values());
    }

    public List<Relation> relations(Civilization c) {
        List<Relation> result = new ArrayList<>();
        for (Relation r : relations.values()) if (r.involves(c.id())) result.add(r);
        return result;
    }

    /** Sets or clears (NEUTRAL) a relation. */
    public void setRelation(String civA, String civB, RelationType type, String aggressor) {
        String key = Relation.key(civA, civB);
        if (type == RelationType.NEUTRAL) {
            if (relations.remove(key) != null) saves.delete(RELATIONS, key);
            return;
        }
        Relation r = relations.get(key);
        if (r == null) {
            r = new Relation(civA, civB, type, aggressor);
            relations.put(key, r);
        } else {
            r.type(type, aggressor);
        }
        saves.save(RELATIONS, r);
    }

    public void save(Relation r) {
        saves.save(RELATIONS, r);
    }

    public SaveQueue saves() {
        return saves;
    }
}
