package rs.novica.dobarboiler.ui.settings

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rs.novica.dobarboiler.api.HistoryApiClient
import java.time.Duration
import java.time.Instant

class ConnectionTestTest {
    private lateinit var server: MockWebServer
    private val now = Instant.parse("2026-10-20T10:00:00Z")

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/health") ->
                    MockResponse().setBody("""{"ok":true,"last_state_at":"2026-10-20T09:58:00+00:00"}""")
                request.getHeader("Authorization") != "Bearer good" -> MockResponse().setResponseCode(401)
                else -> MockResponse().setBody("""{"accepted":0,"duplicates":0}""")
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun client(token: String) = HistoryApiClient(server.url("/boiler/").toString(), token, Json { ignoreUnknownKeys = true })

    @Test fun wrongTokenFailsEvenThoughHealthIsPublic() = runTest {
        val r = testConnection(client("typo"), now)
        assertTrue(r is TestResult.Failed)
    }

    @Test fun goodTokenReportsLastStateAge() = runTest {
        assertEquals(TestResult.Ok(Duration.ofMinutes(2)), testConnection(client("good"), now))
    }
}
