package com.lightcommunity.nfcreader

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanFormattingTest {

    @Test
    fun `formatSerial groups hex into colon-separated pairs`() {
        assertEquals("04:A3:B2:C1", ScanFormatting.formatSerial("04A3B2C1"))
        assertEquals("04:A3:B2", ScanFormatting.formatSerial("04A3B2"))
        assertEquals("AB:C", ScanFormatting.formatSerial("ABC"))
        assertEquals("", ScanFormatting.formatSerial(""))
    }

    private val now = Instant.parse("2026-09-01T12:00:00Z")
    private fun ago(seconds: Long) = now.minusSeconds(seconds).toEpochMilli()

    @Test
    fun `relativeTimestamp reports just now under a minute`() {
        assertEquals("Just now", ScanFormatting.relativeTimestamp(ago(5), now))
        assertEquals("Just now", ScanFormatting.relativeTimestamp(ago(59), now))
    }

    @Test
    fun `relativeTimestamp reports minutes under an hour`() {
        assertEquals("1m ago", ScanFormatting.relativeTimestamp(ago(60), now))
        assertEquals("59m ago", ScanFormatting.relativeTimestamp(ago(59 * 60), now))
    }

    @Test
    fun `relativeTimestamp reports today and yesterday within two days`() {
        assertTrue(ScanFormatting.relativeTimestamp(ago(3 * 3600), now).startsWith("Today, "))
        assertTrue(ScanFormatting.relativeTimestamp(ago(30 * 3600), now).startsWith("Yesterday, "))
    }

    @Test
    fun `relativeTimestamp falls back to an absolute date past two days`() {
        val result = ScanFormatting.relativeTimestamp(ago(5 * 86400), now)
        assertTrue(Regex("""[A-Z][a-z]{2} \d{1,2}, \d{4}""").matches(result), "was: $result")
    }
}
