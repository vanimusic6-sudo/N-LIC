package io.github.romanvht.byedpi.utility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamProbeClassifierTest {
    private fun verdict(
        status: Int = 206,
        length: Long = StreamProbeClassifier.SAMPLE_BYTES.toLong(),
        read: Int = StreamProbeClassifier.SAMPLE_BYTES,
        timeout: Boolean = false,
        eof: Boolean = false,
        time: Long = 600,
        contentType: String = "video/mp4",
    ) = StreamProbeClassifier.classify(status, contentType, length, read, timeout, eof, time)

    @Test fun `short transfer can succeed for thumbnails yet fail for video`() {
        assertEquals(StreamOutcome.COMPLETE, verdict())
        assertEquals(StreamOutcome.SLOW_TRANSFER, verdict(time = 10_000))
        assertEquals(StreamOutcome.STALLED_AFTER_DATA, verdict(read = 20_480, timeout = true))
        assertEquals(StreamOutcome.PREMATURE_EOF, verdict(read = 16_384, eof = true))
        assertEquals(StreamOutcome.SHORT_OBJECT, verdict(length = 16_384, read = 16_384, eof = true))
    }

    @Test fun `expired signed media and HTML do not qualify as successful video`() {
        assertEquals(StreamOutcome.HTTP_REJECTED, verdict(status = 403))
        assertEquals(StreamOutcome.REDIRECTED, verdict(status = 302))
        assertEquals(StreamOutcome.NOT_MEDIA, verdict(contentType = "text/html;charset=utf-8"))
        assertEquals(StreamOutcome.TIMEOUT_BEFORE_DATA, verdict(read = 0, timeout = true))
        assertEquals(StreamOutcome.EMPTY_RESPONSE, verdict(read = 0, eof = true))
    }

    @Test fun `only approved encrypted media targets can be probed`() {
        assertTrue(StreamProbeClassifier.isAllowedTarget("https", "RR1---A.googlevideo.com", -1, "/videoplayback"))
        assertTrue(StreamProbeClassifier.isAllowedTarget("https", "speed.cloudflare.com", 443, "/__down"))
        assertFalse(StreamProbeClassifier.isAllowedTarget("http", "speed.cloudflare.com", -1, "/__down"))
        assertFalse(StreamProbeClassifier.isAllowedTarget("https", "googlevideo.com.evil.invalid", -1, "/videoplayback"))
        assertFalse(StreamProbeClassifier.isAllowedTarget("https", "127.0.0.1", -1, "/videoplayback"))
        assertFalse(StreamProbeClassifier.isAllowedTarget("https", "speed.cloudflare.com", -1, "/admin"))
    }
}
