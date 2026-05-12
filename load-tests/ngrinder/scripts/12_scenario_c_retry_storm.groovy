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
class ScenarioCRetryStorm {
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

    private static Long paramLong(String key, String defaultValue) {
        try {
            String v = param(key, defaultValue)
            if (v == null || v.isBlank()) return null
            return Long.parseLong(v.trim())
        } catch (Exception ignored) {
            return null
        }
    }

    static GTest testRead
    static GTest testSetup

    static HTTPRequest requestRead
    static HTTPRequest setupRequest

    static long runId
    static String baseUrl
    static String adminEmail
    static String adminPassword
    static String adminBearer
    static Long eventId
    static String loadTestRunId

    static AtomicInteger okCount = new AtomicInteger(0)
    static AtomicInteger rateLimitedCount = new AtomicInteger(0)

    String bearer
    Map<String, String> headersAuthJson
    long endAtMs

    @BeforeProcess
    static void beforeProcess() {
        runId = System.currentTimeMillis()
        baseUrl = param("baseUrl", "http://localhost:8080")
        loadTestRunId = param("runId", "")
        adminPassword = param("adminPassword", "password123456")
        String adminEmailProp = param("adminEmail", "")
        adminEmail = adminEmailProp?.trim() ? adminEmailProp.trim() : "admin_${runId}@example.com"

        testRead = new GTest(1, "C retry-storm - readSeats")
        testSetup = new GTest(0, "C retry-storm - setup")
        requestRead = new HTTPRequest()
        testRead.record(requestRead)

        setupRequest = new HTTPRequest()
        Long existingEventId = paramLong("eventId", "")
        if (existingEventId != null && existingEventId > 0) {
            eventId = existingEventId
            grinder.logger.info("Scenario C init: using existing eventId={}", eventId)
        } else {
            initEvent()
        }
    }

    @BeforeThread
    void beforeThread() {
        testRead.record(this, "C retry-storm - readSeats")
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

        int testDurationSec = paramInt("testDurationSec", "20")
        if (testDurationSec < 1) testDurationSec = 20
        endAtMs = System.currentTimeMillis() + (testDurationSec * 1000L)
    }

    @Test
    void test() {
        while (System.currentTimeMillis() < endAtMs) {
            HTTPResponse resp = requestRead.GET(baseUrl + "/api/events/" + eventId + "/seats", [:], headersAuthJson)
            if (resp.getStatusCode() == 200) {
                okCount.incrementAndGet()
            } else if (resp.getStatusCode() == 429) {
                rateLimitedCount.incrementAndGet()
            }
        }
    }

    @AfterProcess
    static void afterProcess() {
        grinder.logger.info("Scenario C summary: ok={} rateLimited(429)={}", okCount.get(), rateLimitedCount.get())
        assertThat(okCount.get() + rateLimitedCount.get(), is(greaterThan(0)))
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

        int seatCount = paramInt("eventSeatCount", "72")
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
        grinder.logger.info("Scenario C init: eventId={}", eventId)
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
}

