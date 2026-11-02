package rs.novica.dobarboiler.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryServerDefaultsTest {

    @Test
    fun `stored address and token win over the built-in ones`() {
        assertEquals("http://x/", HistoryServerDefaults.url(stored = "http://x/", builtInToken = "b"))
        assertEquals("mine", HistoryServerDefaults.token(stored = "mine", builtInToken = "b"))
    }

    @Test
    fun `an APK built with a token is configured out of the box`() {
        assertEquals(HistoryServerDefaults.URL, HistoryServerDefaults.url(stored = null, builtInToken = "b"))
        assertEquals("b", HistoryServerDefaults.token(stored = null, builtInToken = "b"))
    }

    @Test
    fun `an APK built without a token stays unconfigured`() {
        assertNull(HistoryServerDefaults.url(stored = null, builtInToken = ""))
        assertNull(HistoryServerDefaults.token(stored = null, builtInToken = " "))
    }
}
