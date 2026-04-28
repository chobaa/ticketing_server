import groovy.json.JsonSlurper
import net.grinder.script.GTest
import net.grinder.script.Grinder
import net.grinder.scriptengine.groovy.junit.GrinderRunner
import net.grinder.scriptengine.groovy.junit.annotation.AfterProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread
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
class DataIntegrityScenario {
    private static Map<String, String> scriptParams = parseScriptParams()

    private static Map<String, String> parseScriptParams() {
        String raw = System.getProperty("param", "")
        if (raw == null || raw.isBlank()) return [:]
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

    boolean done = false
    String bearer
    Map<String, String> headersAuthJson
    Long chosenSeatId

    @BeforeProcess
    static void beforeProcess() {
        runId = System.currentTimeMillis()
        baseUrl = param("baseUrl", "http://localhost:8080")
        adminPassword = param("adminPassword", "password123456")
        String adminEmailProp = param("adminEmail", "")
        adminEmail = adminEmailProp?.trim() ? adminEmailProp.trim() : "admin_${runId}@example.com"

        testJoin = new GTest(1, "04 data-integrity - joinQueue")
        testAdmission = new GTest(2, "04 data-integrity - admissionPoll")
        testReserve = new GTest(3, "04 data-integrity - reserve")

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
        testJoin.record(this, "04 data-integrity - joinQueue")
        testAdmission.record(this, "04 data-integrity - admissionPoll")
        testReserve.record(this, "04 data-integrity - reserve")
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
    }

    @Test
    void test() {
        if (done) return
        try {
            joinQueue()
            String admissionToken = pollAdmissionToken()
            reserveOnce(chosenSeatId, admissionToken)
        } finally {
            done = true
        }
    }

    @AfterProcess
    static void afterProcess() {
        grinder.logger.info("DataIntegrityScenario summary: successCount={}, uniqueSeats={}",
                successCount.get(), successSeatIds.size())

        assertThat(successCount.get(), is(greaterThan(0)))
        assertThat(successSeatIds.size(), is(greaterThan(0)))

        // 좌석 뷰(캐시) 기반으로 HELD 개수를 검증
        HTTPResponse seatsResp = setupRequest.GET(baseUrl + "/api/events/" + eventId + "/seats")
        if (seatsResp.getStatusCode() != 200) {
            throw new IllegalStateException("Seat fetch failed at end: status=" + seatsResp.getStatusCode() + " body=" + seatsResp.getBodyText())
        }
        def seatList = new JsonSlurper().parseText(seatsResp.getBodyText()) as List

        Map<Long, String> seatStatusById = [:]
        seatList.each { seatStatusById[(it.id as long)] = (it.status as String) }

        int heldCount = seatStatusById.values().count { it != null && it.equalsIgnoreCase("HELD") }

        // 정합성 핵심 불변식
        assertThat(heldCount, is(successSeatIds.size()))
        assertThat(heldCount <= seatIds.size(), is(true))

        // HELD로 남아있는 좌석은 반드시 성공한 예약 좌석이어야 함
        def heldSeatIds = seatStatusById.findAll { k, v -> v != null && v.equalsIgnoreCase("HELD") }.keySet() as Set
        assertThat(heldSeatIds.containsAll(successSeatIds.keySet() as Set), is(true))
        assertThat(successSeatIds.keySet().containsAll(heldSeatIds), is(true))

        teardownEvent()
    }

    private static void initEventAndSeats() {
        String token = registerOrLogin(adminEmail, adminPassword)
        adminBearer = token
        Map<String, String> adminHeadersJson = [
                "Authorization": "Bearer " + token,
                "Content-Type" : "application/json"
        ]

        int seatCount = paramInt("eventSeatCount", "5")
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
                grade     : grade
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
        grinder.logger.info("DataIntegrityScenario init: eventId={} seatCount={} seatIds={}",
                eventId, seatIds.size(), seatIds.size())
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

        Map<String, String> jsonHeaders = ["Content-Type": "application/json"]
        Map<String, Object> registerBody = ["email": email, "password": password]
        HTTPResponse regResp = setupRequest.POST(baseUrl + "/api/auth/register", registerBody, jsonHeaders)
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
        throw new IllegalStateException("Timeout waiting admission token")
    }

    private void reserveOnce(long seatId, String admissionToken) {
        String url = baseUrl + "/api/events/" + eventId + "/reservations"
        Map<String, Object> body = ["seatId": seatId, "admissionToken": admissionToken]
        HTTPResponse resp = requestReserve.POST(url, body, headersAuthJson)

        if (resp.getStatusCode() == 200) {
            def reservation = new JsonSlurper().parseText(resp.getBodyText())
            long reservedSeatId = reservation.seatId as long
            successSeatIds.put(reservedSeatId, true)
            successCount.incrementAndGet()
        }
    }
}

