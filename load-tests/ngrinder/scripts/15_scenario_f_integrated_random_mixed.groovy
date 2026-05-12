import groovy.json.JsonSlurper
import net.grinder.script.GTest
import net.grinder.scriptengine.groovy.junit.GrinderRunner
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread
import org.junit.Test
import org.junit.runner.RunWith
import org.ngrinder.http.HTTPRequest
import org.ngrinder.http.HTTPResponse

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

import static net.grinder.script.Grinder.grinder

/**
 * Scenario F: Integrated mixed load — over wall-clock duration each VUser repeatedly picks a random
 * micro-behaviour inspired by A/B/C/D/E. Threads use distinct logins; occasionally an ephemeral
 * extra user is registered to mimic diverse sign-ups hitting read paths.
 *
 * Params (semicolon-separated, same as other scenarios):
 * - baseUrl, eventId (required from controller)
 * - testDurationSec (default 180; min 30 in script). Loop also exits early when /seats has no AVAILABLE row.
 * - admissionPollIntervalMs, admissionMaxWaitSec, progressPollIntervalMs, progressMaxWaitSec
 * - ephemeralUserProbability (default 0.12) chance per outer tick to register+read as throwaway user
 */
@RunWith(GrinderRunner)
class ScenarioFIntegratedRandomMixed {
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
        try {
            String v = param(key, defaultValue)
            if (v == null || v.isBlank()) return 0L
            return Long.parseLong(v.trim())
        } catch (Exception ignored) {
            return 0L
        }
    }

    private static double paramDouble(String key, String defaultValue) {
        try {
            return Double.parseDouble(param(key, defaultValue).trim())
        } catch (Exception ignored) {
            return Double.parseDouble(defaultValue)
        }
    }

    static GTest testMix
    static HTTPRequest requestMix
    static HTTPRequest setupRequest

    static long runId
    static String baseUrl
    static long eventId
    static String loadTestRunId

    static AtomicInteger ticksA = new AtomicInteger(0)
    static AtomicInteger ticksB = new AtomicInteger(0)
    static AtomicInteger ticksC = new AtomicInteger(0)
    static AtomicInteger ticksD = new AtomicInteger(0)
    static AtomicInteger ticksE = new AtomicInteger(0)
    static AtomicInteger ephemeralUsers = new AtomicInteger(0)

    String bearer
    Map<String, String> headersAuthJson

    @BeforeProcess
    static void beforeProcess() {
        runId = System.currentTimeMillis()
        baseUrl = param("baseUrl", "http://localhost:8080")
        eventId = paramLong("eventId", "0")
        loadTestRunId = param("runId", "")
        if (eventId <= 0) throw new IllegalStateException("eventId is required for scenario F")

        testMix = new GTest(1, "F integrated - mixed tick")
        requestMix = new HTTPRequest()
        setupRequest = new HTTPRequest()
        testMix.record(requestMix)
    }

    @BeforeThread
    void beforeThread() {
        testMix.record(this, "F integrated - mixed tick")
        grinder.statistics.delayReports = true

        int threadIdx = (grinder.threadNumber as int)
        int procNum = (grinder.getProcessNumber() as int)

        String userPassword = param("userPassword", "password123456")
        String userEmail = "mix_${runId}_${procNum}_${threadIdx}@example.com"
        bearer = registerOrLogin(userEmail, userPassword)
        headersAuthJson = [
                "Authorization": "Bearer " + bearer,
                "X-LoadTest-RunId": loadTestRunId,
                "Content-Type" : "application/json"
        ]
    }

    @Test
    void test() {
        int durSec = paramInt("testDurationSec", "180")
        if (durSec < 30) durSec = 30
        long endAt = System.currentTimeMillis() + durSec * 1000L
        double ephemProb = paramDouble("ephemeralUserProbability", "0.12")
        if (ephemProb < 0) ephemProb = 0
        if (ephemProb > 0.5) ephemProb = 0.5

        while (System.currentTimeMillis() < endAt) {
            if (noAvailableSeats()) {
                grinder.logger.info("Scenario F early exit: no AVAILABLE seats (inventory exhausted)")
                break
            }
            if (ThreadLocalRandom.current().nextDouble() < ephemProb) {
                maybeEphemeralSeatReader()
            }

            int mode = ThreadLocalRandom.current().nextInt(0, 5)
            switch (mode) {
                case 0:
                    runSliceA()
                    ticksA.incrementAndGet()
                    break
                case 1:
                    runSliceB()
                    ticksB.incrementAndGet()
                    break
                case 2:
                    runSliceC()
                    ticksC.incrementAndGet()
                    break
                case 3:
                    runSliceD()
                    ticksD.incrementAndGet()
                    break
                default:
                    runSliceE()
                    ticksE.incrementAndGet()
                    break
            }

            jitterSleep(10, 120)
        }

        grinder.logger.info(
                "Scenario F summary: ticksA={} ticksB={} ticksC={} ticksD={} ticksE={} ephemeralUsers={}",
                ticksA.get(), ticksB.get(), ticksC.get(), ticksD.get(), ticksE.get(), ephemeralUsers.get())
    }

    private void maybeEphemeralSeatReader() {
        try {
            String email = "ephem_${runId}_${grinder.threadNumber}_${System.nanoTime()}@example.com"
            String pwd = param("userPassword", "password123456")
            String tok = registerOrLogin(email, pwd)
            if (tok == null || tok.isBlank()) return
            Map<String, String> h = ["Authorization": "Bearer " + tok]
            String url = baseUrl + "/api/events/" + eventId + "/seats"
            int bursts = ThreadLocalRandom.current().nextInt(2, 8)
            for (int i = 0; i < bursts; i++) {
                requestMix.GET(url, [:], h)
            }
            ephemeralUsers.incrementAndGet()
        } catch (Exception ignored) {
        }
    }

    /** A-like: join + short admission polling + optional reserve first AVAILABLE */
    private void runSliceA() {
        joinQueue()
        String tok = pollAdmissionShort()
        if (tok != null) {
            reserveFirstAvailable(tok)
        }
        admissionPollBurst(3)
    }

    /** B-like: join + admission + hammer seat id #1 (hot key) */
    private void runSliceB() {
        joinQueue()
        String tok = pollAdmissionShort()
        if (tok == null) return
        long hotSeatId = firstSeatId()
        if (hotSeatId > 0) {
            postReserve(hotSeatId, tok)
        }
    }

    /** C-like: authenticated read storm */
    private void runSliceC() {
        String url = baseUrl + "/api/events/" + eventId + "/seats"
        int n = ThreadLocalRandom.current().nextInt(5, 25)
        for (int i = 0; i < n; i++) {
            requestMix.GET(url, [:], headersAuthJson)
        }
    }

    /** D-like: reserve then short sleep + seat check */
    private void runSliceD() {
        joinQueue()
        String tok = pollAdmissionShort()
        if (tok == null) return
        long sid = firstAvailableSeatId()
        if (sid <= 0) return
        if (postReserve(sid, tok)) {
            sleepMs(ThreadLocalRandom.current().nextInt(400, 2500))
            requestMix.GET(baseUrl + "/api/events/" + eventId + "/seats", [:], headersAuthJson)
        }
    }

    /** E-like: one full-ish attempt with bounded progress poll */
    private void runSliceE() {
        joinQueue()
        String tok = pollAdmissionLonger()
        if (tok == null) return
        long sid = seatIdByThreadRotation()
        if (sid <= 0) return
        Long rid = reserveReturningId(sid, tok)
        if (rid != null) {
            pollProgressShort(rid)
        }
    }

    private void joinQueue() {
        String url = baseUrl + "/api/events/" + eventId + "/queue"
        requestMix.POST(url, [:], headersAuthJson)
    }

    private void admissionPollBurst(int rounds) {
        String url = baseUrl + "/api/events/" + eventId + "/admission"
        for (int i = 0; i < rounds; i++) {
            requestMix.GET(url, [:], headersAuthJson)
            sleepMs(50)
        }
    }

    private String pollAdmissionShort() {
        return pollAdmission(paramInt("admissionPollIntervalMs", "200"), 8)
    }

    private String pollAdmissionLonger() {
        return pollAdmission(paramInt("admissionPollIntervalMs", "200"), 40)
    }

    private String pollAdmission(int intervalMs, int maxAttempts) {
        String url = baseUrl + "/api/events/" + eventId + "/admission"
        def json = new JsonSlurper()
        for (int i = 0; i < maxAttempts; i++) {
            HTTPResponse resp = requestMix.GET(url, [:], headersAuthJson)
            if (resp.getStatusCode() == 200) {
                try {
                    def body = json.parseText(resp.getBodyText())
                    String t = body.token as String
                    if (t != null && !t.isBlank()) return t
                } catch (Exception ignored) {
                }
            }
            sleepMs(Math.max(0, intervalMs))
        }
        return null
    }

    private long firstSeatId() {
        List seats = loadSeats()
        if (seats == null || seats.isEmpty()) return -1L
        return (seats.get(0).id as long)
    }

    private long firstAvailableSeatId() {
        List seats = loadSeats()
        if (seats == null) return -1L
        def s = seats.find { (it.status as String)?.equalsIgnoreCase("AVAILABLE") }
        return s == null ? -1L : (s.id as long)
    }

    private long seatIdByThreadRotation() {
        List seats = loadSeats()
        if (seats == null || seats.isEmpty()) return -1L
        int idx = (grinder.threadNumber as int) % seats.size()
        return (seats.get(idx).id as long)
    }

    private List loadSeats() {
        try {
            HTTPResponse resp = requestMix.GET(baseUrl + "/api/events/" + eventId + "/seats", [:], headersAuthJson)
            if (resp.getStatusCode() != 200) return null
            return new JsonSlurper().parseText(resp.getBodyText()) as List
        } catch (Exception ignored) {
            return null
        }
    }

    /** True when seat list has no AVAILABLE rows (SOLD/HELD/etc. only). Ends wall-clock loop early. */
    private boolean noAvailableSeats() {
        List seats = loadSeats()
        if (seats == null || seats.isEmpty()) return false
        return !seats.any { (it.status as String)?.equalsIgnoreCase("AVAILABLE") }
    }

    private void reserveFirstAvailable(String admissionToken) {
        long sid = firstAvailableSeatId()
        if (sid > 0) postReserve(sid, admissionToken)
    }

    private boolean postReserve(long seatId, String admissionToken) {
        try {
            String url = baseUrl + "/api/events/" + eventId + "/reservations"
            Map<String, Object> body = ["seatId": seatId, "admissionToken": admissionToken]
            HTTPResponse resp = requestMix.POST(url, body, headersAuthJson)
            return resp.getStatusCode() == 200
        } catch (Exception ignored) {
            return false
        }
    }

    private Long reserveReturningId(long seatId, String admissionToken) {
        try {
            String url = baseUrl + "/api/events/" + eventId + "/reservations"
            Map<String, Object> body = ["seatId": seatId, "admissionToken": admissionToken]
            HTTPResponse resp = requestMix.POST(url, body, headersAuthJson)
            if (resp.getStatusCode() != 200) return null
            def r = new JsonSlurper().parseText(resp.getBodyText())
            return r.id as Long
        } catch (Exception ignored) {
            return null
        }
    }

    private void pollProgressShort(long reservationId) {
        String url = baseUrl + "/api/events/" + eventId + "/reservations/" + reservationId + "/progress"
        int interval = paramInt("progressPollIntervalMs", "200")
        int maxAttempts = paramInt("progressShortMaxAttempts", "40")
        def json = new JsonSlurper()
        for (int i = 0; i < maxAttempts; i++) {
            HTTPResponse resp = requestMix.GET(url, [:], headersAuthJson)
            if (resp.getStatusCode() != 200) return
            try {
                def obj = json.parseText(resp.getBodyText())
                String st = obj.reservationStatus as String
                if ("CONFIRMED".equalsIgnoreCase(st) || "CANCELED".equalsIgnoreCase(st)) return
            } catch (Exception ignored) {
                return
            }
            sleepMs(interval)
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

    private static void sleepMs(int ms) {
        if (ms <= 0) return
        try {
            Thread.sleep(ms)
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt()
        }
    }

    private static void jitterSleep(int minMs, int maxMs) {
        int lo = Math.min(minMs, maxMs)
        int hi = Math.max(minMs, maxMs)
        int span = Math.max(1, hi - lo)
        sleepMs(lo + ThreadLocalRandom.current().nextInt(span + 1))
    }
}
