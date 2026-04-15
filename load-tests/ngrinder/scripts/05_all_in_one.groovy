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
import static org.hamcrest.Matchers.greaterThan
import static org.hamcrest.Matchers.is

@RunWith(GrinderRunner)
class AllInOneScenario {
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

    static GTest testJoin
    static GTest testAdmission
    static GTest testReserve
    static GTest testCancel
    static GTest testProgress
    static GTest testSeatCheck

    static HTTPRequest requestJoin
    static HTTPRequest requestAdmission
    static HTTPRequest requestReserve
    static HTTPRequest requestCancel
    static HTTPRequest requestProgress
    static HTTPRequest requestSeatCheck

    static HTTPRequest setupRequest

    static long runId
    static String baseUrl
    static String adminEmail
    static String adminPassword

    static Long eventId
    static List<Long> seatIds = []

    // Metrics / validations across process
    static AtomicInteger reserveAttempted = new AtomicInteger(0)
    static AtomicInteger reserveSucceeded = new AtomicInteger(0)
    static AtomicInteger cancelSucceeded = new AtomicInteger(0)
    static AtomicInteger wrongSeatCount = new AtomicInteger(0)

    // Tracks currently-held seats to detect double-success concurrency bug
    static ConcurrentHashMap<Long, Long> activeSeatToReservation = new ConcurrentHashMap<>()

    // per thread
    String bearer
    Map<String, String> headersAuthJson
    String admissionTokenCached
    long endAtMs
    long chosenSeatId

    @BeforeProcess
    static void beforeProcess() {
        runId = System.currentTimeMillis()
        baseUrl = param("baseUrl", "http://localhost:8080")
        adminPassword = param("adminPassword", "password123456")
        String adminEmailProp = param("adminEmail", "")
        adminEmail = adminEmailProp?.trim() ? adminEmailProp.trim() : "admin_${runId}@example.com"

        testJoin = new GTest(1, "05 all-in-one - joinQueue")
        testAdmission = new GTest(2, "05 all-in-one - admissionPoll")
        testReserve = new GTest(3, "05 all-in-one - reserve")
        testCancel = new GTest(4, "05 all-in-one - cancel")
        testProgress = new GTest(5, "05 all-in-one - progress")
        testSeatCheck = new GTest(6, "05 all-in-one - seatCheck")

        requestJoin = new HTTPRequest()
        requestAdmission = new HTTPRequest()
        requestReserve = new HTTPRequest()
        requestCancel = new HTTPRequest()
        requestProgress = new HTTPRequest()
        requestSeatCheck = new HTTPRequest()

        testJoin.record(requestJoin)
        testAdmission.record(requestAdmission)
        testReserve.record(requestReserve)
        testCancel.record(requestCancel)
        testProgress.record(requestProgress)
        testSeatCheck.record(requestSeatCheck)

        setupRequest = new HTTPRequest()
        initEventAndSeats()
    }

    @BeforeThread
    void beforeThread() {
        testJoin.record(this, "05 all-in-one - joinQueue")
        testAdmission.record(this, "05 all-in-one - admissionPoll")
        testReserve.record(this, "05 all-in-one - reserve")
        testCancel.record(this, "05 all-in-one - cancel")
        testProgress.record(this, "05 all-in-one - progress")
        testSeatCheck.record(this, "05 all-in-one - seatCheck")
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

        int testDurationSec = paramInt("testDurationSec", "30")
        if (testDurationSec < 1) testDurationSec = 30
        endAtMs = System.currentTimeMillis() + (testDurationSec * 1000L)

        int poolSize = paramInt("seatPoolSize", "10")
        if (poolSize < 1) poolSize = 1
        if (seatIds.isEmpty()) {
            chosenSeatId = 1L
        } else {
            int effectivePool = Math.min(poolSize, seatIds.size())
            chosenSeatId = seatIds.get(threadIdx % effectivePool) as long
        }
    }

    @Test
    void test() {
        while (System.currentTimeMillis() < endAtMs) {
            if (admissionTokenCached == null) {
                joinQueue()
                admissionTokenCached = pollAdmissionToken()
            }

            reserveAttempted.incrementAndGet()
            Long reservationId = reserveOnce(chosenSeatId, admissionTokenCached)
            if (reservationId != null) {
                // Verify reservation belongs to the seat this user attempted
                verifyProgress(reservationId, ["PENDING_PAYMENT", "CONFIRMED", "CANCELED"])
                cancelReservation(reservationId)
                verifyProgress(reservationId, ["CANCELED"])
                verifySeatAvailable(chosenSeatId)
            }

            try {
                Thread.sleep(10 + (grinder.threadNumber % 7))
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt()
            }
        }
    }

