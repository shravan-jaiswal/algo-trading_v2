package com.trading.risk;

/**
 * Sealed TSL state — direction is encoded in the type, not a magic flag.
 * AtomicReference<TslState> in RiskManager gives lock-free thread safety.
 */
public sealed interface TslState permits TslState.Long, TslState.Short, TslState.LongStep {

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

    record LongStep(double stop, double peak, double entryPrice,
                    double initialStopPct, double triggerStepPct,
                    double stopStepPct) implements TslState {

        public LongStep update(double currentPrice) {
            if (currentPrice <= peak || entryPrice <= 0 || triggerStepPct <= 0 || stopStepPct <= 0) {
                return this;
            }
            double movePct = (currentPrice - entryPrice) / entryPrice;
            int steps = (int) Math.floor((movePct + 1e-9) / triggerStepPct);
            if (steps <= 0) return new LongStep(stop, currentPrice, entryPrice,
                    initialStopPct, triggerStepPct, stopStepPct);

            double initialStop = entryPrice - entryPrice * initialStopPct;
            double steppedStop = initialStop + steps * entryPrice * stopStepPct;
            return new LongStep(Math.max(stop, steppedStop), currentPrice, entryPrice,
                    initialStopPct, triggerStepPct, stopStepPct);
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

    static LongStep initLongStep(double entryPrice, double initialStopPct,
                                 double triggerStepPct, double stopStepPct) {
        double stop = entryPrice - entryPrice * initialStopPct;
        return new LongStep(stop, entryPrice, entryPrice, initialStopPct,
                triggerStepPct, stopStepPct);
    }

    static Short initShort(double entryPrice, double trailPct) {
        double stop = entryPrice + entryPrice * trailPct;
        return new Short(stop, entryPrice);
    }
}
