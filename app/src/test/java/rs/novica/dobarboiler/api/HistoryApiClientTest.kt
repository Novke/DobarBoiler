package rs.novica.dobarboiler.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HistoryApiClientTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun client(base: String = server.url("/boiler/").toString()) = HistoryApiClient(base, "t0k", json)

    private val batch = ActionsBatch(
        installId = "inst-1",
        actions = listOf(
            ActionDto(localId = 7, at = "2026-10-18T14:01:00+02:00", kind = "CONVERGE", trig = "USER",
                detail = "temp 65", outcome = "SUCCESS", attempt = 1,
                stateJson = """{"powerOn":true,"targetTemp":65,"eco":true}"""),
        ),
    )

    @Test fun postsActionsWithBearerAndExactJson() = runTest {
        server.enqueue(MockResponse().setBody("""{"accepted":1,"duplicates":0}"""))
        val res = client().postActions(batch)
        assertEquals(ActionsResult(accepted = 1, duplicates = 0), res)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/boiler/api/v1/app/actions", req.path)
        assertEquals("Bearer t0k", req.getHeader("Authorization"))
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("inst-1", body["install_id"]!!.jsonPrimitive.content)
        val action = body["actions"]!!.jsonArray[0].jsonObject
        assertEquals(7L, action["local_id"]!!.jsonPrimitive.content.toLong())
        assertTrue(action.containsKey("state_json"))
        assertEquals("2026-10-18T14:01:00+02:00", action["at"]!!.jsonPrimitive.content)
    }

    @Test fun postsIntentWithSnakeCaseDesired() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        client().postIntent(
            IntentDto(installId = "inst-1", at = "2026-10-18T07:30:00+02:00", source = "DEFAULT_OFF",
                desired = DesiredDto(powerOn = false, targetTemp = null, eco = null), nextTransitionAt = null),
        )
        val req = server.takeRequest()
        assertEquals("/boiler/api/v1/app/intent", req.path)
        val desired = json.parseToJsonElement(req.body.readUtf8()).jsonObject["desired"]!!.jsonObject
        assertEquals("false", desired["power_on"]!!.jsonPrimitive.content)
    }

    @Test fun normalizesBaseUrlWithoutTrailingSlash() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"last_state_at":"2026-10-18T14:00:00+00:00"}"""))
        client(server.url("/boiler").toString()).health()
        assertEquals("/boiler/api/v1/health", server.takeRequest().path)
    }

    @Test fun non2xxThrows() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"invalid token"}"""))
        try {
            client().postActions(batch)
            fail("expected HistoryApiException")
        } catch (e: HistoryApiException) {
            assertEquals(401, e.status)
        }
    }

    @Test fun healthParses() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":false,"last_state_at":null,"last_energy_at":null,"paused_until":null}"""))
        assertEquals(HealthDto(ok = false, lastStateAt = null), client().health())
    }
}
