package com.civcraft.economy;

import com.civcraft.CivCraft;
import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.effect.Modifier;
import com.civcraft.effect.StatSheet;
import com.civcraft.effect.Stats;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import com.civcraft.model.TownStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Hourly production figures of a town (spec §9–§12): happiness, hammers, beakers, culture, faith,
 * income and growth. All effect modifiers (buildings, government, nation, biomes, talents,
 * religion...) come from the {@link com.civcraft.effect.StatService}; the town module adds the
 * base values and the state-dependent unhappiness sources computed here.
 */
public final class Production {

    /** Extra stats introduced by this module (other modules contribute through effects). */
    public static final String CAPTURED_UNHAPPINESS = "captured_unhappiness";
    public static final String PLAYER_UNHAPPINESS = "player_unhappiness";
    public static final String DISTANCE_UNHAPPINESS = "distance_unhappiness";
    /** Multiplier on the captured-town science penalty (Paganism: ×0 = no penalty). */
    public static final String CAPTURED_SCIENCE_PENALTY = "captured_science_penalty";

    public record Line(String source, double value) {
    }

    public record State(String name, double multiplier) {
    }

    public record Figures(double happiness, double unhappiness, double happyPercent, State state,
                          double hammers, double beakers, double culture, double faith, double income, double growth,
                          List<Line> happinessLines, List<Line> unhappinessLines, double distance, boolean touchesCapital) {
    }

    private final CivCraft civ;
    private final NavigableMap<Double, State> states = new TreeMap<>();
    private final Map<String, Figures> cache = new HashMap<>();

    public Production(CivCraft civ) {
        this.civ = civ;
        ConfigurationSection s = civ.balance().section("core", "happiness.states");
        for (String key : s.getKeys(false)) {
            ConfigurationSection st = s.getConfigurationSection(key);
            if (st != null) states.put(Double.parseDouble(key), new State(st.getString("name", key), st.getDouble("multiplier", 1)));
        }
        if (states.isEmpty()) states.put(0.0, new State("—", 1.0));
    }

    /** Drops cached figures (every second and after any economic state change). */
    public void invalidate() {
        cache.clear();
    }

    public State stateFor(double happyPercent) {
        return EconomyMath.floorValue(states, happyPercent);
    }

    public NavigableMap<Double, State> states() {
        return states;
    }

    public Figures figures(Town town) {
        Figures f = cache.get(town.id());
        if (f == null) {
            f = compute(town);
            cache.put(town.id(), f);
        }
        return f;
    }

