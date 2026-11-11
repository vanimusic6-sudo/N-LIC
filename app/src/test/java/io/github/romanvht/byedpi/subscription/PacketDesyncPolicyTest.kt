package io.github.romanvht.byedpi.subscription

import org.junit.Assert.*
import org.junit.Test

class PacketDesyncPolicyTest {
    @Test fun packetFakeIsNotTlsRecordFragmentation() {
        val cmd = PacketDesyncPolicy.command(PacketDesyncPolicy.FAKE_TTL, true)!!
        assertTrue(cmd.contains("--fake -1"))
        assertTrue(cmd.contains("--ttl 8"))
        assertTrue(cmd.contains("--fake-tls-mod rand"))
        assertFalse(cmd.contains("--tlsrec"))
        assertFalse(cmd.contains("--split"))
        assertNull(PacketDesyncPolicy.command(PacketDesyncPolicy.FAKE_TTL, false))
    }

    @Test fun requiresExplicitBayAndRawReality() {
        assertTrue(PacketDesyncPolicy.shouldUse(true, true, true))
        assertFalse(PacketDesyncPolicy.shouldUse(false, true, true))
        assertFalse(PacketDesyncPolicy.shouldUse(true, false, true))
        assertFalse(PacketDesyncPolicy.shouldUse(true, true, false))
    }

    @Test fun onlyKnownBoundedModes() {
        assertNotNull(PacketDesyncPolicy.command(PacketDesyncPolicy.DISOOB, true))
        assertNull(PacketDesyncPolicy.command(PacketDesyncPolicy.LEGACY, true))
        assertNull(PacketDesyncPolicy.command("unknown", true))
        val args = listOf("--ip", "127.0.0.1", "--port", "11088",
            "--fake", "-1", "--ttl", "8", "--proto=tls")
        assertTrue(XrayBayArgs.validLoopback(args, 11088))
        assertFalse(XrayBayArgs.validLoopback(args.drop(2), 11088))
    }
}
