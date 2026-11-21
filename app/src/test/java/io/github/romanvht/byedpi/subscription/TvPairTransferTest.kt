package io.github.romanvht.byedpi.subscription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

class TvPairTransferTest {
    @Test fun onlyPrivateIpv4MayBeUsedAsDestination() {
        assertTrue(TvPairTransfer.privateIpv4("192.168.1.50"))
        assertTrue(TvPairTransfer.privateIpv4("10.0.0.22"))
        assertTrue(TvPairTransfer.privateIpv4("172.20.0.5"))
        assertFalse(TvPairTransfer.privateIpv4("8.8.8.8"))
        assertFalse(TvPairTransfer.privateIpv4("127.0.0.1"))
        assertFalse(TvPairTransfer.privateIpv4("169.254.1.5"))
        assertFalse(TvPairTransfer.privateIpv4("172.32.1.3"))
        assertFalse(TvPairTransfer.privateIpv4("192.168.1.999"))
        assertFalse(TvPairTransfer.privateIpv4("192.168.1.2.evil.org"))
        assertFalse(TvPairTransfer.privateIpv4("192.168.1.-2"))
    }

    @Test fun ciphertextRoundTripsWithoutLoggingOrPlaintextWireFormat() {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val payload = "vless://secret-credential@example.net:443".toByteArray()
        val ciphertext = TvPairTransfer.encrypt(secret, iv, payload)
        assertFalse(ciphertext.contentEquals(payload))
        assertArrayEquals(payload, TvPairTransfer.decrypt(secret, iv, ciphertext))
    }

    @Test(expected = AEADBadTagException::class)
    fun modifiedEncryptedTransferIsRejected() {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val data = TvPairTransfer.encrypt(secret, iv, "hello".toByteArray())
        data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
        TvPairTransfer.decrypt(secret, iv, data)
    }

    @Test(expected = AEADBadTagException::class)
    fun transferWithWrongPairingKeyIsRejected() {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrong = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val data = TvPairTransfer.encrypt(secret, iv, "test".toByteArray())
        TvPairTransfer.decrypt(wrong, iv, data)
    }
}
