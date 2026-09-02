package com.lightcommunity.nfcreader

import kotlin.test.Test
import kotlin.test.assertEquals

class TagActionTest {

    private fun action(
        type: ActionType,
        url: String? = null,
        method: String? = null,
        note: String? = null,
        dial: String? = null,
    ) = TagAction(
        serialNumber = "04A3B2C1",
        label = "Tag",
        actionType = type,
        webhookUrl = url,
        webhookMethod = method,
        webhookHeaders = null,
        webhookBody = null,
        skipSsl = false,
        noteText = note,
        dialNumber = dial,
        createdAt = 0L,
    )

    @Test
    fun `webhook summary shows method and url`() {
        assertEquals(
            "POST https://example.com/hook",
            action(ActionType.WEBHOOK, url = "https://example.com/hook", method = "post").summary(),
        )
    }

    @Test
    fun `note summary is truncated to 50 chars`() {
        val note = "x".repeat(80)
        assertEquals(50, action(ActionType.NOTE, note = note).summary().length)
    }

    @Test
    fun `dial summary uses the number or notes it comes from the tag`() {
        assertEquals("+15551234567", action(ActionType.DIAL, dial = "+15551234567").summary())
        assertEquals("Number from tag", action(ActionType.DIAL, dial = null).summary())
        assertEquals("Number from tag", action(ActionType.DIAL, dial = "  ").summary())
    }

    @Test
    fun `parseHeaders reads key-value lines and keeps colons in values`() {
        val parsed = ActionExecutor.parseHeaders(
            """
            Authorization: Bearer abc123
            X-Scheduled-At: 12:30:00
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "Authorization" to "Bearer abc123",
                "X-Scheduled-At" to "12:30:00",
            ),
            parsed,
        )
    }

    @Test
    fun `parseHeaders ignores blank and malformed lines`() {
        assertEquals(emptyList(), ActionExecutor.parseHeaders(null))
        assertEquals(emptyList(), ActionExecutor.parseHeaders("   "))
        assertEquals(
            listOf("A" to "b"),
            ActionExecutor.parseHeaders("no-colon-here\n\nA: b\n: missing-key"),
        )
    }
}
