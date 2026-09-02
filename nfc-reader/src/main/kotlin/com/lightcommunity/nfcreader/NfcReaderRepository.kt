package com.lightcommunity.nfcreader

import com.thelightphone.sdk.nfc.LightNfcRecord
import com.thelightphone.sdk.nfc.LightNfcTap
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// ── Domain model ─────────────────────────────────────────────────────────

data class Scan(
    val id: Long,
    val serialNumber: String,
    val uri: String?,
    val text: String?,
    val textLanguage: String?,
    val binaryRecordCount: Int,
    val totalRecordCount: Int,
    val timestampMs: Long,
) {
    /** True when this scan came from a vCard NFC tag (contact card). */
    val isContact: Boolean get() = textLanguage == VCARD_MARKER

    /** Contact name, if this is a vCard scan. */
    val contactName: String? get() = if (isContact) text else null

    /** Phone number, if this is a vCard scan with a tel: URI. */
    val contactPhone: String?
        get() = if (isContact && uri?.startsWith("tel:") == true) {
            uri.removePrefix("tel:")
        } else null

    /** Email address, stored after a newline in the text field for vCard scans. */
    val contactEmail: String?
        get() {
            if (!isContact || text == null) return null
            val parts = text.split("\n", limit = 2)
            return parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        }

    /** Display name for the contact, falling back to phone or serial. */
    val contactDisplayName: String?
        get() {
            if (!isContact) return null
            val name = text?.split("\n")?.firstOrNull()
            return name?.takeIf { it.isNotBlank() }
        }

    /** Short preview for list rows. */
    fun preview(): String = when {
        isContact -> contactDisplayName ?: contactPhone ?: serialNumber
        uri != null -> uri
        text != null -> text
        else -> serialNumber
    }

    /** Human-readable type label. */
    fun typeLabel(): String = when {
        isContact -> "Contact"
        uri != null -> "URI"
        text != null -> "Text"
        binaryRecordCount > 0 -> "Binary"
        totalRecordCount == 0 -> "Empty tag"
        else -> "Tag"
    }
}

private fun ScanEntity.toDomain() = Scan(
    id = id,
    serialNumber = serialNumber,
    uri = uri,
    text = text,
    textLanguage = textLanguage,
    binaryRecordCount = binaryRecordCount,
    totalRecordCount = totalRecordCount,
    timestampMs = timestampMs,
)

// ── vCard parsing ────────────────────────────────────────────────────────

/** Marker stored in textLanguage to flag a scan as a parsed vCard. */
internal const val VCARD_MARKER = "vcard"

private val VCARD_MIME_TYPES = setOf("text/vcard", "text/x-vcard")

/** Minimal vCard parser: extracts FN (display name), TEL, and EMAIL. */
internal data class ParsedContact(
    val name: String?,
    val phone: String?,
    val email: String?,
)

internal fun parseVCard(raw: String): ParsedContact {
    var name: String? = null
    var phone: String? = null
    var email: String? = null

    for (line in raw.lineSequence()) {
        val trimmed = line.trim()
        when {
            // FN:Sora  or  FN;CHARSET=UTF-8:Sora
            trimmed.startsWith("FN", ignoreCase = true) -> {
                name = trimmed.substringAfter(":").trim().takeIf { it.isNotBlank() }
            }
            // TEL;TYPE=HOME:1-231-238-888  or  TEL:+1234567890
            trimmed.startsWith("TEL", ignoreCase = true) -> {
                if (phone == null) {
                    phone = trimmed.substringAfter(":").trim().takeIf { it.isNotBlank() }
                }
            }
            // EMAIL;TYPE=OTHER:user@example.com  or  EMAIL:user@example.com
            trimmed.startsWith("EMAIL", ignoreCase = true) -> {
                if (email == null) {
                    email = trimmed.substringAfter(":").trim().takeIf { it.isNotBlank() }
                }
            }
        }
    }

    // Fallback: if FN is missing, try N field  (N:Last;First;;; → "First Last")
    if (name == null) {
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("N", ignoreCase = true) && trimmed.contains(":")) {
                val tag = trimmed.substringBefore(":")
                // Must be exactly "N" or "N;..." — not "NOTE" etc.
                if (tag.equals("N", ignoreCase = true) || tag.startsWith("N;", ignoreCase = true)) {
                    val parts = trimmed.substringAfter(":").split(";")
                    val last = parts.getOrNull(0)?.trim().orEmpty()
                    val first = parts.getOrNull(1)?.trim().orEmpty()
                    name = "$first $last".trim().takeIf { it.isNotBlank() }
                    break
                }
            }
        }
    }

    return ParsedContact(name = name, phone = phone, email = email)
}

