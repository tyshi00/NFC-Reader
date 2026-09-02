package com.lightcommunity.nfcreader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightNfcTapReader
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.nfc.LightNfcTap
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
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

data class ScanResult(
    val scanId: Long,
    val scan: Scan,
)

sealed interface ScanState {
    data object Waiting : ScanState
    data object Processing : ScanState
    data class ScannedTag(val result: ScanResult) : ScanState
    data class ActionTriggered(
        val result: ScanResult,
        val action: TagAction,
        val actionResult: ActionResult,
    ) : ScanState
    data class Failed(val message: String) : ScanState
}

class ScanScreenViewModel(
    repo: NfcReaderRepository,
    actionRepo: TagActionRepository,
) : LightViewModel<Unit>() {

    private val processor = NfcTapProcessor(repo, actionRepo)

    private val _state = MutableStateFlow<ScanState>(ScanState.Waiting)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    fun onTap(tap: LightNfcTap) {
        // Ignore a tap only mid-processing; a tap over a shown result still counts.
        if (_state.value == ScanState.Processing) return
        _state.value = ScanState.Processing
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = when (val outcome = processor.process(tap)) {
                is TapOutcome.Scanned ->
                    ScanState.ScannedTag(ScanResult(outcome.scanId, outcome.scan))
                is TapOutcome.ActionRan ->
                    ScanState.ActionTriggered(
                        result = ScanResult(outcome.scanId, outcome.scan),
                        action = outcome.action,
                        actionResult = outcome.result,
                    )
                is TapOutcome.Failed ->
                    ScanState.Failed(outcome.message)
            }
        }
    }

    fun resetForNextScan() {
        _state.value = ScanState.Waiting
    }
}

class ScanScreen(
    private val sealedActivity: SealedLightActivity,
    private val repo: NfcReaderRepository,
    private val actionRepo: TagActionRepository,
) : LightScreen<Unit, ScanScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<ScanScreenViewModel>
        get() = ScanScreenViewModel::class.java

    override fun createViewModel() = ScanScreenViewModel(repo, actionRepo)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        LightTheme(colors = themeColors) {
            Box(modifier = Modifier.fillMaxSize()) {
                LightNfcTapReader(
                    onTap = { tap -> viewModel.onTap(tap) },
                    onBack = { goBack() },
                    title = "Scan",
                    prompt = "Hold your phone near an NFC tag.",
                )

                when (val s = state) {
                    is ScanState.Processing -> {
                        FullOverlay { LightText(text = "Reading...", variant = LightTextVariant.Copy, lighten = true, align = TextAlign.Center) }
                    }
                    is ScanState.Failed -> {
                        Column(
                            modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background),
                        ) {
                            LightTopBar(
                                center = LightTopBarCenter.Text("Couldn't read tag"),
                                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                            )
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
                                contentAlignment = Alignment.Center,
                            ) {
                                LightText(
                                    text = s.message,
                                    variant = LightTextVariant.Copy,
                                    lighten = true,
                                    align = TextAlign.Center,
                                )
                            }
                            LightBottomBar(
                                items = listOf(
                                    LightBarButton.LightIcon(
                                        icon = LightIcons.REFRESH,
                                        onClick = { viewModel.resetForNextScan() },
                                        contentDescription = "Try again",
                                    ),
                                ),
                            )
                        }
                    }
                    is ScanState.ActionTriggered -> {
                        ActionResultOverlay(
                            action = s.action,
                            actionResult = s.actionResult,
                            scan = s.result.scan,
                            onScanAgain = { viewModel.resetForNextScan() },
                            onViewDetails = {
                                navigateTo(screenFactory = { TapDetailScreen(it, repo, actionRepo, s.result.scanId) })
                                viewModel.resetForNextScan()
                            },
                            onEditAction = {
                                navigateTo(screenFactory = {
                                    SetupActionScreen(it, actionRepo, repo, serial = s.action.serialNumber, existingAction = s.action)
                                })
                                viewModel.resetForNextScan()
                            },
                        )
                    }
                    is ScanState.ScannedTag -> {
                        TagResultOverlay(
                            result = s.result,
                            onScanAgain = { viewModel.resetForNextScan() },
                            onViewDetails = {
                                navigateTo(screenFactory = { TapDetailScreen(it, repo, actionRepo, s.result.scanId) })
                                viewModel.resetForNextScan()
                            },
                            onAddAction = {
                                navigateTo(screenFactory = {
                                    SetupActionScreen(it, actionRepo, repo, serial = s.result.scan.serialNumber)
                                })
                                viewModel.resetForNextScan()
                            },
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

// ── Action result overlay ────────────────────────────────────────────────

@Composable
private fun ActionResultOverlay(
    action: TagAction,
    actionResult: ActionResult,
    scan: Scan,
    onScanAgain: () -> Unit,
    onViewDetails: () -> Unit,
    onEditAction: () -> Unit,
) {
    val isSuccess = actionResult is ActionResult.Success
    Column(
        modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            center = LightTopBarCenter.Text(action.label),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (action.actionType == ActionType.NOTE) {
                        // Show the note text prominently
                        LightText(
                            text = action.noteText ?: "",
                            variant = LightTextVariant.Heading,
                            align = TextAlign.Center,
                        )
                    } else {
                        // Webhook result
                        LightText(
                            text = if (isSuccess) "Triggered" else "Failed",
                            variant = LightTextVariant.Heading,
                            align = TextAlign.Center,
                        )
                        LightText(
                            text = when (actionResult) {
                                is ActionResult.Success -> actionResult.message
                                is ActionResult.Error -> actionResult.message
                            },
                            variant = LightTextVariant.Copy,
                            lighten = true,
                            align = TextAlign.Center,
                            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = action.summary(),
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            align = TextAlign.Center,
                            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                        )
                    }

                    LightText(
                        text = ScanFormatting.formatSerial(scan.serialNumber),
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        monospace = true,
                        align = TextAlign.Center,
                        modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                    )
                }
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.REFRESH,
                    onClick = onScanAgain,
                    contentDescription = "Scan again",
                ),
                LightBarButton.Text(text = "EDIT", onClick = onEditAction),
            ),
        )
    }
}

