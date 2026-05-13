package com.trading.utils;

public final class Shared {

    private Shared() {}

    public static String fmt(double v)  { return String.format("%.2f", v); }
    public static String fmtPnl(double v) { return String.format("%+.2f", v); }
    public static String fmtPct(double v) { return String.format("%.2f%%", v * 100); }

    public static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    public static boolean isValid(double v) { return v > 0 && !Double.isNaN(v) && !Double.isInfinite(v); }
}