    private Figures compute(Town town) {
        StatSheet sheet = civ.stats().town(town);
        Civilization c = civ.state().civOf(town);
        List<Line> hLines = new ArrayList<>();
        List<Line> uLines = new ArrayList<>();

        // --- happiness points -------------------------------------------------------------------
        double base = civ.balance().getDouble("core", "happiness.base", 10);
        hLines.add(new Line("base", base));
        boolean capital = c != null && town.id().equals(c.capitalId());
        if (capital) hLines.add(new Line("capital", civ.balance().getDouble("core", "happiness.capital-bonus", 5)));
        for (Modifier m : sheet.breakdown(Stats.HAPPINESS)) hLines.add(new Line(m.source(), m.value()));
        double happiness = base + (capital ? civ.balance().getDouble("core", "happiness.capital-bonus", 5) : 0)
                + sheet.apply(Stats.HAPPINESS, 0);

        // --- unhappiness points -----------------------------------------------------------------
        double unhappiness = 0;
        if (c != null) {
            List<Town> towns = civ.state().towns(c);
            int own = 0;
            double captured = 0;
            for (Town t : towns) {
                if (t.status() == TownStatus.CAPTURED) {
                    Civilization nativeCiv = civ.state().civ(t.nativeCivId());
                    boolean sameNation = nativeCiv != null && c.nation() != null && c.nation().equals(nativeCiv.nation());
                    captured += sameNation ? civ.balance().getDouble("core", "happiness.captured-same-nation-unhappiness", 1)
                            : civ.balance().getDouble("core", "happiness.captured-town-unhappiness", 2);
                } else {
                    own++;
                }
            }
            double perTown = own * civ.balance().getDouble("core", "happiness.per-town-unhappiness", 1.25);
            uLines.add(new Line("towns", perTown));
            unhappiness += perTown;
            if (captured > 0) {
                double cu = sheet.apply(CAPTURED_UNHAPPINESS, captured);
                uLines.add(new Line("captured", cu));
                unhappiness += cu;
            }
        }
        int residents = town.residents().size();
        Set<UUID> outsiders = new HashSet<>();
        for (Set<UUID> group : town.groups().values()) {
            for (UUID id : group) if (!town.isResident(id)) outsiders.add(id);
        }
        double players = EconomyMath.playerUnhappiness(residents, civ.balance().getDouble("core", "happiness.resident-factor", 0.125))
                + EconomyMath.playerUnhappiness(outsiders.size(), civ.balance().getDouble("core", "happiness.non-resident-factor", 0.075));
        players = sheet.apply(PLAYER_UNHAPPINESS, players);
        uLines.add(new Line("players", players));
        unhappiness += players;

        double distance = 0;
        boolean touching = false;
        Town cap = c == null ? null : civ.state().capital(c);
        if (cap != null && !capital && cap.center() != null && town.center() != null
                && Objects.equals(cap.center().world(), town.center().world())) {
            distance = xzDistance(cap.center(), town.center());
            touching = touches(town, cap);
            double d = EconomyMath.distanceUnhappiness(distance, touching,
                    civ.balance().getDouble("core", "happiness.distance-factor", 0.01),
                    civ.balance().getDouble("core", "happiness.distance-exponent-touching", 0.75),
                    civ.balance().getDouble("core", "happiness.distance-exponent-far", 1.05));
            d = sheet.apply(DISTANCE_UNHAPPINESS, d);
            uLines.add(new Line("distance", d));
            unhappiness += d;
        }
        for (Modifier m : sheet.breakdown(Stats.UNHAPPINESS)) uLines.add(new Line(m.source(), m.value()));
        unhappiness += sheet.apply(Stats.UNHAPPINESS, 0);

        happiness = Math.max(0, happiness);
        unhappiness = Math.max(0, unhappiness);
        double percent = EconomyMath.happyPercent(happiness, unhappiness);
        State state = stateFor(percent);
        double mult = state.multiplier();

        // --- outputs ----------------------------------------------------------------------------
        boolean isCaptured = town.isCaptured();
        double capturedPenalty = civ.balance().getDouble("economy", "captured-multiplier", 0.5);
        double hammersRaw = Math.max(0, sheet.apply(Stats.HAMMERS, 0)) * mult * (isCaptured ? capturedPenalty : 1);
        double beakers = Math.max(0, sheet.apply(Stats.BEAKERS, 0)) * mult;
        if (isCaptured) beakers *= 1 - (1 - capturedPenalty) * Math.max(0, sheet.apply(CAPTURED_SCIENCE_PENALTY, 1.0));
        double hammers = hammersRaw;
        if (town.convertingHammers()) {
            beakers += EconomyMath.chammers(hammersRaw, civ.balance().getDouble("core", "town.chammers-divisor", 16));
            hammers = 0;
        }
        double culture = Math.max(0, sheet.apply(Stats.CULTURE, 0)) * mult;
        if (town.disbanding() || (town.inDebt() && civ.balance().file("economy").getBoolean("debt-stops-culture", true))) {
            culture = 0;
        }
        double faith = Math.max(0, sheet.apply(Stats.FAITH, 0)) * mult * (isCaptured ? capturedPenalty : 1);
        double income = town.disbanding() ? 0 : Math.max(0, sheet.apply(Stats.INCOME, 0)) * mult;
        double growth = Math.max(0, sheet.apply(Stats.GROWTH, 0)) * (isCaptured ? capturedPenalty : 1);
        return new Figures(happiness, unhappiness, percent, state, hammers, beakers, culture, faith, income, growth,
                hLines, uLines, distance, touching);
    }

    public static double xzDistance(BlockPos a, BlockPos b) {
        double dx = a.x() - b.x();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Whether any culture chunk of {@code town} is adjacent to (or shared with) the capital's culture. */
    public boolean touches(Town town, Town capital) {
        for (ChunkKey chunk : civ.culture().chunks(town)) {
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Town owner = civ.culture().owner(chunk.offset(d[0], d[1]));
                if (owner != null && owner.id().equals(capital.id())) return true;
            }
        }
        return false;
    }
}
