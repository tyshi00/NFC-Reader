package com.lightcommunity.nfcreader

import android.util.Log
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.nfc.LightNfcTap
import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val TAG = "NfcActions"

// ── Action types ─────────────────────────────────────────────────────────

enum class ActionType(val label: String) {
    WEBHOOK("Webhook"),
    NOTE("Show note"),
    DIAL("Open dialer"),
}

// ── Entity ───────────────────────────────────────────────────────────────

@Entity(tableName = "tag_actions")
data class TagActionEntity(
    @PrimaryKey val serialNumber: String,
    val label: String,
    val actionType: String,
    val webhookUrl: String?,
    val webhookMethod: String?,
    val webhookHeaders: String?,
    val webhookBody: String?,
    val skipSsl: Boolean,
    val noteText: String?,
    val dialNumber: String?,
    val createdAt: Long,
)

// ── DAO ──────────────────────────────────────────────────────────────────

@Dao
interface TagActionDao {
    @Query("SELECT * FROM tag_actions ORDER BY createdAt DESC")
    suspend fun getAll(): List<TagActionEntity>

    @Query("SELECT * FROM tag_actions WHERE serialNumber = :serial LIMIT 1")
    suspend fun getBySerial(serial: String): TagActionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TagActionEntity)

    @Query("DELETE FROM tag_actions WHERE serialNumber = :serial")
    suspend fun deleteBySerial(serial: String)

    @Query("SELECT COUNT(*) FROM tag_actions")
    suspend fun count(): Int
}

// ── Database ─────────────────────────────────────────────────────────────

