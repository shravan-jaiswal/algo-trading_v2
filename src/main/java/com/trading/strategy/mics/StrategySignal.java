package com.trading.strategy.mics;

public sealed interface StrategySignal
        permits StrategySignal.BullSignal,
                StrategySignal.BearSignal,
                StrategySignal.NeutralSignal {

    int    confluenceScore();
    double suggestedStopLoss();
    HedgeMode hedgeMode();
    String reason();

    record BullSignal(
            int       confluenceScore,
            double    suggestedStopLoss,
            HedgeMode hedgeMode,
            String    reason
    ) implements StrategySignal {}

    record BearSignal(
            int       confluenceScore,
            double    suggestedStopLoss,
            HedgeMode hedgeMode,
            String    reason
    ) implements StrategySignal {}

    record NeutralSignal(String reason) implements StrategySignal {
        @Override public int       confluenceScore()    { return 0; }
        @Override public double    suggestedStopLoss()  { return -1; }
        @Override public HedgeMode hedgeMode()          { return HedgeMode.EQUITY; }
    }

    static NeutralSignal neutral(String reason) { return new NeutralSignal(reason); }
}
