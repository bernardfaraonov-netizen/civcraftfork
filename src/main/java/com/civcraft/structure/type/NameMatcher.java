package com.civcraft.structure.type;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Matches what players type ({@code /build кот}, {@code /b мировой рын}) against names and aliases. Scoring:
 * exact name 4, name prefix 3, every typed word is a prefix of a name word 2, substring 1. The best score wins;
 * several entries with the best score are an ambiguity the caller reports.
 */
public final class NameMatcher {

    private NameMatcher() {
    }

    /** Lower case, ё→е, punctuation and underscores to spaces, collapsed whitespace. */
    public static String normalize(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        StringBuilder sb = new StringBuilder(lower.length());
        boolean space = false;
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                sb.append(ch);
                space = false;
            } else if (!space && sb.length() > 0) {
                sb.append(' ');
                space = true;
            }
        }
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == ' ') sb.setLength(len - 1);
        return sb.toString();
    }

    /** Score of one candidate key against a normalized query; 0 = no match. */
    public static int score(String normalizedQuery, String key) {
        String k = normalize(key);
        if (normalizedQuery.isEmpty() || k.isEmpty()) return 0;
        if (k.equals(normalizedQuery)) return 4;
        if (k.startsWith(normalizedQuery)) return 3;
        if (wordPrefixes(normalizedQuery, k)) return 2;
        if (normalizedQuery.length() >= 3 && k.contains(normalizedQuery)) return 1;
        return 0;
    }

    private static boolean wordPrefixes(String query, String key) {
        String[] q = query.split(" ");
        String[] words = key.split(" ");
        int from = 0;
        outer:
        for (String part : q) {
            for (int i = from; i < words.length; i++) {
                if (words[i].startsWith(part)) {
                    from = i + 1;
                    continue outer;
                }
            }
            return false;
        }
        return true;
    }

    /**
     * Returns the candidates with the best score (empty if nothing matched). {@code keys} yields every name of a
     * candidate (id, display name, aliases).
     */
    public static <T> List<T> best(String query, Collection<T> candidates, Function<T, Collection<String>> keys) {
        String q = normalize(query);
        int bestScore = 0;
        Set<T> best = new LinkedHashSet<>();
        for (T candidate : candidates) {
            int s = 0;
            for (String key : keys.apply(candidate)) s = Math.max(s, score(q, key));
            if (s == 0) continue;
            if (s > bestScore) {
                bestScore = s;
                best.clear();
            }
            if (s == bestScore) best.add(candidate);
        }
        return new ArrayList<>(best);
    }
}