// ── Tag result overlay (no action assigned) ──────────────────────────────

@Composable
private fun TagResultOverlay(
    result: ScanResult,
    onScanAgain: () -> Unit,
    onViewDetails: () -> Unit,
    onAddAction: () -> Unit,
) {
    val scan = result.scan
    Column(
        modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            center = LightTopBarCenter.Text(
                if (scan.isContact) "Contact Scanned" else "Scanned",
            ),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (scan.isContact) {
                        scan.contactDisplayName?.let { name ->
                            LightText(text = name, variant = LightTextVariant.Heading, align = TextAlign.Center)
                        }
                        scan.contactPhone?.let { phone ->
                            LightText(
                                text = phone, variant = LightTextVariant.Copy, align = TextAlign.Center,
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                            LightText(
                                text = "Long-press to copy", variant = LightTextVariant.Detail,
                                lighten = true, align = TextAlign.Center,
                                modifier = Modifier.padding(top = 0.15f.gridUnitsAsDp()),
                            )
                        }
                        scan.contactEmail?.let { email ->
                            LightText(
                                text = email, variant = LightTextVariant.Copy, lighten = true, align = TextAlign.Center,
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                        }
                    } else {
                        LightText(text = scan.preview(), variant = LightTextVariant.Heading, align = TextAlign.Center)
                        LightText(
                            text = scan.typeLabel(), variant = LightTextVariant.Copy, lighten = true, align = TextAlign.Center,
                            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                        )
                    }

                    LightText(
                        text = ScanFormatting.formatSerial(scan.serialNumber),
                        variant = LightTextVariant.Fine, lighten = true, monospace = true, align = TextAlign.Center,
                        modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                    )
                }
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.REFRESH,
                    onClick = onScanAgain,
                    contentDescription = "Scan again",
                ),
                LightBarButton.Text(text = "ACTION", onClick = onAddAction),
                LightBarButton.LightIcon(
                    icon = LightIcons.ARROW_RIGHT,
                    onClick = onViewDetails,
                    contentDescription = "View details",
                ),
            ),
        )
    }
}

@Composable
private fun FullOverlay(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
