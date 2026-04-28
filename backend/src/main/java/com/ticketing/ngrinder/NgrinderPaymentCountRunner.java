package com.ticketing.ngrinder;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Runs an nGrinder test in "payment-count" mode:
 * start a long-running test, then stop it once the desired number of payments
 * (success + failure settlements) has been observed in app metrics.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NgrinderPaymentCountRunner {
    private final MeterRegistry meterRegistry;
    private final NgrinderClient ngrinderClient;

    public void stopOnTimeout(long testId, long timeoutMs) {
        final long timeout = Math.max(timeoutMs, 5_000L);
        final long startedAt = System.currentTimeMillis();

        Thread.startVirtualThread(() -> {
            try {
                while (true) {
                    long elapsed = System.currentTimeMillis() - startedAt;
                    if (elapsed >= timeout) {
                        log.warn("Stopping nGrinder testId={} due to timeout: elapsedMs={}", testId, elapsed);
                        safeStop(testId);
                        return;
                    }

                    // If test already finished/canceled, stop monitoring.
                    try {
                        JsonNode st = ngrinderClient.getStatus(testId);
                        String name = st == null ? null : st.path("status").path("name").asText(null);
                        if (name != null && (name.equalsIgnoreCase("FINISHED") || name.equalsIgnoreCase("STOPPED") || name.equalsIgnoreCase("CANCELED"))) {
                            log.info("Stop monitoring testId={} because status={}", testId, name);
                            return;
                        }
                    } catch (Exception ignored) {
                        // ignore status polling errors
                    }

                    Thread.sleep(1_000);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("TimeoutStopRunner failed for testId={}: {}", testId, e.getMessage());
            }
        });
    }

    private void safeStop(long testId) {
        try {
            ngrinderClient.stop(testId);
        } catch (Exception e) {
            log.warn("Failed to stop nGrinder testId={}: {}", testId, e.getMessage());
        }
    }

    // kept for possible future "metric-based stop" use
    @SuppressWarnings("unused")
    private double paymentSettledTotal() {
        return counter("ticketing.payment.succeeded.total") + counter("ticketing.payment.failed.total");
    }

    @SuppressWarnings("unused")
    private double counter(String name) {
        Counter c = meterRegistry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }
}

