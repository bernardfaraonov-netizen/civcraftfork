package com.civcraft.resident;

import com.civcraft.CivCraft;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.model.Camp;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Name resolution for commands: exact match (case-insensitive), then the same text converted from
 * the Russian layout, then a unique prefix. Plain string comparison only — user input never becomes
 * a regular expression (legacy DoS, audit C-17).
 */
public final class Lookup {

    private Lookup() {
    }

    public static Town town(String input) throws CivException {
        CivCraft civ = CivCraft.get();
        Town t = find(input, civ.state()::townByName, civ.state().towns(), Town::name);
        if (t == null) throw new CivException("error.unknown-town", Messages.arg("name", input));
        return t;
    }

    public static Civilization civ(String input) throws CivException {
        CivCraft civ = CivCraft.get();
        Civilization c = find(input, civ.state()::civByName, civ.state().civs(), Civilization::name);
        if (c == null) c = find(input, civ.state()::civByTag, civ.state().civs(), x -> x.tag() == null ? "" : x.tag());
        if (c == null) throw new CivException("error.unknown-civ", Messages.arg("name", input));
        return c;
    }

    public static Camp camp(String input) throws CivException {
        CivCraft civ = CivCraft.get();
        Camp c = find(input, civ.state()::campByName, civ.state().camps(), Camp::name);
        if (c == null) throw new CivException("camp.unknown", Messages.arg("name", input));
        return c;
    }

    /** Known resident (online or offline). Online players are preferred for prefix matches. */
    public static Resident resident(String input) throws CivException {
        CivCraft civ = CivCraft.get();
        Resident exact = civ.state().residentByName(input);
        if (exact != null) return exact;
        String latin = Layout.toLatin(input);
        exact = civ.state().residentByName(latin);
        if (exact != null) return exact;
        List<Resident> online = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Resident r = civ.state().resident(p);
            if (r != null) online.add(r);
        }
        Resident r = prefix(latin, online, Resident::name);
        if (r == null) r = prefix(latin, civ.state().residents(), Resident::name);
        if (r == null) throw new CivException("error.unknown-player", Messages.arg("name", input));
        return r;
    }

    private static <T> T find(String input, Function<String, T> exact, Collection<T> all, Function<T, String> name) {
        if (input == null || input.isBlank()) return null;
        T t = exact.apply(input);
        if (t != null) return t;
        String latin = Layout.toLatin(input);
        if (!latin.equals(input)) {
            t = exact.apply(latin);
            if (t != null) return t;
        }
        return prefix(latin, all, name);
    }

    /** Unique case-insensitive prefix match; null when none or ambiguous. */
    private static <T> T prefix(String input, Collection<T> all, Function<T, String> name) {
        String p = input.toLowerCase(Locale.ROOT);
        T found = null;
        for (T t : all) {
            String n = name.apply(t);
            if (n == null) continue;
            String lower = n.toLowerCase(Locale.ROOT);
            if (lower.equals(p)) return t;
            if (lower.startsWith(p)) {
                if (found != null) return null;
                found = t;
            }
        }
        return found;
    }

    /** Resolves one of the given option ids (e.g. an upgrade or government) by id, display name or prefix. */
    public static <T> T option(String input, Collection<T> all, Function<T, String> id, Function<T, String> display) {
        if (input == null) return null;
        String in = input.trim().toLowerCase(Locale.ROOT);
        String latin = Layout.toLatin(in);
        for (T t : all) {
            if (id.apply(t).equalsIgnoreCase(in) || id.apply(t).equalsIgnoreCase(latin)) return t;
            if (display.apply(t).equalsIgnoreCase(in)) return t;
        }
        T found = null;
        for (T t : all) {
            String d = display.apply(t).toLowerCase(Locale.ROOT);
            String i = id.apply(t).toLowerCase(Locale.ROOT);
            if (d.startsWith(in) || i.startsWith(in) || i.startsWith(latin)) {
                if (found != null && found != t) return null;
                found = t;
            }
        }
        return found;
    }
}
