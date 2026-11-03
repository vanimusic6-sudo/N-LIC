package io.github.romanvht.byedpi.utility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TelegramProxyLinkBuilderTest {
    private val secret = "0123456789abcdef0123456789abcdef"

    @Test fun `valid remote MTProxy links open in Telegram`() {
        assertEquals(
            "tg://proxy?server=mt.example.net&port=443&secret=$secret",
            TelegramProxyLinkBuilder.build("mt.example.net", 443, secret)
        )
    }

    @Test fun `never allow query injection or malformed host`() {
        assertThrows(IllegalArgumentException::class.java) {
            TelegramProxyLinkBuilder.build("localhost&secret=evil", 443, secret)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TelegramProxyLinkBuilder.build("mt.example.net", 0, secret)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TelegramProxyLinkBuilder.build("mt.example.net", 443, "bad")
        }
    }
}
