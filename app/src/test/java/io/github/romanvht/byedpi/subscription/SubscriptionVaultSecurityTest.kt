package io.github.romanvht.byedpi.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class SubscriptionVaultSecurityTest {
    @Test fun encryptedInputIsBoundedBeforeHeapAllocation() {
        val exactlyMin = ByteArray(29)
        assertEquals(29, SubscriptionVault.readBoundedCiphertext(
            ByteArrayInputStream(exactlyMin)).size)
        val exactlyMax = ByteArray(2 * 1024 * 1024)
        assertEquals(exactlyMax.size, SubscriptionVault.readBoundedCiphertext(
            ByteArrayInputStream(exactlyMax)).size)
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionVault.readBoundedCiphertext(
                ByteArrayInputStream(ByteArray(2 * 1024 * 1024 + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionVault.readBoundedCiphertext(
                ByteArrayInputStream(ByteArray(28)))
        }
    }
}