@Database(
    entities = [TagActionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TagActionDatabase : RoomDatabase() {
    abstract fun tagActionDao(): TagActionDao
}

// ── Domain model ─────────────────────────────────────────────────────────

data class TagAction(
    val serialNumber: String,
    val label: String,
    val actionType: ActionType,
    val webhookUrl: String?,
    val webhookMethod: String?,
    val webhookHeaders: String?,
    val webhookBody: String?,
    val skipSsl: Boolean,
    val noteText: String?,
    val dialNumber: String?,
    val createdAt: Long,
) {
    fun summary(): String = when (actionType) {
        ActionType.WEBHOOK -> webhookMethod?.uppercase().orEmpty() + " " + (webhookUrl ?: "")
        ActionType.NOTE -> noteText?.take(50) ?: ""
        ActionType.DIAL -> dialNumber?.takeIf { it.isNotBlank() } ?: "Number from tag"
    }
}

private fun TagActionEntity.toDomain() = TagAction(
    serialNumber = serialNumber,
    label = label,
    actionType = ActionType.entries.firstOrNull { it.name == actionType } ?: ActionType.NOTE,
    webhookUrl = webhookUrl,
    webhookMethod = webhookMethod,
    webhookHeaders = webhookHeaders,
    webhookBody = webhookBody,
    skipSsl = skipSsl,
    noteText = noteText,
    dialNumber = dialNumber,
    createdAt = createdAt,
)

// ── Repository ───────────────────────────────────────────────────────────

class TagActionRepository(private val db: TagActionDatabase) {

    companion object {
        @Volatile private var INSTANCE: TagActionRepository? = null

        fun getInstance(factory: () -> TagActionDatabase): TagActionRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TagActionRepository(factory()).also { INSTANCE = it }
            }
    }

    suspend fun getAll(): List<TagAction> =
        db.tagActionDao().getAll().map { it.toDomain() }

    suspend fun getBySerial(serial: String): TagAction? =
        db.tagActionDao().getBySerial(serial)?.toDomain()

    suspend fun save(action: TagAction) {
        db.tagActionDao().upsert(
            TagActionEntity(
                serialNumber = action.serialNumber,
                label = action.label,
                actionType = action.actionType.name,
                webhookUrl = action.webhookUrl,
                webhookMethod = action.webhookMethod,
                webhookHeaders = action.webhookHeaders,
                webhookBody = action.webhookBody,
                skipSsl = action.skipSsl,
                noteText = action.noteText,
                dialNumber = action.dialNumber,
                createdAt = action.createdAt,
            ),
        )
    }

    suspend fun delete(serial: String) {
        db.tagActionDao().deleteBySerial(serial)
    }

    suspend fun count(): Int =
        db.tagActionDao().count()
}

// ── Action executor ──────────────────────────────────────────────────────

sealed interface ActionResult {
    data class Success(val message: String) : ActionResult
    data class Error(val message: String) : ActionResult
}

object ActionExecutor {

    private val strictClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val trustAllClient: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun execute(action: TagAction, scan: Scan? = null): ActionResult = withContext(Dispatchers.IO) {
        when (action.actionType) {
            ActionType.WEBHOOK -> executeWebhook(action)
            ActionType.NOTE -> ActionResult.Success(action.noteText ?: "")
            ActionType.DIAL -> openDialer(action, scan)
        }
    }

    /**
     * Number from the action, or the contact tag if blank. Not every LightOS
     * build honours the SDK's OpenDialer.
     */
    private suspend fun openDialer(action: TagAction, scan: Scan?): ActionResult {
        val number = action.dialNumber?.takeIf { it.isNotBlank() }
            ?: scan?.contactPhone
        if (number.isNullOrBlank()) return ActionResult.Error("No phone number")

        return when (
            val result = callRemoteServiceMethod(
                LightServiceMethod.OpenDialer,
                LightServiceMethod.OpenDialer.Request(phoneNumber = number),
            )
        ) {
            is LightResult.Success -> ActionResult.Success("Dialer: $number")
            is LightResult.Error -> ActionResult.Error(result.extra ?: "Dialer unavailable")
        }
    }

    private fun executeWebhook(action: TagAction): ActionResult {
        val url = action.webhookUrl
        if (url.isNullOrBlank()) return ActionResult.Error("No URL configured")

        return try {
            val method = action.webhookMethod?.uppercase() ?: "GET"
            val builder = Request.Builder().url(url)

            // Apply custom headers
            parseHeaders(action.webhookHeaders).forEach { (key, value) ->
                builder.addHeader(key, value)
            }

            when (method) {
                "POST" -> {
                    val body = action.webhookBody ?: ""
                    val mediaType = "application/json".toMediaTypeOrNull()
                    builder.post(body.toRequestBody(mediaType))
                }
                "PUT" -> {
                    val body = action.webhookBody ?: ""
                    val mediaType = "application/json".toMediaTypeOrNull()
                    builder.put(body.toRequestBody(mediaType))
                }
                else -> builder.get()
            }

            val client = if (action.skipSsl) trustAllClient else strictClient
            val response = client.newCall(builder.build()).execute()
            Log.d(TAG, "Webhook ${action.label}: $method $url → ${response.code}")

            if (response.isSuccessful) {
                ActionResult.Success("${response.code} OK")
            } else {
                ActionResult.Error("HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Webhook failed: ${e.message}", e)
            ActionResult.Error(e.message ?: "Request failed")
        }
    }

    /**
     * Parses headers from a simple "Key: Value" per-line format.
     * Example:
     *   Authorization: Bearer abc123
     *   X-Custom: myvalue
     */
    internal fun parseHeaders(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.contains(":") }
            .map { line ->
                val colonIndex = line.indexOf(':')
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                key to value
            }
            .filter { it.first.isNotBlank() }
            .toList()
    }
}

// ── Tap processing ───────────────────────────────────────────────────────

/** What happened when a tag was tapped. */
sealed interface TapOutcome {
    /** Saved; no action bound. */
    data class Scanned(val scanId: Long, val scan: Scan) : TapOutcome

    /** Saved and the action ran. */
    data class ActionRan(
        val scanId: Long,
        val scan: Scan,
        val action: TagAction,
        val result: ActionResult,
    ) : TapOutcome

    /** Couldn't read or save the tag. */
    data class Failed(val message: String) : TapOutcome
}

/** Save a tap, look up its action, run it. Never throws; failures return [TapOutcome.Failed]. */
class NfcTapProcessor(
    private val scanRepo: NfcReaderRepository,
    private val actionRepo: TagActionRepository,
) {
    suspend fun process(tap: LightNfcTap): TapOutcome = try {
        val id = scanRepo.saveTap(tap)
        val scan = scanRepo.getScan(id)
            ?: return TapOutcome.Failed("Scan could not be read back")

        when (val action = actionRepo.getBySerial(tap.serialNumber)) {
            null -> TapOutcome.Scanned(id, scan)
            else -> TapOutcome.ActionRan(id, scan, action, ActionExecutor.execute(action, scan))
        }
    } catch (e: Exception) {
        Log.e(TAG, "Tap processing failed", e)
        TapOutcome.Failed(e.message ?: "Could not read tag")
    }
}
