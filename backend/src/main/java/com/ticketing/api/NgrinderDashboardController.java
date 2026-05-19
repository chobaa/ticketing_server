package com.ticketing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ticketing.api.dto.CreateEventRequest;
import com.ticketing.event.Event;
import com.ticketing.event.EventService;
import com.ticketing.metrics.LoadTestRunAttributionService;
import com.ticketing.metrics.LoadTestRunProfile;
import com.ticketing.ngrinder.NgrinderClient;
import com.ticketing.ngrinder.NgrinderPaymentCountRunner;
import com.ticketing.payment.PaymentQueueMaintenanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/dashboard/ngrinder")
@RequiredArgsConstructor
public class NgrinderDashboardController {
    private final NgrinderClient ngrinderClient;
    private final NgrinderPaymentCountRunner paymentCountRunner;
    private final PaymentQueueMaintenanceService paymentQueueMaintenanceService;
    private final EventService eventService;
    private final LoadTestRunAttributionService loadTestRunAttribution;

    @Value("${ticketing.ngrinder.target-base-url:http://host.docker.internal:8080}")
    private String defaultTargetBaseUrl;

    /** Page is 0-based (same as nGrinder / Spring Data). */
    @GetMapping("/tests")
    public ResponseEntity<JsonNode> tests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        JsonNode raw = ngrinderClient.listTests(page, size);
        if (raw != null && raw.isArray()) {
            ObjectNode box = JsonNodeFactory.instance.objectNode();
            box.set("tests", raw);
            box.put("number", page);
            box.put("size", size);
            box.put("hasNext", raw.size() >= size);
            return ResponseEntity.ok(box);
        }
        return ResponseEntity.ok(raw);
    }

    @GetMapping("/tests/{id}/status")
    public ResponseEntity<JsonNode> status(@PathVariable long id) {
        return ResponseEntity.ok(ngrinderClient.getStatus(id));
    }

    @GetMapping("/tests/{id}/perf")
    public ResponseEntity<JsonNode> perf(
            @PathVariable long id,
            @RequestParam(defaultValue = "TPS,Errors,Mean_Test_Time") String dataType,
            @RequestParam(defaultValue = "800") int imgWidth) {
        return ResponseEntity.ok(ngrinderClient.getPerf(id, dataType, imgWidth));
    }

    @GetMapping("/tests/{id}/logs")
    public ResponseEntity<JsonNode> logs(@PathVariable long id) {
        return ResponseEntity.ok(ngrinderClient.getLogs(id));
    }

    @PostMapping("/tests/{id}/ready")
    public ResponseEntity<JsonNode> ready(@PathVariable long id) {
        return ResponseEntity.ok(ngrinderClient.putStatusReady(id));
    }

    @PostMapping("/tests/{id}/stop")
    public ResponseEntity<Void> stop(@PathVariable long id) {
        ngrinderClient.stop(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tests/{id}/clone-and-start")
    public ResponseEntity<JsonNode> cloneAndStart(@PathVariable long id) {
        return ResponseEntity.ok(ngrinderClient.cloneAndStart(id));
    }

    @PostMapping("/tests/delete-all")
    public ResponseEntity<Void> deleteAll() {
        // Iterate pages until empty; nGrinder API is 0-based.
        int page = 0;
        int size = 200;
        while (true) {
            JsonNode raw = ngrinderClient.listTests(page, size);
            JsonNode arr = null;
            if (raw != null && raw.isArray()) arr = raw;
            else if (raw != null && raw.has("tests") && raw.get("tests").isArray()) arr = raw.get("tests");
            if (arr == null || arr.isEmpty()) break;

            List<Long> ids = new ArrayList<>();
            for (JsonNode t : arr) {
                if (t != null && t.has("id") && t.get("id").canConvertToLong()) {
                    ids.add(t.get("id").asLong());
                }
            }
            ngrinderClient.deleteTests(ids);
            if (arr.size() < size) break;
            page++;
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/presets/{key}/start")
    public ResponseEntity<JsonNode> startPreset(
            @PathVariable String key,
            @RequestParam(required = false) String baseUrl,
            @RequestParam(required = false) Integer vusers,
            @RequestParam(required = false) Integer threads,
            @RequestParam(required = false) Integer testDurationSec,
            @RequestParam(required = false) Integer eventSeatCount,
            @RequestParam(required = false) Integer seatPoolSize) {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = defaultTargetBaseUrl;
        }
        // nGrinder requires the script to exist in controller's script repository.
        // If scripts are not uploaded, return a helpful message (instead of opaque "script should exist").
        JsonNode scripts = ngrinderClient.listScripts();
        // nGrinder script API response shape differs by version:
        // - sometimes it's a JSON array
        // - sometimes it's an object containing "value": []
        JsonNode scriptArr =
                (scripts != null && scripts.isArray())
                        ? scripts
                        : (scripts != null && scripts.has("value") ? scripts.get("value") : null);
        boolean hasAnyScript = scriptArr != null && scriptArr.isArray() && scriptArr.size() > 0;

        // Build a PerfTest body with minimal required fields.
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        String now = LocalDateTime.now().toString();
        body.put("status", "READY");
        body.put("agentCount", 1);
        body.put("processes", 1);
        body.put("samplingInterval", 1);
        body.put("duration", 30_000); // ms

        // script parameters (consumed by our groovy via -Dparam=...)
        // IMPORTANT: nGrinder passes "param" into JVM args as -Dparam=...
        // Newlines are brittle (can be split into separate JVM args and/or leak into URL strings).
        // Use ';' delimiter to keep it single-line. Our groovy scripts parse both ';' and newlines.
        StringBuilder param = new StringBuilder();
        param.append("baseUrl=").append(baseUrl).append(";");

        // Map scriptName to actual controller path to avoid "script should exist".
        // nGrinder expects "scriptName" to be the script path (e.g. "01_comprehensive.groovy/01_comprehensive.groovy").
        java.util.function.Function<String, String> scriptPath =
                (String fileName) -> {
                    if (scriptArr == null || !scriptArr.isArray()) return fileName;
                    for (JsonNode s : scriptArr) {
                        if (s != null && fileName.equals(s.path("fileName").asText(null))) {
                            String p = s.path("path").asText(null);
                            if (p != null && !p.isBlank()) {
                                p = p.trim();
                                // Some nGrinder versions return only "fileName" (no folder prefix),
                                // but the perftest API expects "fileName/fileName" for scriptName.
                                if (!p.contains("/") && p.endsWith(".groovy")) {
                                    return p + "/" + p;
                                }
                                return p;
                            }
                        }
                    }
                    return fileName.endsWith(".groovy") ? (fileName + "/" + fileName) : fileName;
                };

        switch (key) {
            case "all" -> {
                body.put("testName", "올인원(부하+예약/취소+동시성+정합성) " + now);
                body.put("scriptName", scriptPath.apply("05_all_in_one.groovy"));
                int vu = (vusers == null || vusers < 1) ? 50 : vusers;
                int th = (threads == null || threads < 1) ? vu : threads;
                int durSec = (testDurationSec == null || testDurationSec < 1) ? 30 : testDurationSec;
                int seats = (eventSeatCount == null || eventSeatCount < 1) ? Math.max(50, vu) : eventSeatCount;
                int pool = (seatPoolSize == null || seatPoolSize < 1) ? Math.min(10, seats) : seatPoolSize;

                body.put("threads", th);
                body.put("vuserPerAgent", vu);
                body.put("duration", (durSec * 1000) + 5_000);

                param.append("eventSeatCount=").append(seats).append(";");
                param.append("seatPoolSize=").append(pool).append(";");
                param.append("testDurationSec=").append(durSec).append(";");
                param.append("admissionMaxWaitSec=30;");
            }
            case "comp" -> {
                body.put("testName", "종합 시나리오(큐→입장→예약) " + now);
                body.put("scriptName", scriptPath.apply("01_comprehensive.groovy"));
                body.put("threads", 5);
                body.put("vuserPerAgent", 5);
                body.put("duration", 25_000);
                param.append("eventSeatCount=200;");
                param.append("admissionMaxWaitSec=30;");
                param.append("testDurationSec=20;");
            }
            case "conc" -> {
                body.put("testName", "동시성(동일 좌석 경쟁) " + now);
                body.put("scriptName", scriptPath.apply("02_concurrency.groovy"));
                body.put("threads", 30);
                body.put("vuserPerAgent", 30);
                body.put("duration", 25_000);
                param.append("eventSeatCount=1;");
                param.append("admissionMaxWaitSec=30;");
            }
            case "integrity" -> {
                body.put("testName", "데이터 정합성(예약/좌석 상태) " + now);
                body.put("scriptName", scriptPath.apply("04_data_integrity.groovy"));
                body.put("threads", 5);
                body.put("vuserPerAgent", 5);
                body.put("duration", 25_000);
                param.append("eventSeatCount=5;");
                param.append("admissionMaxWaitSec=30;");
            }
            case "load-lite" -> {
                body.put("testName", "부하(약) " + now);
                body.put("scriptName", scriptPath.apply("03_load.groovy"));
                body.put("threads", 10);
                body.put("vuserPerAgent", 10);
                body.put("duration", 25_000);
                param.append("eventSeatCount=200;");
                param.append("testDurationSec=20;");
            }
            case "load-heavy" -> {
                body.put("testName", "부하(강) " + now);
                body.put("scriptName", scriptPath.apply("03_load.groovy"));
                body.put("threads", 50);
                body.put("vuserPerAgent", 50);
                body.put("duration", 35_000);
                param.append("eventSeatCount=400;");
                param.append("testDurationSec=30;");
            }
            default -> {
                ObjectNode err = JsonNodeFactory.instance.objectNode();
                err.put("error", "unknown preset key: " + key);
                return ResponseEntity.badRequest().body(err);
            }
        }

        if (!hasAnyScript) {
            ObjectNode err = JsonNodeFactory.instance.objectNode();
            err.put("error", "nGrinder script repository is empty. Upload scripts first (prevents 'script should exist').");
            err.put("hint", "Run: .\\\\load-tests\\\\ngrinder\\\\upload-scripts.ps1 -ControllerBaseUrl http://localhost:19080 -Username admin -Password admin");
            return ResponseEntity.badRequest().body(err);
        }

        body.put("param", param.toString());
        JsonNode created = ngrinderClient.createTest(body);
        return ResponseEntity.ok(created);
    }

    /**
     * Start a single "payment-count" test:
     * - user only specifies how many payments to generate
     * - backend stops the test automatically when (payment success + failure) delta reaches target
     */
    @PostMapping("/payments/start")
    public ResponseEntity<JsonNode> startPaymentCountTest(
            @RequestParam int paymentCount,
            @RequestParam(defaultValue = "true") boolean resetPaymentQueue,
            @RequestParam(required = false) String baseUrl) {
        if (paymentCount < 1) {
            ObjectNode err = JsonNodeFactory.instance.objectNode();
            err.put("error", "paymentCount must be >= 1");
            return ResponseEntity.badRequest().body(err);
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = defaultTargetBaseUrl;
        }

        if (resetPaymentQueue) {
            paymentQueueMaintenanceService.purgePaymentQueueReadyMessages();
        }

        // Ensure script exists.
        JsonNode scripts = ngrinderClient.listScripts();
        JsonNode scriptArr =
                (scripts != null && scripts.isArray())
                        ? scripts
                        : (scripts != null && scripts.has("value") ? scripts.get("value") : null);
        boolean hasAnyScript = scriptArr != null && scriptArr.isArray() && scriptArr.size() > 0;
        if (!hasAnyScript) {
            ObjectNode err = JsonNodeFactory.instance.objectNode();
            err.put("error", "nGrinder script repository is empty. Upload scripts first (prevents 'script should exist').");
            err.put("hint", "Run: .\\\\load-tests\\\\ngrinder\\\\upload-scripts.ps1 -ControllerBaseUrl http://localhost:19080 -Username admin -Password admin");
            return ResponseEntity.badRequest().body(err);
        }

        java.util.function.Function<String, String> scriptPath =
                (String fileName) -> {
                    if (scriptArr == null || !scriptArr.isArray()) return fileName;
                    for (JsonNode s : scriptArr) {
                        if (s != null && fileName.equals(s.path("fileName").asText(null))) {
                            String p = s.path("path").asText(null);
                            if (p != null && !p.isBlank()) return p;
                        }
                    }
                    return fileName;
                };

        // Conservative defaults: keep pressure moderate, but allow reaching large counts.
        int vusers = 30;
        int threads = 30;
        int seats = Math.max(200, paymentCount * 2);
        int seatPool = Math.min(20, Math.max(1, seats));

        // Run long enough; script will finish early once paymentTarget is reached.
        long durationMs = Math.min(60 * 60 * 1000L, Math.max(5 * 60 * 1000L, (long) paymentCount * 1500L));
        int testDurationSec = (int) Math.min(3600, Math.max(120, (durationMs / 1000L) - 5));

        ObjectNode body = JsonNodeFactory.instance.objectNode();
        String now = LocalDateTime.now().toString();
        body.put("status", "READY");
        body.put("agentCount", 1);
        body.put("processes", 1);
        body.put("samplingInterval", 1);
        body.put("duration", durationMs);
        // Avoid controller-side auto stop (e.g., "Too low TPS") while we do deterministic request counting.
        body.put("ignoreTooManyError", true);
        // Use RUN-COUNT mode to avoid idle periods being treated as "Too low TPS".
        body.put("threshold", "R");
        body.put("runCount", 1);
        body.put("threads", threads);
        body.put("vuserPerAgent", vusers);
        body.put("testName", "결제 " + paymentCount + "건 완료까지 " + now);
        body.put("scriptName", scriptPath.apply("05_all_in_one.groovy"));

        StringBuilder param = new StringBuilder();
        param.append("baseUrl=").append(baseUrl).append(";");
        param.append("eventSeatCount=").append(seats).append(";");
        param.append("seatPoolSize=").append(seatPool).append(";");
        param.append("testDurationSec=").append(testDurationSec).append(";");
        param.append("admissionMaxWaitSec=30;");
        param.append("paymentTarget=").append(paymentCount).append(";");
        body.put("param", param.toString());

        JsonNode created = ngrinderClient.createTest(body);
        long id = created == null ? -1 : created.path("id").asLong(-1);
        if (id > 0) {
            // Baseline for delta-based stop: if script doesn't terminate, stop by settled delta + inflight==0.
            long baselineSettled = paymentCountRunner.currentSettledTotalRounded();
            paymentCountRunner.stopWhenSettledReached(id, paymentCount, baselineSettled, durationMs + 60_000L);
            // extra safety stop (in case of polling failures)
            paymentCountRunner.stopOnTimeout(id, durationMs + 60_000L);
        }
        return ResponseEntity.ok(created);
    }

    /**
     * Start a deterministic "request-count" test:
     * - issues exactly requestCount reserve requests (best-effort exact, no overshoot by script slots)
     * - then the script finishes immediately (no need to wait full duration)
     */
    @PostMapping("/requests/start")
    public ResponseEntity<JsonNode> startRequestCountTest(
            @RequestParam int requestCount,
            @RequestParam(defaultValue = "true") boolean resetPaymentQueue,
            @RequestParam(required = false) String baseUrl) {
        if (requestCount < 1) {
            ObjectNode err = JsonNodeFactory.instance.objectNode();
            err.put("error", "requestCount must be >= 1");
            return ResponseEntity.badRequest().body(err);
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = defaultTargetBaseUrl;
        }

        if (resetPaymentQueue) {
            paymentQueueMaintenanceService.purgePaymentQueueReadyMessages();
        }

        JsonNode scripts = ngrinderClient.listScripts();
        JsonNode scriptArr =
                (scripts != null && scripts.isArray())
                        ? scripts
                        : (scripts != null && scripts.has("value") ? scripts.get("value") : null);
        boolean hasAnyScript = scriptArr != null && scriptArr.isArray() && scriptArr.size() > 0;
        if (!hasAnyScript) {
            ObjectNode err = JsonNodeFactory.instance.objectNode();
            err.put("error", "nGrinder script repository is empty. Upload scripts first (prevents 'script should exist').");
            err.put("hint", "Run: .\\\\load-tests\\\\ngrinder\\\\upload-scripts.ps1 -ControllerBaseUrl http://localhost:19080 -Username admin -Password admin");
            return ResponseEntity.badRequest().body(err);
        }

        java.util.function.Function<String, String> scriptPath =
                (String fileName) -> {
                    if (scriptArr == null || !scriptArr.isArray()) return fileName;
                    for (JsonNode s : scriptArr) {
                        if (s != null && fileName.equals(s.path("fileName").asText(null))) {
                            String p = s.path("path").asText(null);
                            if (p != null && !p.isBlank()) return p;
                        }
                    }
                    return fileName;
                };

        int vusers = 30;
        int threads = 30;
        int seats = Math.max(200, requestCount * 2);
        int seatPool = Math.min(20, Math.max(1, seats));

        // IMPORTANT:
        // Use RUN-COUNT mode (threshold=R) so the script is executed once per thread.
        // In duration mode (threshold=D), once requestTarget is reached the script becomes idle,
        // which can trigger controller-side "Too low TPS" auto-stop.
        long durationMs = 5 * 60 * 1000L; // safety net only
        int testDurationSec = 295; // safety net only

        ObjectNode body = JsonNodeFactory.instance.objectNode();
        String now = LocalDateTime.now().toString();
        body.put("status", "READY");
        body.put("agentCount", 1);
        body.put("processes", 1);
        body.put("samplingInterval", 1);
        body.put("duration", durationMs);
        body.put("ignoreTooManyError", true);
        body.put("threshold", "R");
        body.put("runCount", 1);
        body.put("threads", threads);
        body.put("vuserPerAgent", vusers);
        body.put("testName", "요청 " + requestCount + "건 (reserve) " + now);
        body.put("scriptName", scriptPath.apply("05_all_in_one.groovy"));

        StringBuilder param = new StringBuilder();
        param.append("baseUrl=").append(baseUrl).append(";");
        param.append("eventSeatCount=").append(seats).append(";");
        param.append("seatPoolSize=").append(seatPool).append(";");
        param.append("testDurationSec=").append(testDurationSec).append(";");
        param.append("admissionMaxWaitSec=30;");
        param.append("requestTarget=").append(requestCount).append(";");
        body.put("param", param.toString());

        JsonNode created = ngrinderClient.createTest(body);
        long id = created == null ? -1 : created.path("id").asLong(-1);
        if (id > 0) {
            paymentCountRunner.stopOnTimeout(id, durationMs + 60_000L);
        }
        return ResponseEntity.ok(created);
    }

    /**
     * Start a "payment-requested count" test:
     * - generate traffic (reserve flow) continuously
     * - stop the test when app metric ticketing.payment.requested.total delta reaches requestedCount
     * This makes the test stop around the desired "발행량(requested)" count.
     */
    @PostMapping("/payment-requests/start")
    public ResponseEntity<JsonNode> startPaymentRequestedCountTest(
            @RequestParam int requestedCount,
            @RequestParam(defaultValue = "true") boolean resetPaymentQueue,
            @RequestParam(required = false) String baseUrl) {
        if (requestedCount < 1) {
            ObjectNode err = JsonNodeFactory.instance.objectNode();
            err.put("error", "requestedCount must be >= 1");
            return ResponseEntity.badRequest().body(err);
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = defaultTargetBaseUrl;
        }

        if (resetPaymentQueue) {
            paymentQueueMaintenanceService.purgePaymentQueueReadyMessages();
        }

        JsonNode scripts = ngrinderClient.listScripts();
        JsonNode scriptArr =
                (scripts != null && scripts.isArray())
                        ? scripts
                        : (scripts != null && scripts.has("value") ? scripts.get("value") : null);
        boolean hasAnyScript = scriptArr != null && scriptArr.isArray() && scriptArr.size() > 0;
        if (!hasAnyScript) {
            ObjectNode err = JsonNodeFactory.instance.objectNode();
            err.put("error", "nGrinder script repository is empty. Upload scripts first (prevents 'script should exist').");
            err.put("hint", "Run: .\\\\load-tests\\\\ngrinder\\\\upload-scripts.ps1 -ControllerBaseUrl http://localhost:19080 -Username admin -Password admin");
            return ResponseEntity.badRequest().body(err);
        }

        java.util.function.Function<String, String> scriptPath =
                (String fileName) -> {
                    if (scriptArr == null || !scriptArr.isArray()) return fileName;
                    for (JsonNode s : scriptArr) {
                        if (s != null && fileName.equals(s.path("fileName").asText(null))) {
                            String p = s.path("path").asText(null);
                            if (p != null && !p.isBlank()) return p;
                        }
                    }
                    return fileName;
                };

        int vusers = 30;
        int threads = 30;
        int seats = Math.max(200, requestedCount * 2);
        // requested-count tests should be able to keep reserving without canceling:
        // use a large enough seat pool so we don't quickly exhaust a tiny subset of seats as SOLD.
        int seatPool = Math.min(seats, Math.max(50, requestedCount));

        // Keep the script running (duration mode) until backend stopWhenRequestedReached hits the target.
        // Per-request wall time must cover payment simulation (multi-second dwell) × queue depth, not just raw TPS.
        long maxWindowMs = 15 * 60 * 1000L;
        long durationMs =
                Math.min(maxWindowMs, Math.max(60 * 1000L, (long) requestedCount * 60_000L));
        long maxWindowSec = maxWindowMs / 1000L;
        int testDurationSec =
                (int) Math.min(maxWindowSec, Math.max(60L, (durationMs / 1000L) - 5L));

        ObjectNode body = JsonNodeFactory.instance.objectNode();
        String now = LocalDateTime.now().toString();
        body.put("status", "READY");
        body.put("agentCount", 1);
        body.put("processes", 1);
        body.put("samplingInterval", 1);
        body.put("duration", durationMs);
        body.put("ignoreTooManyError", true);
        body.put("threshold", "D");
        body.put("threads", threads);
        body.put("vuserPerAgent", vusers);
        body.put("testName", "발행(requested) " + requestedCount + "건까지 " + now);
        body.put("scriptName", scriptPath.apply("05_all_in_one.groovy"));

        StringBuilder param = new StringBuilder();
        param.append("baseUrl=").append(baseUrl).append(";");
        param.append("eventSeatCount=").append(seats).append(";");
        param.append("seatPoolSize=").append(seatPool).append(";");
        param.append("testDurationSec=").append(testDurationSec).append(";");
        param.append("admissionMaxWaitSec=30;");
        // Default script progress wait is 30s; under load, payment workers can queue longer than that.
        param.append("progressMaxWaitSec=120;");
        // For requested-count tests we MUST NOT cancel reservations, otherwise the payment pipeline is aborted
        // (by design) and (success+fail) stays near 0, breaking requested accounting.
        param.append("requestTarget=0;");
        // Any positive number switches the script to "wait for terminal outcome (no cancel)" mode.
        // Use a very large target so it never stops naturally; backend stopWhenRequestedReached will stop it.
        param.append("paymentTarget=1000000000;");
        body.put("param", param.toString());

        long baselineRequested = paymentCountRunner.currentRequestedTotalRounded();
        JsonNode created = ngrinderClient.createTest(body);
        long id = created == null ? -1 : created.path("id").asLong(-1);
        if (id > 0) {
            paymentCountRunner.stopWhenRequestedReached(id, requestedCount, baselineRequested, durationMs + 60_000L);
            paymentCountRunner.stopOnTimeout(id, durationMs + 60_000L);
        }
        if (created != null && created.isObject()) {
            ((ObjectNode) created).put("baselinePaymentRequestedTotal", baselineRequested);
            ((ObjectNode) created).put("requestedCountTarget", requestedCount);
        }
        return ResponseEntity.ok(created);
    }

    /**
     * Start one of the scenario scripts (A~F).
     * <p>
     * Scenarios are implemented as groovy scripts in nGrinder controller's script repository:
     * - A: 10_scenario_a_thundering_herd.groovy
     * - B: 11_scenario_b_hot_key_lock.groovy
     * - C: 12_scenario_c_retry_storm.groovy
     * - D: 13_scenario_d_zombie_ttl.groovy
     * - E: 14_scenario_e_baseline_ticketing.groovy
     * - F: 15_scenario_f_integrated_random_mixed.groovy
     */
    @PostMapping("/scenarios/start")
    public ResponseEntity<JsonNode> startScenario(
            @RequestParam String scenario,
            @RequestParam(required = false) String baseUrl,
            @RequestParam(required = false) Integer vusers,
            @RequestParam(required = false) Integer threads,
            @RequestParam(required = false) Integer eventSeatCount,
            @RequestParam(required = false) Integer testDurationSec,
            @RequestParam(required = false) Integer sleepMs,
            @RequestParam(required = false) Integer holdTtlSeconds,
            @RequestParam(required = false) Integer crowdMultiplier) {
        if (scenario == null || scenario.isBlank()) {
            ObjectNode err = JsonNodeFactory.instance.objectNode();
            err.put("error", "scenario is required (A|B|C|D|E|F)");
            return ResponseEntity.badRequest().body(err);
        }
        String sc = scenario.trim().toUpperCase();
        if (!List.of("A", "B", "C", "D", "E", "F").contains(sc)) {
            ObjectNode err = JsonNodeFactory.instance.objectNode();
            err.put("error", "unknown scenario: " + scenario);
            err.put("hint", "use scenario=A|B|C|D|E|F");
            return ResponseEntity.badRequest().body(err);
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = defaultTargetBaseUrl;
        }

        JsonNode scripts = ngrinderClient.listScripts();
        JsonNode scriptArr =
                (scripts != null && scripts.isArray())
                        ? scripts
                        : (scripts != null && scripts.has("value") ? scripts.get("value") : null);
        boolean hasAnyScript = scriptArr != null && scriptArr.isArray() && scriptArr.size() > 0;
        if (!hasAnyScript) {
            ObjectNode err = JsonNodeFactory.instance.objectNode();
            err.put("error", "nGrinder script repository is empty. Upload scripts first (prevents 'script should exist').");
            err.put("hint", "Run: .\\\\load-tests\\\\ngrinder\\\\upload-scripts.ps1 -ControllerBaseUrl http://localhost:19080 -Username admin -Password admin");
            return ResponseEntity.badRequest().body(err);
        }

        java.util.function.Function<String, String> scriptPath =
                (String fileName) -> {
                    if (scriptArr == null || !scriptArr.isArray()) return fileName;
                    for (JsonNode s : scriptArr) {
                        if (s != null && fileName.equals(s.path("fileName").asText(null))) {
                            String p = s.path("path").asText(null);
                            if (p != null && !p.isBlank()) return p;
                        }
                    }
                    return fileName;
                };

        int multiplier = (crowdMultiplier == null || crowdMultiplier < 1) ? 10 : crowdMultiplier;
        int vu = (vusers == null || vusers < 1) ? 50 : vusers;
        int th = (threads == null || threads < 1) ? vu : threads;
        // nGrinder runs at most `threads` concurrent workers; if threads < vusers, only a subset of
        // VUsers ever execute (looks like "3 runs then done"). Keep threads aligned with vusers.
        th = Math.max(th, vu);

        // Create a dedicated LOAD_TEST event so:
        // - test traffic doesn't mix with real/public events
        // - ops heatmap can immediately render the event seats
        int seatCountForEvent = (eventSeatCount == null || eventSeatCount < 1) ? 400 : eventSeatCount;
        if ("B".equals(sc)) seatCountForEvent = 1;
        if ("D".equals(sc) && seatCountForEvent < vu) {
            // Scenario D needs enough seats to avoid hot-key collision dominating TTL behavior.
            seatCountForEvent = vu;
        }
        if ("F".equals(sc)) {
            int wantVu = (vusers == null || vusers < 1) ? 2000 : Math.min(5000, Math.max(1, vusers));
            if (eventSeatCount == null || eventSeatCount < 1) {
                seatCountForEvent = Math.min(5000, Math.max(800, wantVu));
            } else {
                seatCountForEvent = Math.min(5000, eventSeatCount);
            }
            if (seatCountForEvent < wantVu) {
                seatCountForEvent = Math.min(5000, wantVu);
            }
            vu = wantVu;
            th = vu;
        }
        // A: script reserves at most one seat per vuser; a modest pool keeps the ops heatmap readable.
        if ("A".equals(sc) && (eventSeatCount == null || eventSeatCount < 1)) {
            seatCountForEvent = Math.max(32, Math.min(200, vu + 24));
        }
        // C: GET /seats polling only; keep seat list small so JSON stays light (rate-limit story, not inventory size).
        if ("C".equals(sc) && (eventSeatCount == null || eventSeatCount < 1)) {
            seatCountForEvent = Math.max(24, Math.min(128, vu * 4));
        }
        // D: one seat per thread index modulo seat count; default pool modest vs blind 400, still >= vu enforced above.
        if ("D".equals(sc) && (eventSeatCount == null || eventSeatCount < 1)) {
            seatCountForEvent = Math.max(vu, Math.min(300, vu * 2));
        }
        // E: baseline crowd = seats * multiplier; keep default inventory moderate when callers omit seat count.
        if ("E".equals(sc) && (eventSeatCount == null || eventSeatCount < 1)) {
            seatCountForEvent = 48;
        }
        String now = LocalDateTime.now().toString();
        CreateEventRequest createEvent =
                new CreateEventRequest(
                        "LOAD_TEST " + sc + " " + now,
                        "load-test",
                        LocalDateTime.now().plusMinutes(5).toString(),
                        seatCountForEvent,
                        new BigDecimal("100.00"),
                        "R",
                        "LOAD_TEST");
        Event ev = eventService.create(createEvent);

        // Scenario E defaults: crowd ~= seats * multiplier (10x by default)
        if ("E".equals(sc) && (vusers == null || vusers < 1)) {
            vu = Math.max(1, Math.min(5000, seatCountForEvent * multiplier));
            th = vu;
        }

        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("status", "READY");
        body.put("agentCount", 1);
        body.put("processes", 1);
        body.put("samplingInterval", 1);
        body.put("ignoreTooManyError", true);
        body.put("threads", th);
        body.put("vuserPerAgent", vu);

        StringBuilder param = new StringBuilder();
        param.append("baseUrl=").append(baseUrl).append(";");
        param.append("eventId=").append(ev.getId()).append(";");
        // Used for log drill-down (Loki/console search). Kept out of Prometheus labels.
        String runId = java.util.UUID.randomUUID().toString();
        param.append("runId=").append(runId).append(";");
        param.append("admissionMaxWaitSec=30;");
        param.append("admissionPollIntervalMs=200;");

        long durationMs = 60_000L;

        switch (sc) {
            case "A" -> {
                body.put("testName", "Scenario A (Open Run Spike) " + now);
                body.put("scriptName", "10_scenario_a_thundering_herd.groovy/10_scenario_a_thundering_herd.groovy");
                int durSec = (testDurationSec == null || testDurationSec < 1) ? 20 : testDurationSec;
                param.append("testDurationSec=").append(durSec).append(";");
                // Duration mode: we want sustained admission polling pressure after the initial join spike.
                body.put("threshold", "D");
                durationMs = (durSec * 1000L) + 15_000L;
            }
            case "B" -> {
                body.put("testName", "Scenario B (Hot Key / Lock) " + now);
                body.put("scriptName", "11_scenario_b_hot_key_lock.groovy/11_scenario_b_hot_key_lock.groovy");
                // B is intentionally hot-key: single seat.
                body.put("threshold", "R");
                body.put("runCount", 1);
                durationMs = 60_000L;
            }
            case "C" -> {
                body.put("testName", "Scenario C (Retry Storm) " + now);
                body.put("scriptName", "12_scenario_c_retry_storm.groovy/12_scenario_c_retry_storm.groovy");
                int durSec = (testDurationSec == null || testDurationSec < 1) ? 20 : testDurationSec;
                param.append("testDurationSec=").append(durSec).append(";");
                body.put("threshold", "R");
                body.put("runCount", 1);
                durationMs = (durSec * 1000L) + 15_000L;
            }
            case "D" -> {
                body.put("testName", "Scenario D (Zombie TTL) " + now);
                body.put("scriptName", "13_scenario_d_zombie_ttl.groovy/13_scenario_d_zombie_ttl.groovy");
                int holdSec = (holdTtlSeconds == null || holdTtlSeconds < 1) ? 60 : Math.min(600, holdTtlSeconds);
                int sm =
                        (sleepMs == null || sleepMs < 0)
                                ? (holdSec * 1000 + 15_000)
                                : sleepMs;
                param.append("holdTtlSeconds=").append(holdSec).append(";");
                param.append("sleepMs=").append(sm).append(";");
                body.put("threshold", "R");
                body.put("runCount", 1);
                durationMs = sm + 45_000L;
            }
            case "E" -> {
                body.put("testName", "Scenario E (Baseline Ticketing) " + now);
                body.put("scriptName", "14_scenario_e_baseline_ticketing.groovy/14_scenario_e_baseline_ticketing.groovy");
                body.put("threshold", "R");
                body.put("runCount", 1);
                // wait for payment simulation to settle (default 120s in script)
                param.append("progressMaxWaitSec=120;");
                durationMs = 3 * 60_000L;
            }
            case "F" -> {
                body.put("testName", "Scenario F (Integrated Random Mixed) " + now);
                body.put("scriptName", "15_scenario_f_integrated_random_mixed.groovy/15_scenario_f_integrated_random_mixed.groovy");
                int durSec = (testDurationSec == null || testDurationSec < 30) ? 180 : Math.min(3600, testDurationSec);
                param.append("testDurationSec=").append(durSec).append(";");
                param.append("ephemeralUserProbability=0.12;");
                param.append("progressShortMaxAttempts=40;");
                param.append("progressPollIntervalMs=200;");
                body.put("threshold", "D");
                durationMs = (durSec * 1000L) + 45_000L;
            }
            default -> {
                ObjectNode err = JsonNodeFactory.instance.objectNode();
                err.put("error", "unknown scenario: " + scenario);
                return ResponseEntity.badRequest().body(err);
            }
        }

        body.put("duration", durationMs);
        body.put("param", param.toString());
        try {
            JsonNode created = ngrinderClient.createTest(body);
            if (created != null && created.isObject()) {
                ((ObjectNode) created).put("loadTestEventId", ev.getId());
                ((ObjectNode) created).put("loadTestEventSeatCount", seatCountForEvent);
                ((ObjectNode) created).put("loadTestScenario", sc);
                ((ObjectNode) created).put("ngrinderVusers", vu);
                ((ObjectNode) created).put("ngrinderThreads", th);
                ((ObjectNode) created).put("loadTestRunId", runId);
                if ("C".equals(sc)) {
                    loadTestRunAttribution.rememberProfile(runId, LoadTestRunProfile.retryStormRateLimit());
                }
                if ("D".equals(sc)) {
                    int holdSec = (holdTtlSeconds == null || holdTtlSeconds < 1) ? 60 : Math.min(600, holdTtlSeconds);
                    loadTestRunAttribution.rememberProfile(runId, LoadTestRunProfile.zombieTtl(holdSec));
                    long testId = created.path("id").asLong(0L);
                    if (testId > 0L) {
                        paymentCountRunner.stopWhenRunReservationExpiredReached(
                                testId, runId, vu, durationMs + 90_000L);
                        paymentCountRunner.stopOnTimeout(testId, durationMs + 120_000L);
                    }
                }
            }
            return ResponseEntity.ok(created);
        } catch (RestClientResponseException ex) {
            ObjectNode err = JsonNodeFactory.instance.objectNode();
            err.put("error", "nGrinder API call failed: " + ex.getStatusCode().value() + " " + ex.getStatusText());
            String respBody = ex.getResponseBodyAsString();
            if (respBody != null && !respBody.isBlank()) {
                err.put("ngrinderBody", respBody.length() > 2000 ? respBody.substring(0, 2000) : respBody);
            }
            err.put("hint", "If running backend in Docker, set NGRINDER_BASE_URL to the controller service URL (not localhost). If auth fails, set NGRINDER_USERNAME/NGRINDER_PASSWORD.");
            return ResponseEntity.status(ex.getStatusCode()).body(err);
        }
    }

    /**
     * Create & start all presets at once (flow verification + concurrency + integrity + two load levels).
     * Returns a map of preset key -> created test entity (best-effort).
     */
    @PostMapping("/presets/run-all")
    public ResponseEntity<JsonNode> runAllPresets(
            @RequestParam(required = false) String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = defaultTargetBaseUrl;
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        // Order matters: create verification checks first so failures are obvious early.
        List<String> keys = List.of("comp", "conc", "integrity", "load-lite", "load-heavy");
        for (String key : keys) {
            try {
                JsonNode created = startPreset(key, baseUrl, null, null, null, null, null).getBody();
                out.set(key, created);
            } catch (Exception e) {
                ObjectNode err = JsonNodeFactory.instance.objectNode();
                err.put("error", e.getMessage() == null ? "failed" : e.getMessage());
                out.set(key, err);
            }
        }
        return ResponseEntity.ok(out);
    }
}

