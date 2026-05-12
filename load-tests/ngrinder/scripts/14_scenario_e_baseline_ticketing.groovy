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

import java.util.concurrent.atomic.AtomicInteger

import static net.grinder.script.Grinder.grinder
import static org.hamcrest.MatcherAssert.assertThat
import static org.hamcrest.Matchers.greaterThan

/**
 * Scenario E: Baseline ticketing
 * - crowd ~ seats * multiplier (driven by backend choosing vusers)
 * - each VUser: joinQueue -> wait admission -> reserve a distributed seat -> poll progress until terminal
 */
@RunWith(GrinderRunner)
class ScenarioEBaselineTicketing {
    private static Map<String, String> scriptParams = parseScriptParams()

    private static Map<String, String> parseScriptParams() {
        String raw = System.getProperty("param", "")
        if (raw == null || raw.isBlank()) {
            raw = grinder.properties.getProperty("param", "")
        }
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

    private static long paramLong(String key, String defaultValue) {
        return Long.parseLong(param(key, defaultValue).trim())
    }

    static GTest testJoin
    static GTest testAdmission
    static GTest testSeats
    static GTest testReserve
    static GTest testProgress

    static HTTPRequest requestJoin
    static HTTPRequest requestAdmission
    static HTTPRequest requestSeats
    static HTTPRequest requestReserve
    static HTTPRequest requestProgress
    static HTTPRequest setupRequest

    static long runId
    static String loadTestRunId
    static String baseUrl
    static long eventId

    static AtomicInteger joinOk = new AtomicInteger(0)
    static AtomicInteger admissionOk = new AtomicInteger(0)
    static AtomicInteger reserveOk = new AtomicInteger(0)
    static AtomicInteger progressConfirmed = new AtomicInteger(0)
    static AtomicInteger progressCanceled = new AtomicInteger(0)

    String bearer
    Map<String, String> headersAuthJson

    @BeforeProcess
    static void beforeProcess() {
        runId = System.currentTimeMillis()
        baseUrl = param("baseUrl", "http://localhost:8080")
        loadTestRunId = param("runId", "")
        eventId = paramLong("eventId", "0")
        if (eventId <= 0) throw new IllegalStateException("eventId is required for scenario E")

        testJoin = new GTest(1, "E baseline - joinQueue")
        testAdmission = new GTest(2, "E baseline - admissionPoll")
        testSeats = new GTest(3, "E baseline - listSeats")
        testReserve = new GTest(4, "E baseline - reserve")
        testProgress = new GTest(5, "E baseline - progressPoll")

        requestJoin = new HTTPRequest()
        requestAdmission = new HTTPRequest()
        requestSeats = new HTTPRequest()
        requestReserve = new HTTPRequest()
        requestProgress = new HTTPRequest()
        setupRequest = new HTTPRequest()

        testJoin.record(requestJoin)
        testAdmission.record(requestAdmission)
        testSeats.record(requestSeats)
        testReserve.record(requestReserve)
        testProgress.record(requestProgress)
    }

    @BeforeThread
    void beforeThread() {
        testJoin.record(this, "E baseline - joinQueue")
        testAdmission.record(this, "E baseline - admissionPoll")
        testSeats.record(this, "E baseline - listSeats")
        testReserve.record(this, "E baseline - reserve")
        testProgress.record(this, "E baseline - progressPoll")
        grinder.statistics.delayReports = true

        int threadIdx = (grinder.threadNumber as int)
        int procNum = (grinder.getProcessNumber() as int)

        String userPassword = param("userPassword", "password123456")
        String userEmail = "vuser_${runId}_${procNum}_${threadIdx}@example.com"
        bearer = registerOrLogin(userEmail, userPassword)
        if (bearer == null || bearer.isBlank()) {
            grinder.logger.warn("Scenario E: auth failed for {}", userEmail)
            return
        }
        headersAuthJson = [
                "Authorization": "Bearer " + bearer,
                "X-LoadTest-RunId": loadTestRunId,
                "Content-Type" : "application/json"
        ]
    }

    @Test
    void test() {
        if (bearer == null || bearer.isBlank()) return
        joinQueue()
        String admissionToken = pollAdmissionToken()
        if (admissionToken == null) return
        long seatId = chooseSeatId()
        if (seatId <= 0) return
        Long reservationId = reserve(seatId, admissionToken)
        if (reservationId == null) return
        pollProgressToTerminal(reservationId)
    }

    @AfterProcess
    static void afterProcess() {
        grinder.logger.info(
                "Scenario E summary: joinOk={} admissionOk={} reserveOk={} confirmed={} canceled={}",
                joinOk.get(), admissionOk.get(), reserveOk.get(), progressConfirmed.get(), progressCanceled.get())
        assertThat(joinOk.get(), greaterThan(0))
    }

    private static String registerOrLogin(String email, String password) {
        // Be resilient: under load, register/login can briefly fail. Do not throw (would STOP_BY_ERROR the whole test).
        String token = login(email, password)
        if (token != null) return token
        try {
            Map<String, String> jsonHeaders = ["Content-Type": "application/json"]
            Map<String, Object> registerBody = ["email": email, "password": password]
            setupRequest.POST(baseUrl + "/api/auth/register", registerBody, jsonHeaders)
        } catch (Exception ignored) {
        }
        // retry login a few times
        for (int i = 0; i < 3; i++) {
            token = login(email, password)
            if (token != null) return token
            try { Thread.sleep(50) } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break }
        }
        return null
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
        if (resp.getStatusCode() == 200) joinOk.incrementAndGet()
    }

