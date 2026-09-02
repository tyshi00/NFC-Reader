package com.lightcommunity.nfcreader

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.rememberLightNfc
import com.thelightphone.sdk.ui.LightModal
import com.thelightphone.sdk.ui.LightModalManager
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlin.time.Duration.Companion.seconds

private const val TAG = "AmbientNfc"

/**
 * Makes a tag work on every screen, not just the scanner. [HomeScreen] — the
 * initial screen, which owns both repositories — publishes the processor once;
 * every other screen just calls [AmbientNfcReader] and the tap runs its action.
 *
 * NFC is still foreground-only: this does nothing while the app is closed or
 * the screen is off.
 */
object AmbientNfc {
    @Volatile
    var processor: NfcTapProcessor? = null

    /** Pinged after an ambient tap is saved so the history list can refresh. */
    val scansChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
}

/**
 * Runs the NFC reader while the calling screen is showing and turns each tap
 * into a result modal drawn over the app.
 *
 * Not for [ScanScreen] — that screen runs its own full-screen reader. The SDK
 * hands taps to whichever screen is on top, so several of these can be mounted
 * in the back stack at once without a tag being processed twice.
 */
@Composable
fun AmbientNfcReader() {
    val processor = AmbientNfc.processor ?: return
    val nfc = rememberLightNfc()
    LaunchedEffect(nfc) {
        val reader = nfc ?: return@LaunchedEffect
        reader.newReader().asFlow()
            .catch { Log.w(TAG, "ambient reader stopped: ${it.message}") }
            .collect { tap ->
                val outcome = processor.process(tap)
                if (outcome !is TapOutcome.Failed) AmbientNfc.scansChanged.tryEmit(Unit)
                LightModalManager.show(TapResultModal(outcome), duration = 3.seconds)
            }
    }
}

private class TapResultModal(
    private val outcome: TapOutcome,
    override val onExpired: () -> Unit = {},
) : LightModal {

    private val dismissed = CompletableDeferred<Unit>()
    override fun dismiss() {
        dismissed.complete(Unit)
    }
    override suspend fun awaitDismiss() {
        dismissed.await()
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val (heading, detail) = describe(outcome)

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .lightClickable { dismiss() }
                    .padding(horizontal = 1.5f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LightText(
                    text = heading,
                    variant = LightTextVariant.Heading,
                    align = TextAlign.Center,
                )
                if (detail != null) {
                    LightText(
                        text = detail,
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        align = TextAlign.Center,
                        modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

private fun describe(outcome: TapOutcome): Pair<String, String?> = when (outcome) {
    is TapOutcome.Scanned ->
        "Saved" to outcome.scan.preview().take(60)
    is TapOutcome.ActionRan -> {
        val message = when (val r = outcome.result) {
            is ActionResult.Success -> r.message
            is ActionResult.Error -> r.message
        }
        outcome.action.label to message
    }
    is TapOutcome.Failed ->
        "Couldn't read tag" to outcome.message
}
