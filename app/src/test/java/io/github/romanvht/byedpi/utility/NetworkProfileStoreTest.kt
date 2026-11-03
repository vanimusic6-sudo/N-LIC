package io.github.romanvht.byedpi.utility

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkProfileStoreTest {
    @Test fun `cellular and WiFi use separate opt-in command slots`() {
        val wifi = "-r3+s"
        val mobile = "-Kt -r3+s -T2 -At -Kt -o1 -r-5+se"
        val entries = mapOf("nlic_wifi_command" to wifi, "nlic_cellular_command" to mobile)
        assertEquals(wifi, NetworkProfileStore.choose(true, AccessType.WIFI, "-An", entries::get))
        assertEquals(mobile, NetworkProfileStore.choose(true, AccessType.CELLULAR, "-An", entries::get))
        assertEquals("-An", NetworkProfileStore.choose(false, AccessType.CELLULAR, "-An", entries::get))
    }

    @Test fun `never substitute unknown network or malformed saved profile`() {
        assertEquals("-An", NetworkProfileStore.choose(true, AccessType.UNKNOWN, "-An") { "-f-1" })
        assertEquals("-An", NetworkProfileStore.choose(true, AccessType.CELLULAR, "-An") { "broken" })
        assertEquals("-An", NetworkProfileStore.choose(true, AccessType.CELLULAR, "-An") { "-r3+s\n--ip 0.0.0.0" })
    }
}
