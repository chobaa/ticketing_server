// Scenario B is equivalent to the existing concurrency test:
// many users try to reserve the same VIP seat; exactly 1 success expected.
//
// This file is intentionally a thin copy to keep scenario naming consistent in the controller UI.

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import static net.grinder.script.Grinder.grinder
import static org.hamcrest.MatcherAssert.assertThat
import static org.hamcrest.Matchers.is
import static org.hamcrest.Matchers.greaterThan

@RunWith(GrinderRunner)
class ScenarioBHotKeyLock {
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
    static GTest testAdmission
    static GTest testReserve

    static HTTPRequest requestJoin
    static HTTPRequest requestAdmission
    static HTTPRequest requestReserve
    static HTTPRequest setupRequest

    static long runId
    static String baseUrl
    static String adminEmail
    static String adminPassword
    static String adminBearer
    static Long eventId
    static Long targetSeatId
    static String loadTestRunId

    static AtomicInteger attempted = new AtomicInteger(0)
    static AtomicInteger success = new AtomicInteger(0)
    static ConcurrentHashMap<Long, Boolean> successSeatIds = new ConcurrentHashMap<>()

    boolean done = false
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

        testJoin = new GTest(1, "B hot-key - joinQueue")
        testAdmission = new GTest(2, "B hot-key - admissionPoll")
        testReserve = new GTest(3, "B hot-key - reserve")

        requestJoin = new HTTPRequest()
        requestAdmission = new HTTPRequest()
        requestReserve = new HTTPRequest()
        testJoin.record(requestJoin)
        testAdmission.record(requestAdmission)
        testReserve.record(requestReserve)

        setupRequest = new HTTPRequest()
        Long existingEventId = paramLong("eventId", "")
        if (existingEventId != null && existingEventId > 0) {
            eventId = existingEventId
            initSeatFromExistingEvent()
            grinder.logger.info("Scenario B init: using existing eventId={} targetSeatId={}", eventId, targetSeatId)
        } else {
            initEventAndSeat()
        }
    }

    @BeforeThread
    void beforeThread() {
        testJoin.record(this, "B hot-key - joinQueue")
        testAdmission.record(this, "B hot-key - admissionPoll")
        testReserve.record(this, "B hot-key - reserve")
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
        if (done) return
        try {
            joinQueue()
            String admissionToken = pollAdmissionToken()
            attempted.incrementAndGet()
            reserveOnce(admissionToken)
        } finally {
            done = true
        }
    }

    @AfterProcess
    static void afterProcess() {
        grinder.logger.info("Scenario B summary: attempted={} success={}", attempted.get(), success.get())
        assertThat(attempted.get(), is(greaterThan(0)))
        assertThat(success.get(), is(1))
        assertThat(successSeatIds.size(), is(1))
        assertThat(successSeatIds.containsKey(targetSeatId), is(true))
        if (paramLong("eventId", "") == null) {
            teardownEvent()
        }
    }

    private static void initSeatFromExistingEvent() {
        HTTPResponse seatsResp = setupRequest.GET(baseUrl + "/api/events/" + eventId + "/seats")
        if (seatsResp.getStatusCode() != 200) {
            throw new IllegalStateException("Seat fetch failed: status=" + seatsResp.getStatusCode() + " body=" + seatsResp.getBodyText())
        }
        def seatList = new JsonSlurper().parseText(seatsResp.getBodyText()) as List
        List<Long> seatIds = seatList.collect { (it.id as long) }
        if (seatIds.isEmpty()) throw new IllegalStateException("No seats found for eventId=" + eventId)
        targetSeatId = seatIds.get(0)
    }

    private static void initEventAndSeat() {
        String token = registerOrLogin(adminEmail, adminPassword)
        adminBearer = token
        Map<String, String> adminHeadersJson = [
                "Authorization": "Bearer " + token,
                "Content-Type" : "application/json"
        ]

        int seatCount = paramInt("eventSeatCount", "1")
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

        HTTPResponse seatsResp = setupRequest.GET(baseUrl + "/api/events/" + eventId + "/seats")
        if (seatsResp.getStatusCode() != 200) {
            throw new IllegalStateException("Seat fetch failed: status=" + seatsResp.getStatusCode() + " body=" + seatsResp.getBodyText())
        }
        def seatList = new JsonSlurper().parseText(seatsResp.getBodyText()) as List
        List<Long> seatIds = seatList.collect { (it.id as long) }
        targetSeatId = seatIds.get(0)
        grinder.logger.info("Scenario B init: eventId={} targetSeatId={}", eventId, targetSeatId)
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
        if (resp.getStatusCode() != 200) {
            throw new IllegalStateException("joinQueue failed: status=" + resp.getStatusCode() + " body=" + resp.getBodyText())
        }
    }

    private String pollAdmissionToken() {
        String url = baseUrl + "/api/events/" + eventId + "/admission"
        int pollIntervalMs = paramInt("admissionPollIntervalMs", "200")
        int maxWaitSec = paramInt("admissionMaxWaitSec", "20")
        int maxAttempts = (maxWaitSec * 1000) / pollIntervalMs
        def json = new JsonSlurper()
        for (int i = 0; i < maxAttempts; i++) {
            HTTPResponse resp =
                    requestAdmission.GET(
                            url, [:], ["Authorization": "Bearer " + bearer, "X-LoadTest-RunId": loadTestRunId])
            if (resp.getStatusCode() == 200) {
                def body = json.parseText(resp.getBodyText())
                String token = body.token as String
                if (token != null && !token.isBlank()) return token
            }
            try { Thread.sleep(pollIntervalMs) } catch (InterruptedException ignored) { Thread.currentThread().interrupt() }
        }
        throw new IllegalStateException("Timeout waiting admission token")
    }

    private void reserveOnce(String admissionToken) {
        String url = baseUrl + "/api/events/" + eventId + "/reservations"
        Map<String, Object> body = ["seatId": targetSeatId, "admissionToken": admissionToken]
        HTTPResponse resp = requestReserve.POST(url, body, headersAuthJson)
        if (resp.getStatusCode() == 200) {
            def reservation = new JsonSlurper().parseText(resp.getBodyText())
            long reservedSeatId = reservation.seatId as long
            successSeatIds.put(reservedSeatId, true)
            success.incrementAndGet()
        }
    }
}

