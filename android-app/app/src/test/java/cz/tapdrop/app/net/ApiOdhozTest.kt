package cz.tapdrop.app.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiOdhozTest {
    @Test
    fun encodeAndDecodeEmptyOdhozText() {
        val json = OdhozPayload.encode("")
        assertEquals("", OdhozPayload.decode(json))
    }

    @Test
    fun encodeAndDecodeUtf8OdhozText() {
        val json = OdhozPayload.encode("Přepravka šedá")
        assertEquals("Přepravka šedá", OdhozPayload.decode(json))
    }
}
