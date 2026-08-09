package com.trading.backtest;

import com.trading.strategy.RSIStrategy;
import com.trading.strategy.scalping.MomentumScalpingStrategy;
import com.trading.strategy.smc.SmcLiquiditySweepStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class BacktestRunnerTest {

    @Test
    void buildsEveryNewlySupportedStrategy() {
        assertInstanceOf(RSIStrategy.class, BacktestRunner.buildStrategy("RSI"));
        assertInstanceOf(MomentumScalpingStrategy.class, BacktestRunner.buildStrategy("SCALPING"));
        assertInstanceOf(SmcLiquiditySweepStrategy.class, BacktestRunner.buildStrategy("SMC"));
    }

    @Test
    void selectsTheStrategyNativeTimeframe() {
        assertEquals("ONE_MINUTE", BacktestRunner.defaultTimeframe("SCALPING"));
        assertEquals("ONE_MINUTE", BacktestRunner.defaultTimeframe("smc"));
        assertEquals("FIVE_MINUTE", BacktestRunner.defaultTimeframe("VSRSI"));
    }
}
