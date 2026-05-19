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
import static org.hamcrest.MatcherAssert.assertThat
import static org.hamcrest.Matchers.greaterThan
import static org.hamcrest.Matchers.is

@RunWith(GrinderRunner)
class ScenarioDZombieTtl {
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
    static GTest testSeatCheck
    static GTest testSetup

    static HTTPRequest requestJoin
    static HTTPRequest requestAdmission
    static HTTPRequest requestReserve
    static HTTPRequest requestSeatCheck
    static HTTPRequest setupRequest

    static long runId
    static String baseUrl
    static String adminEmail
    static String adminPassword
    static String adminBearer
    static Long eventId
    static List<Long> seatIds = []
    static String loadTestRunId

    static AtomicInteger reservedOk = new AtomicInteger(0)
    static AtomicInteger releasedOk = new AtomicInteger(0)

    String bearer
    Map<String, String> headersAuthJson
    String admissionToken
    long chosenSeatId
    boolean done = false

    @BeforeProcess
    static void beforeProcess() {
        runId = System.currentTimeMillis()
        baseUrl = param("baseUrl", "http://localhost:8080")
        loadTestRunId = param("runId", "")
        adminPassword = param("adminPassword", "password123456")
        String adminEmailProp = param("adminEmail", "")
        adminEmail = adminEmailProp?.trim() ? adminEmailProp.trim() : "admin_${runId}@example.com"

        testJoin = new GTest(1, "D zombie-ttl - joinQueue")
        testAdmission = new GTest(2, "D zombie-ttl - admissionPoll")
        testReserve = new GTest(3, "D zombie-ttl - reserve")
        testSeatCheck = new GTest(4, "D zombie-ttl - seatCheckAfterTTL")
        testSetup = new GTest(0, "D zombie-ttl - setup")

        requestJoin = new HTTPRequest()
        requestAdmission = new HTTPRequest()
        requestReserve = new HTTPRequest()
        requestSeatCheck = new HTTPRequest()
        testJoin.record(requestJoin)
        testAdmission.record(requestAdmission)
        testReserve.record(requestReserve)
        testSeatCheck.record(requestSeatCheck)

        setupRequest = new HTTPRequest()
        Long existingEventId = paramLong("eventId", "")
        if (existingEventId != null && existingEventId > 0) {
            eventId = existingEventId
            initSeatsFromExistingEvent()
            grinder.logger.info("Scenario D init: using existing eventId={} seats={}", eventId, seatIds.size())
        } else {
            initEventAndSeats()
        }
    }

    @BeforeThread
    void beforeThread() {
        testJoin.record(this, "D zombie-ttl - joinQueue")
        testAdmission.record(this, "D zombie-ttl - admissionPoll")
        testReserve.record(this, "D zombie-ttl - reserve")
        testSeatCheck.record(this, "D zombie-ttl - seatCheckAfterTTL")
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
        if (!seatIds.isEmpty()) {
            chosenSeatId = seatIds.get(threadIdx % seatIds.size()) as long
        } else {
            chosenSeatId = 1L
        }
    }

    @Test
    void test() {
        if (done) return
        done = true

        joinQueue()
        admissionToken = pollAdmissionToken()
        boolean ok = reserveOnce(chosenSeatId, admissionToken)
        if (!ok) {
            return
        }

        int holdTtlSec = paramInt("holdTtlSeconds", "60")
        if (holdTtlSec < 1) holdTtlSec = 60
        int defaultSleepMs = holdTtlSec * 1000 + 15_000
        int sleepMs = paramInt("sleepMs", String.valueOf(defaultSleepMs))
        if (sleepMs < 0) sleepMs = 0
        waitForSeatReleasedAfterTtl(chosenSeatId, holdTtlSec, sleepMs)

        // After TTL expiry, seat should be AVAILABLE again (requires reservation expiry scheduler enabled).
        verifySeatAvailable(chosenSeatId)
    }

