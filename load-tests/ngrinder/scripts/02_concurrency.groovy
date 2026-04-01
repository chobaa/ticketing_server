import groovy.json.JsonSlurper
import net.grinder.script.GTest
import net.grinder.script.Grinder
import net.grinder.scriptengine.groovy.junit.GrinderRunner
import net.grinder.scriptengine.groovy.junit.annotation.AfterProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread
import net.grinder.scriptengine.groovy.junit.annotation.Test
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
class ConcurrencyScenario {
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

    static Long eventId
    static Long targetSeatId

    static AtomicInteger attemptedReserveCount = new AtomicInteger(0)
    static AtomicInteger successCount = new AtomicInteger(0)
    static ConcurrentHashMap<Long, Boolean> successSeatIds = new ConcurrentHashMap<>()

    boolean done = false
    String bearer
    Map<String, String> headersAuthJson
    String chosenSeatLabel

    @BeforeProcess
    static void beforeProcess() {
        runId = System.currentTimeMillis()
        baseUrl = Grinder.grinderProperties.getProperty("baseUrl", "http://localhost:8080")
        adminPassword = Grinder.grinderProperties.getProperty("adminPassword", "password123456")
        String adminEmailProp = Grinder.grinderProperties.getProperty("adminEmail", "")
        adminEmail = adminEmailProp?.trim() ? adminEmailProp.trim() : "admin_${runId}@example.com"

        testJoin = new GTest(1, "02 concurrency - joinQueue")
        testAdmission = new GTest(2, "02 concurrency - admissionPoll")
        testReserve = new GTest(3, "02 concurrency - reserve")

        requestJoin = new HTTPRequest()
        requestAdmission = new HTTPRequest()
        requestReserve = new HTTPRequest()
        testJoin.record(requestJoin)
        testAdmission.record(requestAdmission)
        testReserve.record(requestReserve)

        setupRequest = new HTTPRequest()

        initEventAndSeat()
    }

    @BeforeThread
    void beforeThread() {
        testJoin.record(this, "02 concurrency - joinQueue")
        testAdmission.record(this, "02 concurrency - admissionPoll")
        testReserve.record(this, "02 concurrency - reserve")
        grinder.statistics.delayReports = true

        int threadIdx = (grinder.threadNumber as int)
        int procNum = (grinder.getProcessNumber() as int)

        String userPassword = Grinder.grinderProperties.getProperty("userPassword", "password123456")
        String userEmail = "vuser_${runId}_${procNum}_${threadIdx}@example.com"

        String token = registerOrLogin(userEmail, userPassword)
        bearer = token
        headersAuthJson = [
                "Authorization": "Bearer " + bearer,
                "Content-Type" : "application/json"
        ]
        chosenSeatLabel = "targetSeatId=" + targetSeatId
    }

    @Test
    void test() {
        if (done) return
        try {
            joinQueue()
            String admissionToken = pollAdmissionToken()
            attemptedReserveCount.incrementAndGet()
            reserveOnce(admissionToken)
        } finally {
            done = true
        }
    }

    @AfterProcess
    static void afterProcess() {
        grinder.logger.info("ConcurrencyScenario summary: attempted={}, success={}",
                attemptedReserveCount.get(), successCount.get())

        assertThat(attemptedReserveCount.get(), is(greaterThan(0)))
        assertThat(successCount.get(), is(1))
        assertThat(successSeatIds.size(), is(1))
        assertThat(successSeatIds.containsKey(targetSeatId), is(true))
    }

    private static void initEventAndSeat() {
        String token = registerOrLogin(adminEmail, adminPassword)
        Map<String, String> adminHeadersJson = [
                "Authorization": "Bearer " + token,
                "Content-Type" : "application/json"
        ]

        int seatCount = (Grinder.grinderProperties.getProperty("eventSeatCount", "1") as int)
        if (seatCount < 1) seatCount = 1
        BigDecimal seatPrice = new BigDecimal(Grinder.grinderProperties.getProperty("seatPrice", "100.00"))
        String grade = Grinder.grinderProperties.getProperty("seatGrade", "R")

        String eventName = Grinder.grinderProperties.getProperty("eventName", "event_${runId}")
        String venue = Grinder.grinderProperties.getProperty("eventVenue", "seoul")
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
        List<Long> seatIds = seatList.collect { (it.id as long) }
        targetSeatId = seatIds.get(0)
        grinder.logger.info("ConcurrencyScenario init: eventId={} targetSeatId={}", eventId, targetSeatId)
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
        int pollIntervalMs = (Grinder.grinderProperties.getProperty("admissionPollIntervalMs", "200") as int)
        int maxWaitSec = (Grinder.grinderProperties.getProperty("admissionMaxWaitSec", "20") as int)
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

    private void reserveOnce(String admissionToken) {
        String url = baseUrl + "/api/events/" + eventId + "/reservations"
        Map<String, Object> body = ["seatId": targetSeatId, "admissionToken": admissionToken]
        HTTPResponse resp = requestReserve.POST(url, body, headersAuthJson)

        if (resp.getStatusCode() == 200) {
            def reservation = new JsonSlurper().parseText(resp.getBodyText())
            long reservedSeatId = reservation.seatId as long
            successSeatIds.put(reservedSeatId, true)
            successCount.incrementAndGet()
        } else {
            grinder.logger.info("reserve failed status={} (expected for contention). body={}",
                    resp.getStatusCode(), resp.getBodyText())
        }
    }
}

