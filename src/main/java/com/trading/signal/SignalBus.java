package com.trading.signal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Lightweight synchronous event bus for signal fan-out.
 * Listeners are called in subscription order on the publisher's thread.
 * Exceptions in one listener do not prevent others from running.
 */
public final class SignalBus {

    private static final Logger log = LoggerFactory.getLogger(SignalBus.class);

    private final List<Consumer<SignalEvent>> listeners = new CopyOnWriteArrayList<>();

    public void subscribe(Consumer<SignalEvent> listener) {
        listeners.add(listener);
    }

    public void publish(SignalEvent event) {
        if (event == null || !event.isActionable()) return;
        for (Consumer<SignalEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.error("SignalBus listener error for event {}: {}", event, e.getMessage(), e);
            }
        }
    }
}
