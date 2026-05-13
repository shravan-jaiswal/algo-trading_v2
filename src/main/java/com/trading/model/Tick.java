package com.trading.model;

import java.time.LocalDateTime;

public record Tick(
        String        token,
        double        ltp,
        double        open,
        double        high,
        double        low,
        double        close,
        double        volume,
        LocalDateTime ts
) {
    public static Tick ltpOnly(String token, double ltp) {
        return new Tick(token, ltp, 0, 0, 0, 0, 0, LocalDateTime.now());
    }
}
