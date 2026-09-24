package com.civcraft.core.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Money is stored as a {@code long} number of hundredths of a coin. The legacy plugin used
 * {@code double}, which accumulated rounding errors in taxes and upkeep and allowed fractional dupes.
 */
public final class Money {

    public static final long SCALE = 100;
    private static final ThreadLocal<DecimalFormat> FORMAT = ThreadLocal.withInitial(() -> {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator('.');
        return new DecimalFormat("#,##0.##", symbols);
    });

    private Money() {
    }

    public static long ofCoins(double coins) {
        return Math.round(coins * SCALE);
    }

    public static double toCoins(long cents) {
        return cents / (double) SCALE;
    }

    public static String format(long cents) {
        return FORMAT.get().format(toCoins(cents));
    }

    /** Multiplies an amount by a rate, rounding half-up to the nearest cent. */
    public static long multiply(long cents, double rate) {
        return Math.round(cents * rate);
    }

    /** Parses a user supplied amount of coins; returns -1 for anything that is not a positive, finite number. */
    public static long parsePositive(String input) {
        try {
            double value = Double.parseDouble(input.replace(',', '.'));
            if (!Double.isFinite(value) || value <= 0 || value > 1e13) {
                return -1;
            }
            long cents = ofCoins(value);
            return cents > 0 ? cents : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
