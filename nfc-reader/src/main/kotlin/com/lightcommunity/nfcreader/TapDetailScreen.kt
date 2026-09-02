package com.lightcommunity.nfcreader

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightFileShare
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TapDetailState(
    val scan: Scan? = null,
    val loading: Boolean = true,
    val exported: Boolean = false,
)

class TapDetailViewModel(
    private val repo: NfcReaderRepository,
    private val fileShare: LightFileShare,
    private val scanId: Long,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow(TapDetailState())
    val state: StateFlow<TapDetailState> = _state.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            val scan = repo.getScan(scanId)
            _state.value = TapDetailState(scan = scan, loading = false)
        }
    }

    fun exportScan() {
        val scan = _state.value.scan ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val filename = "nfc-scans/${scan.serialNumber}.txt"
            fileShare.write(filename) { writer ->
                writer.appendLine("NFC Tag Scan")
                writer.appendLine("Serial: ${ScanFormatting.formatSerial(scan.serialNumber)}")
                if (scan.isContact) {
                    scan.contactDisplayName?.let { writer.appendLine("Name: $it") }
                    scan.contactPhone?.let { writer.appendLine("Phone: $it") }
                    scan.contactEmail?.let { writer.appendLine("Email: $it") }
                } else {
                    scan.uri?.let { writer.appendLine("URI: $it") }
                    scan.text?.let { writer.appendLine("Text: $it") }
                }
                writer.appendLine("Scanned: ${ScanFormatting.fullTimestamp(scan.timestampMs)}")
            }
            _state.value = _state.value.copy(exported = true)
        }
    }

    fun deleteScan(onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.deleteScan(scanId)
            withContext(Dispatchers.Main) { onDone() }
        }
    }
}

class TapDetailScreen(
    sealedActivity: SealedLightActivity,
    private val repo: NfcReaderRepository,
    private val scanId: Long,
) : LightScreen<Unit, TapDetailViewModel>(sealedActivity) {

    override val viewModelClass: Class<TapDetailViewModel>
        get() = TapDetailViewModel::class.java

    override fun createViewModel() = TapDetailViewModel(repo, lightContext.fileShare, scanId)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                val scan = state.scan
                val title = if (scan?.isContact == true) "Contact" else "Tag Details"

                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text(title),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    // SelectionContainer enables long-press text selection
                    // and copy to clipboard across all content inside it.
                    SelectionContainer {
                        Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                            if (state.loading) {
                                LightText(
                                    text = "Loading...",
                                    variant = LightTextVariant.Copy,
                                    lighten = true,
                                )
                            } else if (scan == null) {
                                LightText(
                                    text = "Scan not found.",
                                    variant = LightTextVariant.Copy,
                                    lighten = true,
                                )
                            } else if (scan.isContact) {
                                ContactContent(scan = scan)
                            } else {
                                TagContent(scan = scan)
                            }
                        }
                    }
                }

                LightBottomBar(
                    items = if (scan != null) {
                        listOf(
                            LightBarButton.LightIcon(
                                icon = LightIcons.TRASH,
                                onClick = {
                                    navigateTo(
                                        screenFactory = {
                                            ConfirmActionScreen(
                                                it,
                                                message = "Delete this scan from history?",
                                                title = "Delete scan",
                                                confirmLabel = "DELETE",
                                            )
                                        },
                                        resultCallback = { confirmed ->
                                            if (confirmed == true) {
                                                viewModel.deleteScan { goBack() }
                                            }
                                        },
                                    )
                                },
                                contentDescription = "Delete",
                            ),
                            LightBarButton.Text(
                                text = if (state.exported) "SAVED" else "SAVE",
                                onClick = { viewModel.exportScan() },
                            ),
                        )
                    } else {
                        listOf()
                    },
                )
            }
        }
    }
}

// ── Contact display ──────────────────────────────────────────────────────

@Composable
private fun ContactContent(scan: Scan) {
    // Name
    scan.contactDisplayName?.let { name ->
        DetailSection(label = "Name") {
            LightText(
                text = name,
                variant = LightTextVariant.Heading,
            )
        }
    }

    // Phone — long-press to select and copy
    scan.contactPhone?.let { phone ->
        DetailSection(label = "Phone  ·  long-press to copy") {
            LightText(
                text = phone,
                variant = LightTextVariant.Heading,
            )
        }
    }

    // Email — long-press to select and copy
    scan.contactEmail?.let { email ->
        DetailSection(label = "Email  ·  long-press to copy") {
            LightText(
                text = email,
                variant = LightTextVariant.Copy,
            )
        }
    }

    // Serial number
    DetailSection(label = "Tag ID") {
        LightText(
            text = ScanFormatting.formatSerial(scan.serialNumber),
            variant = LightTextVariant.Fine,
            monospace = true,
            lighten = true,
        )
    }

    // Timestamp
    DetailSection(label = "Scanned") {
        LightText(
            text = ScanFormatting.fullTimestamp(scan.timestampMs),
            variant = LightTextVariant.Fine,
            lighten = true,
        )
    }
}

// ── Generic tag display ──────────────────────────────────────────────────

@Composable
private fun TagContent(scan: Scan) {
    // Serial number
    DetailSection(label = "Serial Number") {
        LightText(
            text = ScanFormatting.formatSerial(scan.serialNumber),
            variant = LightTextVariant.Heading,
            monospace = true,
        )
    }

    // URI record
    if (scan.uri != null) {
        DetailSection(label = "URI  ·  long-press to copy") {
            LightText(
                text = scan.uri,
                variant = LightTextVariant.Copy,
                underline = true,
            )
        }
    }

    // Text record
    if (scan.text != null) {
        DetailSection(label = buildString {
            append("Text")
            if (scan.textLanguage != null) append(" (${scan.textLanguage})")
        }) {
            LightText(
                text = scan.text,
                variant = LightTextVariant.Copy,
            )
        }
    }

    // Binary records
    if (scan.binaryRecordCount > 0) {
        DetailSection(label = "Binary Records") {
            LightText(
                text = "${scan.binaryRecordCount} binary record${if (scan.binaryRecordCount != 1) "s" else ""}",
                variant = LightTextVariant.Copy,
                lighten = true,
            )
        }
    }

    // Empty tag
    if (scan.totalRecordCount == 0) {
        DetailSection(label = "Records") {
            LightText(
                text = "No NDEF records. This tag only has a UID.",
                variant = LightTextVariant.Copy,
                lighten = true,
            )
        }
    }

    // Timestamp
    DetailSection(label = "Scanned") {
        LightText(
            text = ScanFormatting.fullTimestamp(scan.timestampMs),
            variant = LightTextVariant.Copy,
        )
    }
}

@Composable
private fun DetailSection(label: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
        )
        content()
    }
}
