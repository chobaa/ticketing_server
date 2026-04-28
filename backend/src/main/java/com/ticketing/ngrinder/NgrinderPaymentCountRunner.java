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

    /**
     * Safety stop: if the app's settled delta reaches target (and inflight becomes 0),
     * stop the nGrinder test even if the script doesn't self-terminate.
     */
    public void stopWhenSettledReached(long testId, long targetSettledDelta, long baselineSettledTotal, long timeoutMs) {
        final long timeout = Math.max(timeoutMs, 5_000L);
        final long startedAt = System.currentTimeMillis();

        Thread.startVirtualThread(() -> {
            try {
                while (true) {
                    long elapsed = System.currentTimeMillis() - startedAt;
                    if (elapsed >= timeout) {
                        log.warn("Stopping nGrinder testId={} due to settledStop timeout: elapsedMs={}", testId, elapsed);
                        safeStop(testId);
                        return;
                    }

                    // If test already finished/canceled, stop monitoring.
                    try {
                        JsonNode st = ngrinderClient.getStatus(testId);
                        String name = st == null ? null : st.path("status").path("name").asText(null);
                        if (name != null && (name.equalsIgnoreCase("FINISHED") || name.equalsIgnoreCase("STOPPED") || name.equalsIgnoreCase("CANCELED"))) {
                            log.info("Stop monitoring settledStop testId={} because status={}", testId, name);
                            return;
                        }
                    } catch (Exception ignored) {
                        // ignore status polling errors
                    }

                    long settled = Math.round(paymentSettledTotal());
                    long settledDelta = Math.max(0, settled - baselineSettledTotal);
                    long inflight = Math.max(0, Math.round(paymentInflight()));
                    if (settledDelta >= targetSettledDelta && inflight == 0) {
                        log.info(
                                "Stopping nGrinder testId={} because settledDelta reached: delta={} target={} inflight={}",
                                testId,
                                settledDelta,
                                targetSettledDelta,
                                inflight);
                        safeStop(testId);
                        return;
                    }

                    Thread.sleep(1_000);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("SettledStopRunner failed for testId={}: {}", testId, e.getMessage());
            }
        });
    }

    public long currentSettledTotalRounded() {
        return Math.round(paymentSettledTotal());
    }

    public long currentRequestedTotalRounded() {
        return Math.round(counter("ticketing.payment.requested.total"));
    }

    /**
     * Stop test when payment-requested delta reaches target (approximate "issued payments" count).
     * This matches the user's intention of "발행량 기준으로 N개 근처에서 멈추기".
     */
    public void stopWhenRequestedReached(long testId, long targetRequestedDelta, long baselineRequestedTotal, long timeoutMs) {
        final long timeout = Math.max(timeoutMs, 5_000L);
        final long startedAt = System.currentTimeMillis();

        Thread.startVirtualThread(() -> {
            try {
                while (true) {
                    long elapsed = System.currentTimeMillis() - startedAt;
                    if (elapsed >= timeout) {
                        log.warn("Stopping nGrinder testId={} due to requestedStop timeout: elapsedMs={}", testId, elapsed);
                        safeStop(testId);
                        return;
                    }

                    // If test already finished/canceled, stop monitoring.
                    try {
                        JsonNode st = ngrinderClient.getStatus(testId);
                        String name = st == null ? null : st.path("status").path("name").asText(null);
                        if (name != null && (name.equalsIgnoreCase("FINISHED") || name.equalsIgnoreCase("STOPPED") || name.equalsIgnoreCase("CANCELED"))) {
                            log.info("Stop monitoring requestedStop testId={} because status={}", testId, name);
                            return;
                        }
                    } catch (Exception ignored) {
                        // ignore status polling errors
                    }

                    long requested = currentRequestedTotalRounded();
                    long requestedDelta = Math.max(0, requested - baselineRequestedTotal);
                    if (requestedDelta >= targetRequestedDelta) {
                        log.info(
                                "Stopping nGrinder testId={} because requestedDelta reached: delta={} target={}",
                                testId,
                                requestedDelta,
                                targetRequestedDelta);
                        safeStop(testId);
                        return;
                    }

                    // Poll frequently to reduce overshoot; requested can increase very fast under load.
                    Thread.sleep(50);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("RequestedStopRunner failed for testId={}: {}", testId, e.getMessage());
            }
        });
    }

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
    private double paymentSettledTotal() {
        return counter("ticketing.payment.succeeded.total") + counter("ticketing.payment.failed.total");
    }

    private double paymentInflight() {
        // prefer the gauge if present, but fall back to requested - settled
        io.micrometer.core.instrument.Gauge g = meterRegistry.find("ticketing.payment.inflight").gauge();
        if (g != null) return g.value();
        return Math.max(0.0, counter("ticketing.payment.requested.total") - paymentSettledTotal());
    }

    private double counter(String name) {
        Counter c = meterRegistry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }
}

