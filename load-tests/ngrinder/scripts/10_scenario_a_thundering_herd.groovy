import groovy.json.JsonSlurper
import net.grinder.script.GTest
import net.grinder.scriptengine.groovy.junit.GrinderRunner
import net.grinder.scriptengine.groovy.junit.annotation.AfterProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread
import org.junit.Test
import org.junit.runner.RunWith
import org.ngrinder.http.HTTPRequest
import org.ngrinder.http.HTTPResponse

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

import static net.grinder.script.Grinder.grinder

@RunWith(GrinderRunner)
class ScenarioAThunderingHerd {
    private static Map<String, String> scriptParams = parseScriptParams()

    private static Map<String, String> parseScriptParams() {
        String raw = System.getProperty("param", "")
        if (raw == null || raw.isBlank()) return [:]
        String normalized = raw.replace("\\\\n", "\n")
        Map<String, String> out = [:]
        normalized.split("[\\r\\n;]+")
                .collect { it?.trim() }
                .findAll { it }
                .each { line ->
                    int idx = line.indexOf('=')
                    if (idx > 0) out.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
                }
        return out
    }

    private static String param(String key, String defaultValue) {
        String v = scriptParams.get(key)
        if (v != null && !v.isBlank()) return v
        return grinder.properties.getProperty(key, defaultValue)
    }

    private static int paramInt(String key, String defaultValue) {
        return (param(key, defaultValue) as int)
    }

    private static Long paramLong(String key, String defaultValue) {
        try {
            String v = param(key, defaultValue)
            if (v == null || v.isBlank()) return null
            return Long.parseLong(v.trim())
        } catch (Exception ignored) {
            return null
        }
    }

    static GTest testJoin
    static GTest testAdmissionOnce
    static GTest testReserve
    static GTest testSetup

    static HTTPRequest requestJoin
    static HTTPRequest requestAdmissionOnce
    static HTTPRequest requestReserve
    static HTTPRequest setupRequest

    static long runId
    static String loadTestRunId
    static String baseUrl
    static String adminEmail
    static String adminPassword
    static String adminBearer
    static Long eventId

    static AtomicInteger joinOk = new AtomicInteger(0)
    static AtomicInteger reservedOk = new AtomicInteger(0)

    String bearer
    Map<String, String> headersAuthJson

    @BeforeProcess
    static void beforeProcess() {
        runId = System.currentTimeMillis()
        baseUrl = param("baseUrl", "http://localhost:8080")
        loadTestRunId = param("runId", "")
        adminPassword = param("adminPassword", "password123456")
        String adminEmailProp = param("adminEmail", "")
        adminEmail = adminEmailProp?.trim() ? adminEmailProp.trim() : "admin_${runId}@example.com"

        testJoin = new GTest(1, "A thundering-herd - joinQueue")
        testAdmissionOnce = new GTest(2, "A thundering-herd - admissionOnce")
        testReserve = new GTest(3, "A thundering-herd - reserveOnce")
        testSetup = new GTest(0, "A thundering-herd - setup")

        requestJoin = new HTTPRequest()
        requestAdmissionOnce = new HTTPRequest()
        requestReserve = new HTTPRequest()
        testJoin.record(requestJoin)
        testAdmissionOnce.record(requestAdmissionOnce)
        testReserve.record(requestReserve)

        setupRequest = new HTTPRequest()
        Long existingEventId = paramLong("eventId", "")
        if (existingEventId != null && existingEventId > 0) {
            eventId = existingEventId
            grinder.logger.info("Scenario A init: using existing eventId={}", eventId)
        } else {
            initEvent()
        }
    }

    @BeforeThread
    void beforeThread() {
        testJoin.record(this, "A thundering-herd - joinQueue")
        testAdmissionOnce.record(this, "A thundering-herd - admissionOnce")
        testReserve.record(this, "A thundering-herd - reserveOnce")
        testSetup.record(setupRequest)
        grinder.statistics.delayReports = true

        int threadIdx = (grinder.threadNumber as int)
        int procNum = (grinder.getProcessNumber() as int)

        String userPassword = param("userPassword", "password123456")
        String userEmail = "vuser_${runId}_${procNum}_${threadIdx}@example.com"
        bearer = registerOrLogin(userEmail, userPassword)
        headersAuthJson = [
                "Authorization": "Bearer " + bearer,
                "X-LoadTest-RunId": loadTestRunId,
                "Content-Type" : "application/json"
        ]
    }

    @Test
    void test() {
        // No ramp-up at script level: all VUsers hit joinQueue ASAP after start.
        joinQueue()
        String token = waitAdmissionTokenBestEffort()
        if (token != null) {
            reserveOneBestEffort(token)
        }
        admissionLoopUntilEnd()
    }

    @AfterProcess
    static void afterProcess() {
        grinder.logger.info("Scenario A summary: joinOk={} reservedOk={}", joinOk.get(), reservedOk.get())
        if (paramLong("eventId", "") == null) {
            teardownEvent()
        }
    }