// ── Formatting ───────────────────────────────────────────────────────────

object ScanFormatting {

    private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val fullFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy  h:mm a", Locale.US)

    fun relativeTimestamp(timestampMs: Long, now: Instant = Instant.now()): String {
        val then = Instant.ofEpochMilli(timestampMs)
        val elapsedSeconds = now.epochSecond - then.epochSecond

        return when {
            elapsedSeconds < 60 -> "Just now"
            elapsedSeconds < 3600 -> "${elapsedSeconds / 60}m ago"
            elapsedSeconds < 86400 -> {
                val zoned = ZonedDateTime.ofInstant(then, ZoneId.systemDefault())
                "Today, ${zoned.format(timeFormatter)}"
            }
            elapsedSeconds < 172800 -> {
                val zoned = ZonedDateTime.ofInstant(then, ZoneId.systemDefault())
                "Yesterday, ${zoned.format(timeFormatter)}"
            }
            else -> {
                val zoned = ZonedDateTime.ofInstant(then, ZoneId.systemDefault())
                zoned.format(dateFormatter)
            }
        }
    }

    fun fullTimestamp(timestampMs: Long): String {
        val zoned = ZonedDateTime.ofInstant(
            Instant.ofEpochMilli(timestampMs),
            ZoneId.systemDefault(),
        )
        return zoned.format(fullFormatter)
    }

    /** Formats serial number with colon separators: "04A3B2C1" → "04:A3:B2:C1". */
    fun formatSerial(serial: String): String =
        serial.chunked(2).joinToString(":")
}

// ── Repository ───────────────────────────────────────────────────────────

class NfcReaderRepository(private val db: NfcReaderDatabase) {

    companion object {
        private const val PREF_INVERT_COLORS = "invert_colors"

        @Volatile private var INSTANCE: NfcReaderRepository? = null

        fun getInstance(factory: () -> NfcReaderDatabase): NfcReaderRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NfcReaderRepository(factory()).also { INSTANCE = it }
            }
    }

    // ── Scans ───────────────────────────────────────────────────────────

    suspend fun getAllScans(): List<Scan> =
        db.scanDao().getAll().map { it.toDomain() }

    suspend fun getScan(id: Long): Scan? =
        db.scanDao().getById(id)?.toDomain()

    suspend fun saveTap(tap: LightNfcTap): Long {
        val binaryRecords = tap.records.filterIsInstance<LightNfcRecord.Binary>()
        val binaryCount = binaryRecords.size
        val textRecord = tap.records.filterIsInstance<LightNfcRecord.Text>().firstOrNull()

        // Check for vCard contact in binary records
        val vcardRecord = binaryRecords.firstOrNull { record ->
            record.mimeType?.lowercase() in VCARD_MIME_TYPES
        }

        return if (vcardRecord != null) {
            val raw = String(vcardRecord.bytes, Charsets.UTF_8)
            val contact = parseVCard(raw)
            // Store contact data in existing schema fields:
            // - text: "Name\nEmail" (name on first line, email on second)
            // - uri: "tel:PhoneNumber" if phone exists
            // - textLanguage: "vcard" marker
            val textValue = buildString {
                append(contact.name ?: "Unknown")
                if (contact.email != null) {
                    append("\n")
                    append(contact.email)
                }
            }
            db.scanDao().insert(
                ScanEntity(
                    serialNumber = tap.serialNumber,
                    uri = contact.phone?.let { "tel:$it" } ?: tap.uri,
                    text = textValue,
                    textLanguage = VCARD_MARKER,
                    binaryRecordCount = binaryCount,
                    totalRecordCount = tap.records.size,
                    timestampMs = System.currentTimeMillis(),
                ),
            )
        } else {
            db.scanDao().insert(
                ScanEntity(
                    serialNumber = tap.serialNumber,
                    uri = tap.uri,
                    text = tap.text,
                    textLanguage = textRecord?.languageTag,
                    binaryRecordCount = binaryCount,
                    totalRecordCount = tap.records.size,
                    timestampMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun deleteScan(id: Long) {
        db.scanDao().deleteById(id)
    }

    suspend fun deleteAllScans() {
        db.scanDao().deleteAll()
    }

    suspend fun scanCount(): Int =
        db.scanDao().count()

    // ── Preferences ─────────────────────────────────────────────────────

    suspend fun getInvertColors(): Boolean =
        db.preferenceDao().get(PREF_INVERT_COLORS)?.value == "true"

    suspend fun setInvertColors(value: Boolean) {
        db.preferenceDao().set(PreferenceEntity(PREF_INVERT_COLORS, value.toString()))
    }
}
