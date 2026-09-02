package com.lightcommunity.nfcreader

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.nfc.LightNfcTap
import com.thelightphone.sdk.rememberLightNfc
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
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private const val TAG = "NfcHome"
private const val BANNER_MS = 4000L

/** A short-lived message shown after a tag is tapped while History is open. */
data class TapBanner(val text: String, val isError: Boolean)

data class HomeState(
    val scans: List<Scan> = emptyList(),
    val loading: Boolean = true,
    val banner: TapBanner? = null,
)

class HomeScreenViewModel(
    private val repo: NfcReaderRepository,
    actionRepo: TagActionRepository,
) : LightViewModel<Unit>() {

    private val processor = NfcTapProcessor(repo, actionRepo)
    private var ambientBusy = false

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            if (repo.getInvertColors()) LightThemeController.setLightTheme() else LightThemeController.setDarkTheme()
            val scans = repo.getAllScans()
            _state.value = _state.value.copy(scans = scans, loading = false)
        }
    }

    /** A tap that landed while the app was open on the History screen. */
    fun onAmbientTap(tap: LightNfcTap) {
        if (ambientBusy) return
        ambientBusy = true
        viewModelScope.launch {
            val banner = when (val outcome = processor.process(tap)) {
                is TapOutcome.Scanned ->
                    TapBanner("Saved: ${outcome.scan.preview().take(40)}", isError = false)
                is TapOutcome.ActionRan -> {
                    val detail = when (val r = outcome.result) {
                        is ActionResult.Success -> r.message
                        is ActionResult.Error -> r.message
                    }
                    TapBanner(
                        "${outcome.action.label} — $detail",
                        isError = outcome.result is ActionResult.Error,
                    )
                }
                is TapOutcome.Failed ->
                    TapBanner(outcome.message, isError = true)
            }
            _state.value = _state.value.copy(scans = repo.getAllScans(), banner = banner)
            ambientBusy = false
        }
    }

    fun dismissBanner() {
        _state.value = _state.value.copy(banner = null)
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    private val repo = NfcReaderRepository.getInstance {
        lightContext.buildDatabase(NfcReaderDatabase::class.java, "nfc_reader.db")
    }

    private val actionRepo = TagActionRepository.getInstance {
        lightContext.buildDatabase(TagActionDatabase::class.java, "nfc_actions_v3.db")
    }

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel() = HomeScreenViewModel(repo, actionRepo)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        // Ambient reader: while History is on screen, a tap runs the tag's
        // action and logs it — the same as pressing SCAN first. Reader mode is
        // still foreground-only, so this does nothing while the app is closed.
        val nfc = rememberLightNfc()
        LaunchedEffect(nfc) {
            val reader = nfc ?: return@LaunchedEffect
            reader.newReader().asFlow()
                .catch { Log.w(TAG, "ambient reader stopped: ${it.message}") }
                .collect { tap -> viewModel.onAmbientTap(tap) }
        }
        LaunchedEffect(state.banner) {
            if (state.banner != null) {
                delay(BANNER_MS)
                viewModel.dismissBanner()
            }
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text("NFC Reader"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                state.banner?.let { banner ->
                    LightText(
                        text = banner.text,
                        variant = LightTextVariant.Detail,
                        lighten = banner.isError,
                        align = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp())
                            .padding(bottom = 0.75f.gridUnitsAsDp()),
                    )
                }

                if (state.loading) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LightText(
                            text = "Loading...",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                            align = TextAlign.Center,
                        )
                    }
                } else if (state.scans.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 1.5f.gridUnitsAsDp()),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LightText(
                            text = "No scans yet",
                            variant = LightTextVariant.Heading,
                            align = TextAlign.Center,
                        )
                        LightText(
                            text = "Tap Scan below to read an NFC tag.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                            align = TextAlign.Center,
                            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                        )
                    }
                } else {
                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                            LightText(
                                text = "History",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                            )
                            state.scans.forEach { scan ->
                                ScanRow(
                                    scan = scan,
                                    onClick = {
                                        navigateTo(
                                            screenFactory = { TapDetailScreen(it, repo, scan.id) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.SETTINGS,
                            onClick = {
                                navigateTo(screenFactory = { SettingsScreen(it, repo) })
                            },
                            contentDescription = "Settings",
                        ),
                        LightBarButton.Text(
                            text = "SCAN",
                            onClick = {
                                navigateTo(screenFactory = { ScanScreen(it, repo, actionRepo) })
                            },
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.LIST,
                            onClick = {
                                navigateTo(screenFactory = { ActionsListScreen(it, actionRepo, repo) })
                            },
                            contentDescription = "Actions",
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun ScanRow(scan: Scan, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = scan.preview(),
                variant = LightTextVariant.Copy,
                maxLines = 1,
            )
            Row(
                modifier = Modifier.padding(top = 0.15f.gridUnitsAsDp()),
            ) {
                LightText(
                    text = scan.typeLabel(),
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
                LightText(
                    text = "  ·  ",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
                LightText(
                    text = ScanFormatting.relativeTimestamp(scan.timestampMs),
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
            }
        }
    }
}