    private String pollAdmissionToken() {
        String url = baseUrl + "/api/events/" + eventId + "/admission"
        int pollIntervalMs = paramInt("admissionPollIntervalMs", "200")
        int maxWaitSec = paramInt("admissionMaxWaitSec", "30")
        int maxAttempts = pollIntervalMs <= 0 ? 1 : (maxWaitSec * 1000) / pollIntervalMs
        def json = new JsonSlurper()
        for (int i = 0; i < maxAttempts; i++) {
            HTTPResponse resp =
                    requestAdmission.GET(
                            url, [:], ["Authorization": "Bearer " + bearer, "X-LoadTest-RunId": loadTestRunId])
            if (resp.getStatusCode() == 200) {
                def body = json.parseText(resp.getBodyText())
                String token = body.token as String
                if (token != null && !token.isBlank()) {
                    admissionOk.incrementAndGet()
                    return token
                }
            }
            if (pollIntervalMs > 0) {
                try { Thread.sleep(pollIntervalMs) } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return null }
            }
        }
        return null
    }

    private long chooseSeatId() {
        // distribute seats by thread index to reduce collisions
        String url = baseUrl + "/api/events/" + eventId + "/seats"
        HTTPResponse resp = requestSeats.GET(url, [:], headersAuthJson)
        if (resp.getStatusCode() != 200) return -1L
        def seatList = new JsonSlurper().parseText(resp.getBodyText()) as List
        if (seatList.isEmpty()) return -1L

        int idx = (grinder.threadNumber as int) % seatList.size()
        def seat = seatList.get(idx)
        return seat.id as long
    }

    private Long reserve(long seatId, String admissionToken) {
        String url = baseUrl + "/api/events/" + eventId + "/reservations"
        Map<String, Object> body = ["seatId": seatId, "admissionToken": admissionToken]
        HTTPResponse resp = requestReserve.POST(url, body, headersAuthJson)
        if (resp.getStatusCode() != 200) return null
        reserveOk.incrementAndGet()
        def reservation = new JsonSlurper().parseText(resp.getBodyText())
        return reservation.id as Long
    }

    private void pollProgressToTerminal(long reservationId) {
        String url = baseUrl + "/api/events/" + eventId + "/reservations/" + reservationId + "/progress"
        int pollIntervalMs = paramInt("progressPollIntervalMs", "200")
        int maxWaitSec = paramInt("progressMaxWaitSec", "120")
        int maxAttempts = (maxWaitSec * 1000) / Math.max(50, pollIntervalMs)
        def json = new JsonSlurper()
        for (int i = 0; i < maxAttempts; i++) {
            HTTPResponse resp =
                    requestProgress.GET(
                            url, [:], ["Authorization": "Bearer " + bearer, "X-LoadTest-RunId": loadTestRunId])
            if (resp.getStatusCode() != 200) return
            def obj = json.parseText(resp.getBodyText())
            String st = obj.reservationStatus as String
            if ("CONFIRMED".equalsIgnoreCase(st)) {
                progressConfirmed.incrementAndGet()
                return
            }
            if ("CANCELED".equalsIgnoreCase(st)) {
                progressCanceled.incrementAndGet()
                return
            }
            if (pollIntervalMs > 0) {
                try { Thread.sleep(pollIntervalMs) } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return }
            }
        }
    }
}