    @AfterProcess
    static void afterProcess() {
        grinder.logger.info(
                "AllInOneScenario summary: attempted={}, reserveOk={}, cancelOk={}, wrongSeat={}, activeSeats={}",
                reserveAttempted.get(),
                reserveSucceeded.get(),
                cancelSucceeded.get(),
                wrongSeatCount.get(),
                activeSeatToReservation.size()
        )

        // 1) Must have at least one successful reserve+cancel cycle
        assertThat(reserveSucceeded.get(), is(greaterThan(0)))
        assertThat(cancelSucceeded.get(), is(greaterThan(0)))

        // 2) No user should receive a different seat than requested
        assertThat(wrongSeatCount.get(), is(0))

        // 3) No leftover active seat lock from the test flow
        assertThat(activeSeatToReservation.size(), is(0))
    }

    private static void initEventAndSeats() {
        String token = registerOrLogin(adminEmail, adminPassword)
        Map<String, String> adminHeadersJson = [
                "Authorization": "Bearer " + token,
                "Content-Type" : "application/json"
        ]

        int seatCount = paramInt("eventSeatCount", "400")
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
        grinder.logger.info("AllInOneScenario init: eventId={} seats={}", eventId, seatIds.size())
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

    private Long reserveOnce(long seatId, String admissionToken) {
        String url = baseUrl + "/api/events/" + eventId + "/reservations"
        Map<String, Object> body = ["seatId": seatId, "admissionToken": admissionToken]
        HTTPResponse resp = requestReserve.POST(url, body, headersAuthJson)

        // Under contention it's expected to fail often; only 200 means reservation created.
        if (resp.getStatusCode() != 200) return null

        def reservation = new JsonSlurper().parseText(resp.getBodyText())
        long reservationId = reservation.id as long
        long reservedSeatId = reservation.seatId as long

        if (reservedSeatId != seatId) {
            wrongSeatCount.incrementAndGet()
            grinder.logger.error("Wrong seat reserved: expected={} actual={} reservationId={}", seatId, reservedSeatId, reservationId)
        }

        // Concurrency guard: the same seat should not be successfully reserved twice at the same time
        Long prev = activeSeatToReservation.putIfAbsent(seatId, reservationId)
        if (prev != null) {
            throw new IllegalStateException("Concurrency bug suspected: seat " + seatId + " reserved twice (prevReservationId=" + prev + ", newReservationId=" + reservationId + ")")
        }

        reserveSucceeded.incrementAndGet()
        return reservationId
    }

    private void cancelReservation(long reservationId) {
        String url = baseUrl + "/api/events/" + eventId + "/reservations/" + reservationId + "/cancel"
        HTTPResponse resp = requestCancel.POST(url, [:], ["Authorization": "Bearer " + bearer])
        if (resp.getStatusCode() != 204) {
            // ensure we don't leak activeSeats on cancel failure
            activeSeatToReservation.values().removeIf { it == reservationId }
            throw new IllegalStateException("cancel failed: status=" + resp.getStatusCode() + " body=" + resp.getBodyText())
        }

        // remove seat from active map
        activeSeatToReservation.remove(chosenSeatId, reservationId as Long)
        cancelSucceeded.incrementAndGet()
    }

    private void verifyProgress(long reservationId, List<String> expectedStatuses) {
        String url = baseUrl + "/api/events/" + eventId + "/reservations/" + reservationId + "/progress"
        HTTPResponse resp = requestProgress.GET(url, [:], ["Authorization": "Bearer " + bearer])
        if (resp.getStatusCode() != 200) {
            throw new IllegalStateException("progress failed: status=" + resp.getStatusCode() + " body=" + resp.getBodyText())
        }
        def obj = new JsonSlurper().parseText(resp.getBodyText())
        String st = (obj.reservationStatus as String)
        if (st == null || !expectedStatuses.any { it.equalsIgnoreCase(st) }) {
            throw new IllegalStateException("unexpected reservationStatus=" + st + " expected=" + expectedStatuses)
        }
    }

    private void verifySeatAvailable(long seatId) {
        String url = baseUrl + "/api/events/" + eventId + "/seats"
        HTTPResponse resp = requestSeatCheck.GET(url)
        if (resp.getStatusCode() != 200) {
            throw new IllegalStateException("seat list failed: status=" + resp.getStatusCode() + " body=" + resp.getBodyText())
        }
        def seatList = new JsonSlurper().parseText(resp.getBodyText()) as List
        def seat = seatList.find { (it.id as long) == seatId }
        if (seat == null) throw new IllegalStateException("seat not found in list: seatId=" + seatId)
        String st = seat.status as String
        if (!"AVAILABLE".equalsIgnoreCase(st)) {
            throw new IllegalStateException("seat not AVAILABLE after cancel: seatId=" + seatId + " status=" + st)
        }
    }
}

