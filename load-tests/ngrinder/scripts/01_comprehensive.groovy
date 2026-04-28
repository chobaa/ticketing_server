import groovy.json.JsonSlurper
import net.grinder.script.GTest
import net.grinder.script.Grinder
import net.grinder.scriptengine.groovy.junit.GrinderRunner
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread
import net.grinder.scriptengine.groovy.junit.annotation.AfterProcess
import org.junit.Test
import org.junit.runner.RunWith
import org.ngrinder.http.HTTPRequest
import org.ngrinder.http.HTTPResponse
import java.time.LocalDateTime
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import static net.grinder.script.Grinder.grinder
import static org.hamcrest.MatcherAssert.assertThat
import static org.hamcrest.Matchers.is
import static org.hamcrest.Matchers.greaterThan

@RunWith(GrinderRunner)
class ComprehensiveScenario {
    private static Map<String, String> scriptParams = parseScriptParams()

    private static Map<String, String> parseScriptParams() {
        // REST API로 perftest.param을 넣으면 nGrinder가 "-Dparam=..."로 전달합니다.
        // UI의 "Script Parameters"처럼 개별 grinder.properties로 풀리지 않을 수 있어 여기서 직접 파싱합니다.
        String raw = System.getProperty("param", "")
        if (raw == null || raw.isBlank()) return [:]
        // Support both actual newlines and literal "\n"
        String normalized = raw.replace("\\\\n", "\n")
        Map<String, String> out = [:]
        // Allow ';' delimiter too (newlines in JVM args are brittle).
        normalized.split("[\\r\\n;]+")
                .collect { it?.trim() }
                .findAll { it }
                .each { line ->
                    int idx = line.indexOf('=')
                    if (idx > 0) {
                        out.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
                    }
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

    static GTest testJoin
    static GTest testAdmission
    static GTest testReserve

    static HTTPRequest requestJoin
    static HTTPRequest requestAdmission
    static HTTPRequest requestReserve

    // setup-only (do not record TPS)
    static HTTPRequest setupRequest

    static long runId
    static String baseUrl
    static String adminEmail
    static String adminPassword
    static String adminBearer

    static Long eventId
    static List<Long> seatIds = []

    static AtomicInteger successCount = new AtomicInteger(0)
    static ConcurrentHashMap<Long, Boolean> successSeatIds = new ConcurrentHashMap<>()

    // per thread
    String bearer
    Map<String, String> headersAuthJson
    long chosenSeatId
    String admissionTokenCached
    long endAtMs

    @BeforeProcess
    static void beforeProcess() {
        runId = System.currentTimeMillis()
        baseUrl = param("baseUrl", "http://localhost:8080")
        adminPassword = param("adminPassword", "password123456")
        String adminEmailProp = param("adminEmail", "")
        adminEmail = adminEmailProp?.trim() ? adminEmailProp.trim() : "admin_${runId}@example.com"

        testJoin = new GTest(1, "01 comprehensive - joinQueue")
        testAdmission = new GTest(2, "01 comprehensive - admissionPoll")
        testReserve = new GTest(3, "01 comprehensive - reserve")

        requestJoin = new HTTPRequest()
        requestAdmission = new HTTPRequest()
        requestReserve = new HTTPRequest()
        testJoin.record(requestJoin)
        testAdmission.record(requestAdmission)
        testReserve.record(requestReserve)

        setupRequest = new HTTPRequest()
        initEventAndSeats()
    }

    @BeforeThread
    void beforeThread() {
        testJoin.record(this, "01 comprehensive - joinQueue")
        testAdmission.record(this, "01 comprehensive - admissionPoll")
        testReserve.record(this, "01 comprehensive - reserve")
        grinder.statistics.delayReports = true

        int threadIdx = (grinder.threadNumber as int)
        int procNum = (grinder.getProcessNumber() as int)

        String userPassword = param("userPassword", "password123456")
        String userEmail = "vuser_${runId}_${procNum}_${threadIdx}@example.com"

        String token = registerOrLogin(userEmail, userPassword)
        bearer = token
        headersAuthJson = [
                "Authorization": "Bearer " + bearer,
                "Content-Type" : "application/json"
        ]

        if (!seatIds.isEmpty()) {
            chosenSeatId = seatIds.get(threadIdx % seatIds.size()) as long
        } else {
            chosenSeatId = 1L
        }

        int testDurationSec = paramInt("testDurationSec", "20")
        if (testDurationSec < 1) testDurationSec = 20
        endAtMs = System.currentTimeMillis() + (testDurationSec * 1000L)
    }

    @Test
    void test() {
        // Run a mixed workload loop during the configured duration.
        // Goal: keep TPS meaningful while still validating the end-to-end flow at least once per thread.
        while (System.currentTimeMillis() < endAtMs) {
            long stepStart = System.currentTimeMillis()
            try {
                if (admissionTokenCached == null) {
                    joinQueue()
                    admissionTokenCached = pollAdmissionToken()
                }
                reserveOnce(chosenSeatId, admissionTokenCached)
            } finally {
                long elapsed = System.currentTimeMillis() - stepStart
                // Light jitter to reduce lockstep behavior
                long sleep = Math.max(0L, 50L - Math.min(50L, elapsed))
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep + (grinder.threadNumber % 10))
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
    }

    @AfterProcess
    static void afterProcess() {
        // Sanity check: at least one reservation should succeed.
        // If this fails, the environment is likely misconfigured (queue/admission) or too overloaded.
        int ok = successCount.get()
        grinder.logger.info("ComprehensiveScenario summary: successCount={}, uniqueSeats={}", ok, successSeatIds.size())
        assertThat(ok, is(greaterThan(0)))

        teardownEvent()
    }

    private static void initEventAndSeats() {
        String token = registerOrLogin(adminEmail, adminPassword)
        adminBearer = token
        Map<String, String> adminHeadersJson = [
                "Authorization": "Bearer " + token,
                "Content-Type" : "application/json"
        ]

        int seatCount = paramInt("eventSeatCount", "20")
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
                grade     : grade
        ]

        String createUrl = baseUrl + "/api/events"
        HTTPResponse createResp = setupRequest.POST(createUrl, createBody, adminHeadersJson)
        if (createResp.getStatusCode() != 200) {
            throw new IllegalStateException("Event create failed: status=" + createResp.getStatusCode() + " body=" + createResp.getBodyText())
        }

        def eventObj = new JsonSlurper().parseText(createResp.getBodyText())
        eventId = (eventObj.id as long)

        String seatsUrl = baseUrl + "/api/events/" + eventId + "/seats"
        HTTPResponse seatsResp = setupRequest.GET(seatsUrl)
        if (seatsResp.getStatusCode() != 200) {
            throw new IllegalStateException("Seat fetch failed: status=" + seatsResp.getStatusCode() + " body=" + seatsResp.getBodyText())
        }
        def seatList = new JsonSlurper().parseText(seatsResp.getBodyText()) as List
        seatIds = seatList.collect { (it.id as long) }

        grinder.logger.info("ComprehensiveScenario init: eventId={} seatIds={}", eventId, seatIds.size())
    }

    private static void teardownEvent() {
        if (eventId == null) return
        if (adminBearer == null || adminBearer.isBlank()) return
        try {
            Map<String, String> headers = ["Authorization": "Bearer " + adminBearer]
            HTTPResponse resp = setupRequest.DELETE(baseUrl + "/api/events/" + eventId, headers)
            grinder.logger.info("teardownEvent: eventId={} status={}", eventId, resp == null ? -1 : resp.getStatusCode())
        } catch (Exception e) {
            grinder.logger.warn("teardownEvent failed: eventId={} err={}", eventId, e.getMessage())
        }
    }

    private static String registerOrLogin(String email, String password) {
        String token = login(email, password)
        if (token != null) return token

        // register -> login
        Map<String, String> jsonHeaders = ["Content-Type": "application/json"]
        Map<String, Object> registerBody = ["email": email, "password": password]
        HTTPResponse regResp = setupRequest.POST(baseUrl + "/api/auth/register", registerBody, jsonHeaders)
        // if already registered, register returns 400; proceed to login
        if (regResp.getStatusCode() != 200 && regResp.getStatusCode() != 400) {
            grinder.logger.warn("register unexpected status={} body={}", regResp.getStatusCode(), regResp.getBodyText())
        }
        token = login(email, password)
        if (token == null) {
            throw new IllegalStateException("login failed after register: email=" + email)
        }
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
        } catch (Exception e) {
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
        int maxWaitSec = paramInt("admissionMaxWaitSec", "30")
        int maxAttempts = (maxWaitSec * 1000) / pollIntervalMs

        def json = new JsonSlurper()
        for (int i = 0; i < maxAttempts; i++) {
            HTTPResponse resp = requestAdmission.GET(url, [:], ["Authorization": "Bearer " + bearer])
            if (resp.getStatusCode() == 200) {
                def body = json.parseText(resp.getBodyText())
                String token = body.token as String
                if (token != null && !token.isBlank()) return token
            } else if (resp.getStatusCode() == 404) {
                // not admitted yet
            } else {
                grinder.logger.warn("admission unexpected status={} body={}", resp.getStatusCode(), resp.getBodyText())
            }
            try {
                Thread.sleep(pollIntervalMs)
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt()
            }
        }
        throw new IllegalStateException("Timeout waiting admission token. seatId=" + chosenSeatId)
    }

    private void reserveOnce(long seatId, String admissionToken) {
        String url = baseUrl + "/api/events/" + eventId + "/reservations"
        Map<String, Object> body = ["seatId": seatId, "admissionToken": admissionToken]
        HTTPResponse resp = requestReserve.POST(url, body, headersAuthJson)
        if (resp.getStatusCode() != 200) {
            grinder.logger.warn("reserve failed status={} body={}", resp.getStatusCode(), resp.getBodyText())
            return
        }

        def reservation = new JsonSlurper().parseText(resp.getBodyText())
        long reservedSeatId = reservation.seatId as long
        String status = reservation.status as String
        if (status != null && status.toUpperCase() == "PENDING_PAYMENT") {
            successCount.incrementAndGet()
            successSeatIds.put(reservedSeatId, true)
        } else {
            grinder.logger.warn("reserve unexpected status={}, reservation={}", status, resp.getBodyText())
        }
    }
}

