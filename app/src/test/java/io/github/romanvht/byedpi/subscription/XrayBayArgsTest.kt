package io.github.romanvht.byedpi.subscription

import org.junit.Assert.*
import org.junit.Test

class XrayBayArgsTest {
    @Test fun aSingleLocalRelayIsAccepted() {
        val args = listOf("--ip", "127.0.0.1", "--port", "11088", "-s0+sm")
        assertTrue(XrayBayArgs.validLoopback(args, XrayConfigCompiler.BAY_PORT))
    }

    @Test fun externallyAccessibleOrAmbiguousRelayIsRejected() {
        val valid = listOf("--ip", "127.0.0.1", "--port", "11088", "-s0+sm")
        assertFalse(XrayBayArgs.validLoopback(
            listOf("--ip", "0.0.0.0", "--port", "11088"), 11088))
        assertFalse(XrayBayArgs.validLoopback(valid + listOf("-i0.0.0.0"), 11088))
        assertFalse(XrayBayArgs.validLoopback(valid + listOf("--port", "1080"), 11088))
        assertFalse(XrayBayArgs.validLoopback(valid + "--transparent", 11088))
        assertFalse(XrayBayArgs.validLoopback(valid + "--no-udp", 11088))
        assertFalse(XrayBayArgs.validLoopback(valid + "--", 11088))
        assertFalse(XrayBayArgs.validLoopback(valid, 11089))
        assertFalse(XrayBayArgs.validLoopback(emptyList(), 11088))
    }
}
