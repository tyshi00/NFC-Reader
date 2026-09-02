package com.lightcommunity.nfcreader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VCardParsingTest {

    @Test
    fun `parses FN TEL and EMAIL`() {
        val raw = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Jane Doe
            TEL;TYPE=CELL:+1-555-123-4567
            EMAIL;TYPE=HOME:jane@example.com
            END:VCARD
        """.trimIndent()

        val c = parseVCard(raw)
        assertEquals("Jane Doe", c.name)
        assertEquals("+1-555-123-4567", c.phone)
        assertEquals("jane@example.com", c.email)
    }

    @Test
    fun `handles charset params on FN`() {
        assertEquals("Sora", parseVCard("FN;CHARSET=UTF-8:Sora").name)
    }

    @Test
    fun `falls back to N field when FN is absent`() {
        assertEquals("Jane Doe", parseVCard("N:Doe;Jane;;;").name)
    }

    @Test
    fun `does not mistake NOTE or NICKNAME for the N field`() {
        val c = parseVCard(
            """
            NOTE:call me later
            NICKNAME:JD
            TEL:5551234
            """.trimIndent(),
        )
        assertNull(c.name)
        assertEquals("5551234", c.phone)
    }

    @Test
    fun `keeps the first TEL and EMAIL when several are present`() {
        val c = parseVCard(
            """
            FN:Multi
            TEL;TYPE=HOME:111
            TEL;TYPE=WORK:222
            EMAIL:a@x.com
            EMAIL:b@x.com
            """.trimIndent(),
        )
        assertEquals("111", c.phone)
        assertEquals("a@x.com", c.email)
    }

    @Test
    fun `returns nulls for an empty card`() {
        val c = parseVCard("BEGIN:VCARD\nEND:VCARD")
        assertNull(c.name)
        assertNull(c.phone)
        assertNull(c.email)
    }
}
