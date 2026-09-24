package com.civcraft.economy;

import com.civcraft.core.CivException;
import com.civcraft.core.util.Money;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * Parses money amounts typed by players (spec §1): plain numbers plus combinable suffixes
 * {@code h/с} ×100, {@code k/t/т} ×1 000, {@code m/м/кк} ×1 000 000. {@code 2k5h} = 2 500, {@code 5h5h} = 1 000.
 * Rejects zero, negatives, NaN, infinities and absurdly large values.
 */
public final class Amounts {

    /** Upper bound of a single typed amount, in coins. */
    public static final BigDecimal MAX_COINS = new BigDecimal("10000000000000");

    private Amounts() {
    }

    /** Amount in hundredths, or -1 when the input is not a positive, finite amount. */
    public static long parse(String input) {
        if (input == null) return -1;
        String s = input.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
        if (s.isEmpty()) return -1;
        BigDecimal total = BigDecimal.ZERO;
        int i = 0;
        boolean any = false;
        while (i < s.length()) {
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == ',')) i++;
            if (start == i) return -1;
            String number = s.substring(start, i).replace(',', '.');
            BigDecimal value;
            try {
                value = new BigDecimal(number);
            } catch (NumberFormatException e) {
                return -1;
            }
            long multiplier = 1;
            if (i < s.length()) {
                if (s.startsWith("кк", i)) {
                    multiplier = 1_000_000;
                    i += 2;
                } else {
                    char c = s.charAt(i);
                    switch (c) {
                        case 'h', 'с' -> multiplier = 100;
                        case 'k', 't', 'т', 'к' -> multiplier = 1_000;
                        case 'm', 'м' -> multiplier = 1_000_000;
                        default -> {
                            return -1;
                        }
                    }
                    i++;
                }
            } else if (any) {
                // A trailing bare number after suffixed groups ("2k500") is added as is.
                multiplier = 1;
            }
            total = total.add(value.multiply(BigDecimal.valueOf(multiplier)));
            any = true;
            if (total.compareTo(MAX_COINS) > 0) return -1;
        }
        if (total.signum() <= 0) return -1;
        long cents = total.multiply(BigDecimal.valueOf(Money.SCALE)).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        return cents > 0 ? cents : -1;
    }

    /** Like {@link #parse} but throws a localized error. */
    public static long require(String input) throws CivException {
        long cents = parse(input);
        if (cents <= 0) throw new CivException("error.invalid-amount");
        return cents;
    }

    /** Parses a percentage "15", "15%", "0.15" is treated as 0.15 %. Returns a fraction 0..1 or NaN. */
    public static double percent(String input) {
        if (input == null) return Double.NaN;
        String s = input.trim().replace("%", "").replace(',', '.');
        try {
            double v = Double.parseDouble(s);
            if (!Double.isFinite(v) || v < 0 || v > 100) return Double.NaN;
            return v / 100.0;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Positive integer count (item amounts etc.), or -1. */
    public static int count(String input, int max) {
        try {
            int v = Integer.parseInt(input.trim());
            return v > 0 && v <= max ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
