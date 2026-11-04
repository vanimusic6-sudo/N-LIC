package io.github.romanvht.byedpi.utility

import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkHealthInterpretationTest {
    private fun r(name: String, ok: Boolean, ms: Long = 30L) =
        ConnectionCheck(name, ok, ms, if (ok) "OK" else "TIMEOUT")

    @Test fun `distinguish unreachable Telegram IP from successful YouTube TLS`() {
        val notes = NetworkHealthInterpretation.describe(
            r("youtube", true), r("telegram", false),
            r("small", true), r("large", true),
        )
        assertTrue(notes.any { it.contains("Telegram DC") })
    }

    @Test fun `short HTTP success cannot prove big video body works`() {
        val notes = NetworkHealthInterpretation.describe(
            r("youtube", true), r("telegram", true),
            r("small", true), r("large", false),
        )
        assertTrue(notes.any { it.contains("большой не завершился") })
    }

    @Test fun `both failed must not be described as proven Telegram IP filtering`() {
        val notes = NetworkHealthInterpretation.describe(
            r("youtube", false), r("telegram", false),
            r("small", false), r("large", false),
        )
        assertTrue(notes.none { it.contains("Telegram DC") })
        assertTrue(notes.any { it.contains("DNS и маршрутизацию") })
    }
}
