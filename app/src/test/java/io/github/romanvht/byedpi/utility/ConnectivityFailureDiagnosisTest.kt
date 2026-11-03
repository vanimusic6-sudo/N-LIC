package io.github.romanvht.byedpi.utility

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityFailureDiagnosisTest {
    @Test fun `report DNS failures without mistaking them for DPI evidence`() {
        assertEquals("DNS_FAILURE", ConnectivityFailureDiagnosis.classify(
            UnknownHostException("no address associated with hostname")))
        assertEquals("SOCKET_ACCESS_DENIED", ConnectivityFailureDiagnosis.classify(
            SocketException("socket failed: EPERM (Operation not permitted)")))
        assertEquals("NO_NETWORK_ROUTE", ConnectivityFailureDiagnosis.classify(
            SocketException("ENETUNREACH (Network is unreachable)")))
        assertEquals("NO_NETWORK_ROUTE", ConnectivityFailureDiagnosis.classify(
            IOException("No route to host")))
        assertEquals("TIMEOUT", ConnectivityFailureDiagnosis.classify(
            SocketTimeoutException("timed out")))
    }

    @Test fun `all immediate failures should ask user to verify app-level internet`() {
        val checks = listOf(
            ConnectionCheck("YouTube TCP", false, 2, "DNS_FAILURE"),
            ConnectionCheck("Telegram DC", false, 0, "NO_NETWORK_ROUTE"),
            ConnectionCheck("Control IPv4 TCP", false, 0, "SOCKET_ACCESS_DENIED"),
            ConnectionCheck("Control IPv6 TCP", false, 0, "NO_NETWORK_ROUTE"),
            ConnectionCheck("Cloudflare small", false, 14, "NETWORK_IO_FAILURE"),
            ConnectionCheck("Cloudflare large", false, 0, "NETWORK_IO_FAILURE"),
        )
        val notes = ConnectivityFailureDiagnosis.describe(checks, false, null, true, true)
        assertTrue(notes.any { it.contains("числовым IP") })
        assertTrue(notes.any { it.contains("немедленно") })
        assertTrue(notes.any { it.contains("браузере") })
    }

    @Test fun `working baseline must not produce false connectivity-alarm`() {
        val checks = listOf(
            ConnectionCheck("YouTube", true, 40, "TCP_OK_IPV4"),
            ConnectionCheck("Telegram", false, 2200, "TIMEOUT"),
            ConnectionCheck("Control IPv4 TCP", true, 20, "TCP_OK_IPV4"),
            ConnectionCheck("Control IPv6 TCP", false, 100, "NO_NETWORK_ROUTE"),
            ConnectionCheck("Cloudflare small", true, 90, "BODY_COMPLETE"),
            ConnectionCheck("Cloudflare large", true, 250, "BODY_COMPLETE"),
        )
        val notes = ConnectivityFailureDiagnosis.describe(checks, true, false, true, true)
        assertFalse(notes.any { it.contains("немедленно") })
        assertFalse(notes.any { it.contains("Контрольные TCP-соединения") })
    }
}