    private static void initEvent() {
        String token = registerOrLogin(adminEmail, adminPassword)
        adminBearer = token
        Map<String, String> adminHeadersJson = [
                "Authorization": "Bearer " + token,
                "Content-Type" : "application/json"
        ]

        int seatCount = paramInt("eventSeatCount", "96")
        if (seatCount < 1) seatCount = 1
        BigDecimal seatPrice = new BigDecimal(param("seatPrice", "100.00"))
        String grade = param("seatGrade", "R")
        String eventName = param("eventName", "event_${runId}")
        String venue = param("eventVenue", "seoul")
        String startDate = LocalDateTime.now().plusMinutes(5).toString()

        Map<String, Object> createBody = [
                name      : eventName,
                venue     : venue,
                startDate : startDate,
                seatCount : seatCount,
                seatPrice : seatPrice,
                grade     : grade,
                listingScope: 'LOAD_TEST'
        ]

        HTTPResponse createResp = setupRequest.POST(baseUrl + "/api/events", createBody, adminHeadersJson)
        if (createResp.getStatusCode() != 200) {
            throw new IllegalStateException("Event create failed: status=" + createResp.getStatusCode() + " body=" + createResp.getBodyText())
        }
        def eventObj = new JsonSlurper().parseText(createResp.getBodyText())
        eventId = (eventObj.id as long)
        grinder.logger.info("Scenario A init: eventId={}", eventId)
    }

    private static void teardownEvent() {
        if (eventId == null) return
        if (adminBearer == null || adminBearer.isBlank()) return
        try {
            Map<String, String> headers = ["Authorization": "Bearer " + adminBearer]
            HTTPResponse resp = setupRequest.DELETE(baseUrl + "/api/events/" + eventId, headers)
            grinder.logger.info("teardownEvent: eventId={} status={}", eventId, resp == null ? -1 : resp.getStatusCode())
        } catch (Exception ignored) {
        }
    }

    private static String registerOrLogin(String email, String password) {
        String token = login(email, password)
        if (token != null) return token
        Map<String, String> jsonHeaders = ["Content-Type": "application/json"]
        Map<String, Object> registerBody = ["email": email, "password": password]
        setupRequest.POST(baseUrl + "/api/auth/register", registerBody, jsonHeaders)
        token = login(email, password)
        if (token == null) throw new IllegalStateException("login failed after register: email=" + email)
        return token
    }

    private static String login(String email, String password) {
        try {
            Map<String, String> jsonHeaders = ["Content-Type": "application/json"]
            Map<String, Object> loginBody = ["email": email, "password": password]
            HTTPResponse loginResp = setupRequest.POST(baseUrl + "/api/auth/login", loginBody, jsonHeaders)
            if (loginResp.getStatusCode() != 200) return null
            def tokenObj = new JsonSlurper().parseText(loginResp.getBodyText())
            return tokenObj.accessToken as String
        } catch (Exception ignored) {
            return null
        }
    }

    private void joinQueue() {
        String url = baseUrl + "/api/events/" + eventId + "/queue"
        HTTPResponse resp = requestJoin.POST(url, [:], headersAuthJson)
        if (resp.getStatusCode() == 200) {
            joinOk.incrementAndGet()
        }
    }

    private void admissionOnce() {
        String url = baseUrl + "/api/events/" + eventId + "/admission"
        requestAdmissionOnce.GET(url, [:], ["Authorization": "Bearer " + bearer, "X-LoadTest-RunId": loadTestRunId])
    }

    private String waitAdmissionTokenBestEffort() {
        String url = baseUrl + "/api/events/" + eventId + "/admission"
        int pollIntervalMs = paramInt("admissionPollIntervalMs", "200")
        int maxWaitSec = paramInt("admissionMaxWaitSec", "30")
        int maxAttempts = pollIntervalMs <= 0 ? 1 : (maxWaitSec * 1000) / pollIntervalMs
        def json = new JsonSlurper()
        for (int i = 0; i < maxAttempts; i++) {
            HTTPResponse resp = requestAdmissionOnce.GET(url, [:], ["Authorization": "Bearer " + bearer, "X-LoadTest-RunId": loadTestRunId])
            if (resp.getStatusCode() == 200) {
                try {
                    def body = json.parseText(resp.getBodyText())
                    String token = body.token as String
                    if (token != null && !token.isBlank()) return token
                } catch (Exception ignored) {
                }
            }
            if (pollIntervalMs > 0) {
                try { Thread.sleep(pollIntervalMs) } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return null }
            }
        }
        return null
    }

    private void reserveOneBestEffort(String admissionToken) {
        // Seat occupancy is driven by reservations, not by queue/admission.
        // Pick an AVAILABLE seat and reserve it once per VUser.
        try {
            HTTPResponse seatsResp = setupRequest.GET(baseUrl + "/api/events/" + eventId + "/seats", [:], headersAuthJson)
            if (seatsResp.getStatusCode() != 200) return
            def seatList = new JsonSlurper().parseText(seatsResp.getBodyText()) as List
            def seat = seatList.find { (it.status as String)?.equalsIgnoreCase("AVAILABLE") }
            if (seat == null) return
            long seatId = seat.id as long
            String url = baseUrl + "/api/events/" + eventId + "/reservations"
            Map<String, Object> body = ["seatId": seatId, "admissionToken": admissionToken]
            HTTPResponse resp = requestReserve.POST(url, body, headersAuthJson)
            if (resp.getStatusCode() == 200) {
                reservedOk.incrementAndGet()
            }
        } catch (Exception ignored) {
        }
    }

    private void admissionLoopUntilEnd() {
        int durSec = paramInt("testDurationSec", "20")
        if (durSec < 1) durSec = 20
        long endAtMs = System.currentTimeMillis() + (durSec * 1000L)
        int pollIntervalMs = paramInt("admissionPollIntervalMs", "200")
        if (pollIntervalMs < 0) pollIntervalMs = 0

        String url = baseUrl + "/api/events/" + eventId + "/admission"
        Map<String, String> auth = ["Authorization": "Bearer " + bearer, "X-LoadTest-RunId": loadTestRunId]
        while (System.currentTimeMillis() < endAtMs) {
            requestAdmissionOnce.GET(url, [:], auth)
            if (pollIntervalMs > 0) {
                try {
                    Thread.sleep(pollIntervalMs)
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }
}

