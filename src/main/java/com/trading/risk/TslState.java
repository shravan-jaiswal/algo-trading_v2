package com.trading.risk;

/**
 * Sealed TSL state — direction is encoded in the type, not a magic flag.
 * AtomicReference<TslState> in RiskManager gives lock-free thread safety.
 */
public sealed interface TslState permits TslState.Long, TslState.Short {

    double stop();

    record Long(double stop, double peak) implements TslState {

        /** Advance peak and trail stop upward; immutable — returns new instance. */
        public Long update(double currentPrice, double trailPct) {
            if (currentPrice <= peak) {
                // Price did not make new high — stop unchanged
                return this;
            }
            double newStop = currentPrice - currentPrice * trailPct;
            return new Long(Math.max(stop, newStop), currentPrice);
        }

        public boolean isHit(double currentPrice) { return currentPrice <= stop; }
    }

    record Short(double stop, double trough) implements TslState {

        /** Advance trough and trail stop downward; immutable — returns new instance. */
        public Short update(double currentPrice, double trailPct) {
            if (currentPrice >= trough) {
                return this;
            }
            double newStop = currentPrice + currentPrice * trailPct;
            return new Short(Math.min(stop, newStop), currentPrice);
        }

        public boolean isHit(double currentPrice) { return currentPrice >= stop; }
    }

    static Long  initLong(double entryPrice, double trailPct) {
        double stop = entryPrice - entryPrice * trailPct;
        return new Long(stop, entryPrice);
    }

    static Short initShort(double entryPrice, double trailPct) {
        double stop = entryPrice + entryPrice * trailPct;
        return new Short(stop, entryPrice);
    }
}
