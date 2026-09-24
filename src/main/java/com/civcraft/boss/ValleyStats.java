package com.civcraft.boss;

import com.civcraft.CivCraft;
import com.civcraft.storage.Stored;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Valley tops (spec 04 §11.2): kills, deaths, K/D, damage per player; boss victories per civilization. */
final class ValleyStats {

    static final String PLAYERS = "valley_stats";
    static final String CIVS = "valley_civ_stats";

    static final class PlayerStat implements Stored {
        UUID uuid;
        String name;
        int kills;
        int deaths;
        double damage;

        PlayerStat() {
        }

        PlayerStat(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        @Override
        public String storageId() {
            return uuid.toString();
        }

        double kd() {
            return deaths == 0 ? kills : kills / (double) deaths;
        }
    }

    static final class CivStat implements Stored {
        String civId;
        String name;
        int wins;

        CivStat() {
        }

        CivStat(String civId, String name) {
            this.civId = civId;
            this.name = name;
        }

        @Override
        public String storageId() {
            return civId;
        }
    }

    private final CivCraft civ;
    private final Map<UUID, PlayerStat> players = new HashMap<>();
    private final Map<String, CivStat> civs = new HashMap<>();

    ValleyStats(CivCraft civ) {
        this.civ = civ;
    }

    void load() {
        civ.store().createCollection(PLAYERS);
        civ.store().createCollection(CIVS);
        for (PlayerStat s : civ.store().loadAll(PLAYERS, PlayerStat.class)) if (s.uuid != null) players.put(s.uuid, s);
        for (CivStat s : civ.store().loadAll(CIVS, CivStat.class)) if (s.civId != null) civs.put(s.civId, s);
    }

    private PlayerStat stat(UUID uuid, String name) {
        PlayerStat s = players.computeIfAbsent(uuid, u -> new PlayerStat(u, name));
        s.name = name;
        return s;
    }

    void kill(UUID uuid, String name) {
        PlayerStat s = stat(uuid, name);
        s.kills++;
        civ.saves().save(PLAYERS, s);
    }

    void death(UUID uuid, String name) {
        PlayerStat s = stat(uuid, name);
        s.deaths++;
        civ.saves().save(PLAYERS, s);
    }

    void damage(UUID uuid, String name, double amount) {
        if (!(amount > 0) || !Double.isFinite(amount)) return;
        PlayerStat s = stat(uuid, name);
        s.damage += amount;
        civ.saves().save(PLAYERS, s);
    }

    void win(String civId, String name) {
        CivStat s = civs.computeIfAbsent(civId, id -> new CivStat(id, name));
        s.name = name;
        s.wins++;
        civ.saves().save(CIVS, s);
    }

    List<PlayerStat> top(String kind, int limit) {
        Comparator<PlayerStat> order = switch (kind) {
            case "deaths" -> Comparator.comparingInt((PlayerStat s) -> s.deaths);
            case "kd" -> Comparator.comparingDouble(PlayerStat::kd);
            case "damage" -> Comparator.comparingDouble((PlayerStat s) -> s.damage);
            default -> Comparator.comparingInt((PlayerStat s) -> s.kills);
        };
        List<PlayerStat> list = new ArrayList<>(players.values());
        list.sort(order.reversed());
        return list.subList(0, Math.min(limit, list.size()));
    }

    List<CivStat> topCivs(int limit) {
        List<CivStat> list = new ArrayList<>(civs.values());
        list.sort(Comparator.comparingInt((CivStat s) -> s.wins).reversed());
        return list.subList(0, Math.min(limit, list.size()));
    }

    /** Resets all valley statistics (new phase). */
    void reset() {
        for (UUID id : players.keySet()) civ.saves().delete(PLAYERS, id.toString());
        for (String id : civs.keySet()) civ.saves().delete(CIVS, id);
        players.clear();
        civs.clear();
    }
}
