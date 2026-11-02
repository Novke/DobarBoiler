package rs.novica.dobarboiler.api

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ReleaseClientTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File

    @Before fun setUp() { server = MockWebServer(); server.start(); dir = Files.createTempDirectory("rel").toFile() }
    @After fun tearDown() { server.shutdown(); dir.deleteRecursively() }

    private fun client() = ReleaseClient(server.url("/boiler-apk/").toString())

    private val latestJson = """
        {"version":"0.2.0","versionName":"0.2.0","versionCode":440000,"file":"dobarboiler-0.2.0.apk",
         "sha256":"SUM","size":5,"date":"02.11.2026.","test":false,"commit":"abc",
         "changelog":[{"pr":12,"text":"Ažuriranje iz aplikacije"}]}
    """.trimIndent()

    @Test fun readsLatestAndComparesVersionCode() = runTest {
        server.enqueue(MockResponse().setBody(latestJson))
        val latest = client().latest()!!
        assertEquals("0.2.0", latest.version)
        assertEquals(listOf(ChangeDto(12, "Ažuriranje iz aplikacije")), latest.changelog)
        assertTrue(latest.isNewerThan(434414))
        assertFalse(latest.isNewerThan(440000))
        assertEquals("/boiler-apk/latest.json", server.takeRequest().path)
    }

    @Test fun nothingPublishedIsNull() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client().latest())
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(emptyList<ReleaseDto>(), client().versions())
    }

    @Test fun serverErrorThrows() = runTest {
        server.enqueue(MockResponse().setResponseCode(502))
        try { client().latest(); fail("expected ReleaseException") } catch (_: ReleaseException) { }
    }

    @Test fun downloadVerifiesSha256() = runTest {
        val bytes = "hello".toByteArray()
        val sum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val release = ReleaseDto(version = "0.2.0", versionCode = 2, file = "a.apk", sha256 = sum, size = 5)
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val dest = File(dir, "a.apk")
        var progress = 0f
        client().download(release, dest) { progress = it }
        assertEquals("hello", dest.readText())
        assertEquals(1f, progress)
        assertEquals("/boiler-apk/a.apk", server.takeRequest().path)
    }

    @Test fun downloadWithWrongSumLeavesNothing() = runTest {
        val release = ReleaseDto(version = "0.2.0", versionCode = 2, file = "a.apk", sha256 = "00".repeat(32))
        server.enqueue(MockResponse().setBody("tampered"))
        val dest = File(dir, "a.apk")
        try { client().download(release, dest); fail("expected ReleaseException") } catch (_: ReleaseException) { }
        assertFalse(dest.exists())
        assertFalse(File(dir, "a.apk.part").exists())
    }
}
