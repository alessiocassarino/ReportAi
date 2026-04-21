package com.claude.reportAi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding window rate limiter per i token di Anthropic.
 * Mantiene traccia dei token usati nell'ultimo minuto e aggiunge
 * un'attesa quando si avvicina al limite configurato.
 */
@Component
@Slf4j
public class TokenRateLimiter {

    private static final int TOKENS_PER_MINUTE = 80_000;
    private static final long WINDOW_MS = 60_000L;

    private final Deque<long[]> usageWindow = new ArrayDeque<>(); // [timestamp, tokens]

    public synchronized void waitIfNeeded(int estimatedTokens) throws InterruptedException {
        long now = System.currentTimeMillis();
        evictOldEntries(now);

        int currentUsage = currentUsage();

        if (currentUsage + estimatedTokens > TOKENS_PER_MINUTE) {
            long oldestTimestamp = usageWindow.isEmpty() ? now : usageWindow.peekFirst()[0];
            long waitMs = WINDOW_MS - (now - oldestTimestamp) + 100;

            if (waitMs > 0) {
                log.info("Rate limit: utilizzo corrente={} + stimato={} > {}. Attesa {} ms",
                        currentUsage, estimatedTokens, TOKENS_PER_MINUTE, waitMs);
                Thread.sleep(waitMs);
            }
        }
    }

    public synchronized void recordUsage(int tokens) {
        usageWindow.addLast(new long[]{System.currentTimeMillis(), tokens});
    }

    private void evictOldEntries(long now) {
        while (!usageWindow.isEmpty() && now - usageWindow.peekFirst()[0] > WINDOW_MS) {
            usageWindow.pollFirst();
        }
    }

    private int currentUsage() {
        return usageWindow.stream().mapToInt(e -> (int) e[1]).sum();
    }
}