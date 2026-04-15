package com.ticketing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ticketing.ngrinder.NgrinderClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/dashboard/ngrinder")
@RequiredArgsConstructor
public class NgrinderDashboardController {
    private final NgrinderClient ngrinderClient;

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
                            if (p != null && !p.isBlank()) return p;
                        }
                    }
                    return fileName;
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
            err.put("hint", "Run: .\\\\load-tests\\\\ngrinder\\\\upload-scripts.ps1 -ControllerBaseUrl http://localhost:9080 -Username admin -Password admin");
            return ResponseEntity.badRequest().body(err);
        }

        body.put("param", param.toString());
        JsonNode created = ngrinderClient.createTest(body);
        return ResponseEntity.ok(created);
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

