package rs.novica.dobarboiler.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rs.novica.dobarboiler.api.HistoryApiClient

import rs.novica.dobarboiler.domain.DesiredState
import rs.novica.dobarboiler.domain.Resolution
import rs.novica.dobarboiler.domain.StateSource
import rs.novica.dobarboiler.domain.Transition
import rs.novica.dobarboiler.domain.TransitionReason
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ServerSyncTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
    private val clock = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC)
    private val requests = mutableListOf<Pair<String, String>>()
    private var failOnCall = -1

    private class MemStore(var state: SyncState = SyncState()) : SyncStateStore {
        override suspend fun syncState() = state
        override suspend fun updateSync(transform: (SyncState) -> SyncState) { state = transform(state) }
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request.path!! to request.body.readUtf8()
                if (requests.size == failOnCall) return MockResponse().setResponseCode(500)
                return if (request.path!!.endsWith("intent")) MockResponse().setResponseCode(204)
                else MockResponse().setBody("""{"accepted":1,"duplicates":0}""")
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun rows(ids: LongRange) = ids.map {
        ActionLogEntity(id = it, at = Instant.parse("2026-10-18T10:00:00Z").plusSeconds(it), kind = "CONVERGE",
            trig = "USER", detail = "d$it", outcome = "SUCCESS")
    }

    private fun sync(all: List<ActionLogEntity>, store: SyncStateStore, configured: Boolean = true) = ServerSync(
        fetchAfter = { after, limit -> all.filter { it.id > after }.sortedBy { it.id }.take(limit) },
        store = store,
        clientFactory = { if (configured) HistoryApiClient(server.url("/boiler/").toString(), "tok", json) else null },
        clock = clock,
    )

    private fun actionPosts() = requests.filter { it.first.endsWith("/app/actions") }

    @Test fun uploadsInBatchesOf200() = runTest {
        val store = MemStore()
        val res = sync(rows(1L..450L), store).run()
        assertEquals(SyncResult.Ok(actions = 450, intent = false), res)
        assertEquals(listOf(200, 200, 50), actionPosts().map {
            json.parseToJsonElement(it.second).jsonObject["actions"]!!.jsonArray.size
        })
        assertEquals(450L, store.state.lastUploadedActionId)
        assertNotNull(store.state.lastSyncOkAt)
    }

    @Test fun markAdvancesOnlyOn2xx() = runTest {
        failOnCall = 2
        val store = MemStore()
        val res = sync(rows(1L..450L), store).run()
        assertTrue(res is SyncResult.Failed)
        assertEquals(200L, store.state.lastUploadedActionId)
        assertNotNull(store.state.lastSyncError)
    }

    @Test fun gapInIdsIsFine() = runTest {
        val store = MemStore(SyncState(lastUploadedActionId = 10))
        sync(rows(900L..950L), store).run()
        assertEquals(1, actionPosts().size)
        assertEquals(950L, store.state.lastUploadedActionId)
    }

    @Test fun firstRunGeneratesInstallIdOnce() = runTest {
        val store = MemStore()
        sync(rows(1L..1L), store).run()
        sync(rows(1L..2L), store).run()
        val ids = actionPosts().map { json.parseToJsonElement(it.second).jsonObject["install_id"]!!.jsonPrimitive.content }
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1])
        assertEquals(ids[0], store.state.installId)
    }

    @Test fun unconfiguredIsNoop() = runTest {
        val res = sync(rows(1L..5L), MemStore(), configured = false).run()
        assertEquals(SyncResult.NotConfigured, res)
        assertTrue(requests.isEmpty())
    }

    private fun resolution(source: StateSource = StateSource.BOOST) = Resolution(
        desired = DesiredState(powerOn = true, targetTemp = 60, eco = false),
        source = source,
        nextTransition = Transition(Instant.parse("2026-10-18T14:00:00Z"), TransitionReason.BOOST_TIMEOUT),
    )

    @Test fun intentSentOnlyWhenChanged() = runTest {
        val store = MemStore(SyncState(lastIntentJson = IntentCodec.encode(resolution(), clock.instant())))
        sync(emptyList(), store).run()
        sync(emptyList(), store).run()
        val intents = requests.filter { it.first.endsWith("/app/intent") }
        assertEquals(1, intents.size)
        val body = json.parseToJsonElement(intents[0].second).jsonObject
        assertEquals("BOOST", body["source"]!!.jsonPrimitive.content)
        assertEquals(store.state.installId, body["install_id"]!!.jsonPrimitive.content)
    }

    @Test fun intentCodecRoundTrip() {
        val at = Instant.parse("2026-10-18T12:00:00Z")
        val decoded = IntentCodec.decode(IntentCodec.encode(resolution(), at))!!
        assertEquals("BOOST", decoded.source)
        assertEquals(60, decoded.desired.targetTemp)
        assertEquals(false, decoded.desired.eco)
        assertEquals("2026-10-18T14:00:00Z", decoded.nextTransitionAt)
        assertEquals("2026-10-18T12:00:00Z", decoded.at)
        assertTrue(IntentCodec.sameIntent(IntentCodec.encode(resolution(), at), IntentCodec.encode(resolution(), at.plusSeconds(60))))
        assertTrue(!IntentCodec.sameIntent(IntentCodec.encode(resolution(), at),
            IntentCodec.encode(resolution(StateSource.WEEKLY_PLAN), at)))
        assertNull(IntentCodec.decode("not json"))
    }
}
