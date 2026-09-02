package com.lightcommunity.nfcreader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanModelTest {

    private fun scan(
        uri: String? = null,
        text: String? = null,
        textLanguage: String? = null,
        binary: Int = 0,
        total: Int = 0,
    ) = Scan(
        id = 1,
        serialNumber = "04A3B2C1",
        uri = uri,
        text = text,
        textLanguage = textLanguage,
        binaryRecordCount = binary,
        totalRecordCount = total,
        timestampMs = 0L,
    )

    @Test
    fun `contact scan exposes name phone and email`() {
        val s = scan(
            uri = "tel:+15551234567",
            text = "Jane Doe\njane@example.com",
            textLanguage = VCARD_MARKER,
            total = 2,
        )
        assertTrue(s.isContact)
        assertEquals("+15551234567", s.contactPhone)
        assertEquals("jane@example.com", s.contactEmail)
        assertEquals("Jane Doe", s.contactDisplayName)
        assertEquals("Jane Doe", s.preview())
        assertEquals("Contact", s.typeLabel())
    }

    @Test
    fun `contact without an email has none`() {
        val s = scan(uri = "tel:5551234", text = "Solo", textLanguage = VCARD_MARKER)
        assertNull(s.contactEmail)
        assertEquals("5551234", s.contactPhone)
    }

    @Test
    fun `contact phone is null when the uri is not a tel link`() {
        val s = scan(uri = "https://example.com", text = "Web Person", textLanguage = VCARD_MARKER)
        assertNull(s.contactPhone)
    }

    @Test
    fun `non-contact scans are typed by their richest record`() {
        assertFalse(scan(uri = "https://x").isContact)
        assertEquals("URI", scan(uri = "https://x").typeLabel())
        assertEquals("https://x", scan(uri = "https://x").preview())
        assertEquals("Text", scan(text = "hello").typeLabel())
        assertEquals("Binary", scan(binary = 2, total = 2).typeLabel())
        assertEquals("Empty tag", scan(total = 0).typeLabel())
        assertEquals("04A3B2C1", scan(total = 0).preview())
    }
}
