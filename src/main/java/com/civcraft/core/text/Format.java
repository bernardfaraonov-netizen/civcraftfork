package com.civcraft.core.text;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class Format {

    private static final ThreadLocal<DecimalFormat> NUMBER = ThreadLocal.withInitial(() -> {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0.##", symbols);
    });

    private Format() {
    }

    public static String number(double value) {
        return NUMBER.get().format(value);
    }

    public static String percent(double fraction) {
        return number(fraction * 100) + "%";
    }

    /** Signed percentage: +15% / -10%. */
    public static String signedPercent(double fraction) {
        return (fraction >= 0 ? "+" : "") + percent(fraction);
    }

    public static String progressBar(double fraction, int width) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, fraction)) * width);
        return "<green>" + "|".repeat(filled) + "<dark_gray>" + "|".repeat(width - filled);
    }
}
