package com.jett.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCryptoTest {
    private val key = ByteArray(32) { 7 }

    // Sealed by the phone app's code path (WebCrypto AES-GCM + raw deflate, Node 20).
    private val fromPhone = "v1.AwMDAwMDAwMDAwMDjqhpTwuaDAg2CnVo2QEtXcdtUt1gxF4lnYz1xu60kumb989wSbSJiBqnZUyhh7KXdm5BdttIBcseDwn83_0Uob1WO-peAm3iCSETJl3NYABhLF4"

    @Test fun opensWhatThePhoneSeals() {
        assertEquals("""{"id":"abc123","t":1,"m":"GET","p":"/api/state","b":"héllo ✓"}""", RelayCrypto.open(key, fromPhone))
    }

    @Test fun roundTripAndPrintVector() {
        val s = RelayCrypto.seal(key, "box → phone ✓ " + "x".repeat(5000))
        assertEquals("box → phone ✓ " + "x".repeat(5000), RelayCrypto.open(key, s))
        java.io.File(System.getProperty("java.io.tmpdir"), "jett-relay-vector.txt").writeText(RelayCrypto.seal(key, """{"id":"abc123","s":200,"b":"ok ✓"}"""))
    }

    @Test fun rejectsWrongKeyTamperAndJunk() {
        val s = RelayCrypto.seal(key, "hi")
        assertNull(RelayCrypto.open(ByteArray(32) { 8 }, s))
        val flipped = s.substring(0, s.length - 2) + (if (s[s.length - 2] == 'A') 'B' else 'A') + s.last()
        assertNull(RelayCrypto.open(key, flipped))
        assertNull(RelayCrypto.open(key, "hello")); assertNull(RelayCrypto.open(key, "v1.!!!"))
    }

    @Test fun piecesFitOneMessage() {
        val big = RelayCrypto.seal(key, (1..4000).joinToString { "photo$it" })
        val p = RelayCrypto.pieces("abc123", big)
        assertTrue(p.size > 1); assertTrue(p.all { it.length <= 4096 })
        assertEquals(big, p.joinToString("") { it.split('.', limit = 5)[4] })
        assertTrue(RelayCrypto.newTopic().matches(Regex("jett-[a-z2-9]{24}")))
    }
}