    @AfterProcess
    static void afterProcess() {
        grinder.logger.info("Scenario D summary: reservedOk={} releasedOk={}", reservedOk.get(), releasedOk.get())
        assertThat(reservedOk.get(), is(greaterThan(0)))
        if (paramLong("eventId", "") == null) {
            teardownEvent()
        }
    }

    private static void initSeatsFromExistingEvent() {
        HTTPResponse seatsResp = setupRequest.GET(baseUrl + "/api/events/" + eventId + "/seats")
        if (seatsResp.getStatusCode() != 200) {
            throw new IllegalStateException("Seat fetch failed: status=" + seatsResp.getStatusCode() + " body=" + seatsResp.getBodyText())
        }
        def seatList = new JsonSlurper().parseText(seatsResp.getBodyText()) as List
        seatIds = seatList.collect { (it.id as long) }
    }

    private static void initEventAndSeats() {
        String token = registerOrLogin(adminEmail, adminPassword)
        adminBearer = token
        Map<String, String> adminHeadersJson = [
                "Authorization": "Bearer " + token,
                "Content-Type" : "application/json"
        ]

        int seatCount = paramInt("eventSeatCount", "50")
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
        seatIds = seatList.collect { (it.id as long) }
        grinder.logger.info("Scenario D init: eventId={} seats={}", eventId, seatIds.size())
    }

    private static void teardownEvent() {
        if (eventId == null) return
        if (adminBearer == null || adminBearer.isBlank()) return
        try {
            Map<String, String> headers = ["Authorization": "Bearer " + adminBearer]
            setupRequest.DELETE(baseUrl + "/api/events/" + eventId, headers)
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
        int maxWaitSec = paramInt("admissionMaxWaitSec", "30")
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

    private boolean reserveOnce(long seatId, String admissionToken) {
        String url = baseUrl + "/api/events/" + eventId + "/reservations"
        Map<String, Object> body = ["seatId": seatId, "admissionToken": admissionToken]
        HTTPResponse resp = requestReserve.POST(url, body, headersAuthJson)
        if (resp.getStatusCode() != 200) {
            return false
        }
        reservedOk.incrementAndGet()
        return true
    }

    /** Poll until TTL+scheduler margin or sleep budget; exit early when seat is AVAILABLE. */
    private void waitForSeatReleasedAfterTtl(long seatId, int holdTtlSec, int sleepMs) {
        long deadline = System.currentTimeMillis() + sleepMs
        long readyAfter = System.currentTimeMillis() + (holdTtlSec + 8L) * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (System.currentTimeMillis() >= readyAfter && isSeatAvailable(seatId)) {
                grinder.logger.info("D early exit: seat AVAILABLE after holdTtlSec={}", holdTtlSec)
                return
            }
            try {
                Thread.sleep(1000)
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private boolean isSeatAvailable(long seatId) {
        String url = baseUrl + "/api/events/" + eventId + "/seats?refresh=true"
        HTTPResponse resp = requestSeatCheck.GET(url)
        if (resp.getStatusCode() != 200) {
            return false
        }
        def seatList = new JsonSlurper().parseText(resp.getBodyText()) as List
        def seat = seatList.find { (it.id as long) == seatId }
        if (seat == null) return false
        return "AVAILABLE".equalsIgnoreCase(seat.status as String)
    }

    private void verifySeatAvailable(long seatId) {
        String url = baseUrl + "/api/events/" + eventId + "/seats?refresh=true"
        HTTPResponse resp = requestSeatCheck.GET(url)
        if (resp.getStatusCode() != 200) {
            throw new IllegalStateException("seat list failed: status=" + resp.getStatusCode() + " body=" + resp.getBodyText())
        }
        def seatList = new JsonSlurper().parseText(resp.getBodyText()) as List
        def seat = seatList.find { (it.id as long) == seatId }
        if (seat == null) throw new IllegalStateException("seat not found in list: seatId=" + seatId)
        String st = seat.status as String
        if (!"AVAILABLE".equalsIgnoreCase(st)) {
            throw new IllegalStateException("seat not AVAILABLE after TTL: seatId=" + seatId + " status=" + st)
        }
        releasedOk.incrementAndGet()
    }
}

