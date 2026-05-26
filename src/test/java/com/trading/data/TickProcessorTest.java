package com.trading.data;

import com.trading.model.Candle;
import com.trading.model.Tick;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class TickProcessorTest {

    @Test
    void opensCurrentSlotAndClosesPreviousOnBoundaryTick() {
        TickProcessor processor = new TickProcessor(5, null);

        LocalDateTime t1405 = LocalDateTime.of(2026, 5, 19, 14, 5, 30);
        LocalDateTime t1410 = LocalDateTime.of(2026, 5, 19, 14, 10, 1);

        assertNull(processor.process(new Tick("1333", 100, 0, 0, 0, 0, 0, t1405)));
        assertEquals(LocalDateTime.of(2026, 5, 19, 14, 5), processor.getCandles("1333").get(0).getTs());

        Candle closed = processor.process(new Tick("1333", 101, 0, 0, 0, 0, 0, t1410));

        assertNotNull(closed);
        assertEquals(LocalDateTime.of(2026, 5, 19, 14, 5), closed.getTs());
        assertEquals(LocalDateTime.of(2026, 5, 19, 14, 10),
                processor.getCandles("1333").get(1).getTs());
        assertEquals(1, processor.getCompletedCandles("1333").size());
        assertEquals(LocalDateTime.of(2026, 5, 19, 14, 5),
                processor.getCompletedCandles("1333").get(0).getTs());
        assertEquals(LocalDateTime.of(2026, 5, 19, 14, 10),
                processor.getCandles("1333").get(1).getTs());
    }

    @Test
    void flushCompletedMovesLiveCandleToCompletedWithoutNullSentinel() {
        TickProcessor processor = new TickProcessor(5, null);

        LocalDateTime oldSlot = LocalDateTime.of(2026, 5, 19, 14, 5, 30);

        processor.process(new Tick("1333", 100, 0, 0, 0, 0, 10, oldSlot));

        var flushed = processor.flushCompleted();

        assertEquals(1, flushed.size());
        assertEquals(1, processor.getCompletedCandles("1333").size());
        assertEquals(1, processor.getCandles("1333").size());
        assertEquals(LocalDateTime.of(2026, 5, 19, 14, 5),
                processor.getCompletedCandles("1333").get(0).getTs());
    }

    @Test
    void ignoresTicksOutsideRegularMarketSession() {
        TickProcessor processor = new TickProcessor(5, null);

        LocalDateTime afterClose = LocalDateTime.of(2026, 5, 19, 20, 30);

        assertNull(processor.process(new Tick("1333", 101, 0, 0, 0, 0, 13, afterClose)));
        assertTrue(processor.getCandles("1333").isEmpty());
    }

    @Test
    void doesNotOpenCandleAtMarketCloseBoundary() {
        TickProcessor processor = new TickProcessor(5, null);

        LocalDateTime closeBoundary = LocalDateTime.of(2026, 5, 19, 15, 30);

        assertNull(processor.process(new Tick("1333", 101, 0, 0, 0, 0, 13, closeBoundary)));
        assertTrue(processor.getCandles("1333").isEmpty());
    }
}
