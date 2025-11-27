package com.example.genai.genai_backend.ratelimit;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class SimpleRateLimiter {
    private final int maxRequests;
    private final long refillIntervalSeconds;
    private AtomicInteger tokens;
    private Instant lastRefill;

    public SimpleRateLimiter(int maxRequests, long refillIntervalSeconds) {
        this.maxRequests = maxRequests;
        this.refillIntervalSeconds = refillIntervalSeconds;
        this.tokens = new AtomicInteger(maxRequests);
        this.lastRefill = Instant.now();
    }

    private synchronized void refillIfNeeded() {
        Instant now = Instant.now();
        long elapsed = now.getEpochSecond() - lastRefill.getEpochSecond();
        if (elapsed >= refillIntervalSeconds) {
            tokens.set(maxRequests);
            lastRefill = now;
        }
    }

    public boolean tryConsume() {
        refillIfNeeded();
        while (true) {
            int current = tokens.get();
            if (current <= 0) return false;
            if (tokens.compareAndSet(current, current - 1)) return true;
        }
    }
}