package rs.novica.dobarboiler.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AristonHttpClientTest {

    private lateinit var server: MockWebServer
    private lateinit var creds: FakeCredentialProvider
    private lateinit var client: AristonHttpClient

    private val json = Json { ignoreUnknownKeys = true }

    private val stateJson = """
        {
          "gw": "ABCDEF012345",
          "on": true,
          "mode": 1,
          "temp": 54.0,
          "reqTemp": 65.0,
          "procReqTemp": 65.0,
          "eco": false,
          "pwrOpt": false,
          "antiLeg": false,
          "heatReq": true,
          "avShw": 2,
          "rmTm": "01:23:00",
          "someUnknownField": {"nested": 1}
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        creds = FakeCredentialProvider(email = "user@example.com", password = "secret")
        client = AristonHttpClient(
            credentials = creds,
            json = json,
            baseUrl = server.url("/api/v2/").toString(),
            retryDelayMillis = 0L, // no real 5 s sleeps in tests
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------------------ login

    @Test
    fun `login posts usr-pwd, stores and returns token, sends required headers`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"token":"tok-1","act":{"email":"user@example.com"}}"""),
        )

        val token = client.login("user@example.com", "secret")

        assertEquals("tok-1", token)
        assertEquals("tok-1", creds.token())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v2/accounts/login", recorded.path)
        assertEquals("RestSharp/106.11.7.0", recorded.getHeader("User-Agent"))
        assertEquals("", recorded.getHeader("ar.authToken"))
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("""{"usr":"user@example.com","pwd":"secret"}""", recorded.body.readUtf8())
    }

    @Test
    fun `login failure throws AuthFailed and stores no token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("bad credentials"))

        try {
            client.login("user@example.com", "wrong")
            fail("expected AuthFailed")
        } catch (e: AristonException.AuthFailed) {
            // expected
        }
        assertNull(creds.token())
    }

    @Test
    fun `request without stored token logs in first`() = runBlocking {
        creds.storeToken(null)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"fresh"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(stateJson))

        val state = client.getState("ABCDEF012345")

        assertEquals(2, server.requestCount)
        val loginReq = server.takeRequest()
        assertEquals("/api/v2/accounts/login", loginReq.path)
        val stateReq = server.takeRequest()
        assertEquals("/api/v2/velis/medPlantData/ABCDEF012345", stateReq.path)
        assertEquals("fresh", stateReq.getHeader("ar.authToken"))
        assertEquals(54.0, state.temp, 0.0)
    }

    // -------------------------------------------------- 405 token refresh

    @Test
    fun `405 triggers single re-login and retries original request with fresh token`() = runBlocking {
        creds.storeToken("stale")
        server.enqueue(MockResponse().setResponseCode(405)) // original request, stale token
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"fresh"}""")) // re-login
        server.enqueue(MockResponse().setResponseCode(200).setBody(stateJson)) // retried request

        val state = client.getState("ABCDEF012345")

        assertEquals(3, server.requestCount)

        val first = server.takeRequest()
        assertEquals("/api/v2/velis/medPlantData/ABCDEF012345", first.path)
        assertEquals("stale", first.getHeader("ar.authToken"))

        val relogin = server.takeRequest()
        assertEquals("/api/v2/accounts/login", relogin.path)
        assertEquals("""{"usr":"user@example.com","pwd":"secret"}""", relogin.body.readUtf8())

        val retried = server.takeRequest()
        assertEquals("/api/v2/velis/medPlantData/ABCDEF012345", retried.path)
        assertEquals("fresh", retried.getHeader("ar.authToken"))

        assertEquals("fresh", creds.token())
        assertEquals(true, state.on)
        assertEquals(1, state.mode)
        assertEquals(65.0, state.reqTemp, 0.0)
        assertEquals("01:23:00", state.rmTm)
    }

    @Test
    fun `second 405 after token refresh throws AuthFailed`() = runBlocking {
        creds.storeToken("stale")
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"fresh"}"""))
        server.enqueue(MockResponse().setResponseCode(405))

        try {
            client.getState("ABCDEF012345")
            fail("expected AuthFailed")
        } catch (e: AristonException.AuthFailed) {
            // expected
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `405 with failing re-login throws AuthFailed`() = runBlocking {
        creds.storeToken("stale")
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(403).setBody("nope")) // re-login rejected

        try {
            client.getState("ABCDEF012345")
            fail("expected AuthFailed")
        } catch (e: AristonException.AuthFailed) {
            assertTrue(e.message!!.contains("password changed"))
        }
    }

    @Test
    fun `405 with re-login hitting 5xx throws Transient, not AuthFailed`() = runBlocking {
        creds.storeToken("stale")
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway")) // re-login attempt 1
        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway")) // re-login retry

        try {
            client.getState("ABCDEF012345")
            fail("expected Transient")
        } catch (e: AristonException.Transient) {
            // a cloud outage during re-login is a connection problem, not bad credentials
        }
    }

    // ------------------------------------------- bare-boolean serialization

    @Test
    fun `setPower true sends bare JSON literal true`() = runBlocking {
        creds.storeToken("tok")
        server.enqueue(MockResponse().setResponseCode(200))

        client.setPower("ABCDEF012345", true)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v2/velis/medPlantData/ABCDEF012345/switch", recorded.path)
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("true", recorded.body.readUtf8())
    }

    @Test
    fun `setEco false sends bare JSON literal false`() = runBlocking {
        creds.storeToken("tok")
        server.enqueue(MockResponse().setResponseCode(200))

        client.setEco("ABCDEF012345", false)

        val recorded = server.takeRequest()
        assertEquals("/api/v2/velis/medPlantData/ABCDEF012345/switchEco", recorded.path)
        assertEquals("false", recorded.body.readUtf8())
    }

    @Test
    fun `setTemperature sends new-object body`() = runBlocking {
        creds.storeToken("tok")
        server.enqueue(MockResponse().setResponseCode(200))

        client.setTemperature("ABCDEF012345", 60.0)

        val recorded = server.takeRequest()
        assertEquals("/api/v2/velis/medPlantData/ABCDEF012345/temperature", recorded.path)
        assertEquals("""{"new":60.0}""", recorded.body.readUtf8())
    }

    @Test
    fun `setMaxSetpoint sends old-new plant setting body`() = runBlocking {
        creds.storeToken("tok")
        server.enqueue(MockResponse().setResponseCode(200))

        client.setMaxSetpoint("ABCDEF012345", newTemp = 75.0, oldTemp = 65.0)

        val recorded = server.takeRequest()
        assertEquals("/api/v2/velis/medPlantData/ABCDEF012345/plantSettings", recorded.path)
        assertEquals("""{"MedMaxSetpointTemperature":{"new":75.0,"old":65.0}}""", recorded.body.readUtf8())
    }

    // --------------------------------------------------- error semantics

    @Test
    fun `404 throws NotFound without retry`() = runBlocking {
        creds.storeToken("tok")
        server.enqueue(MockResponse().setResponseCode(404))

        try {
            client.getState("MISSING")
            fail("expected NotFound")
        } catch (e: AristonException.NotFound) {
            // expected
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `429 throws RateLimited immediately with no retry`() = runBlocking {
        creds.storeToken("tok")
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))

        try {
            client.getState("ABCDEF012345")
            fail("expected RateLimited")
        } catch (e: AristonException.RateLimited) {
            assertEquals("slow down", e.message)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `transient 500 is retried once then succeeds`() = runBlocking {
        creds.storeToken("tok")
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody(stateJson))

        val state = client.getState("ABCDEF012345")

        assertEquals(2, server.requestCount)
        assertEquals(54.0, state.temp, 0.0)
    }

    @Test
    fun `transient 500 twice throws Transient`() = runBlocking {
        creds.storeToken("tok")
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(502))

        try {
            client.getState("ABCDEF012345")
            fail("expected Transient")
        } catch (e: AristonException.Transient) {
            assertTrue(e.message!!.contains("502"))
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `M2M 404 from the gateway throws DeviceOffline without retry`() = runBlocking {
        creds.storeToken("tok")
        // Verbatim body the cloud returned for the whole 2026-10-27..29 power outage.
        server.enqueue(
            MockResponse().setResponseCode(500).setBody(
                "\":-( M2M platform error from 80646F26422C (Http status: 404 - Not Found) (FailureId: 170711215454)\"",
            ),
        )

        try {
            client.getState("80646F26422C")
            fail("expected DeviceOffline")
        } catch (e: AristonException.DeviceOffline) {
            assertTrue(e.message!!.contains("M2M"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `M2M error with another inner status stays Transient`() = runBlocking {
        creds.storeToken("tok")
        val body = "\":-( M2M platform error from 80646F26422C (Http status: 408 - Request Timeout)\""
        server.enqueue(MockResponse().setResponseCode(500).setBody(body))
        server.enqueue(MockResponse().setResponseCode(500).setBody(body))

        try {
            client.getState("80646F26422C")
            fail("expected Transient")
        } catch (e: AristonException.Transient) {
            // expected
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `no credentials and no token throws AuthFailed without network calls`() = runBlocking {
        val noCreds = FakeCredentialProvider(email = null, password = null)
        val c = AristonHttpClient(
            credentials = noCreds,
            json = json,
            baseUrl = server.url("/api/v2/").toString(),
            retryDelayMillis = 0L,
        )

        try {
            c.getState("ABCDEF012345")
            fail("expected AuthFailed")
        } catch (e: AristonException.AuthFailed) {
            assertEquals("no credentials", e.message)
        }
        assertEquals(0, server.requestCount)
    }

    // ------------------------------------------------------------ fixtures

    private class FakeCredentialProvider(
        private val email: String?,
        private val password: String?,
    ) : CredentialProvider {
        private var storedToken: String? = null

        override fun credentials(): Pair<String, String>? {
            val e = email ?: return null
            val p = password ?: return null
            return e to p
        }

        override fun token(): String? = storedToken

        override fun storeToken(token: String?) {
            storedToken = token
        }
    }
}
