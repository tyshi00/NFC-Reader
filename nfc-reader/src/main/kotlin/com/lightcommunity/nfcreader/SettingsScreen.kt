package com.lightcommunity.nfcreader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsState(
    val invertColors: Boolean = false,
    val scanCount: Int = 0,
)

class SettingsViewModel(private val repo: NfcReaderRepository) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        reload()
    }

    private fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = SettingsState(
                invertColors = repo.getInvertColors(),
                scanCount = repo.scanCount(),
            )
        }
    }

    fun toggleInvertColors() {
        viewModelScope.launch(Dispatchers.IO) {
            val newValue = !_state.value.invertColors
            repo.setInvertColors(newValue)
            _state.value = _state.value.copy(invertColors = newValue)
            if (newValue) LightThemeController.setLightTheme() else LightThemeController.setDarkTheme()
        }
    }

    fun deleteAllScans() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.deleteAllScans()
            _state.value = _state.value.copy(scanCount = 0)
        }
    }
}

class SettingsScreen(
    sealedActivity: SealedLightActivity,
    private val repo: NfcReaderRepository,
) : LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel() = SettingsViewModel(repo)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        AmbientNfcReader()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text("Settings"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    // Invert colors toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { viewModel.toggleInvertColors() }
                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LightIcon(
                            icon = if (state.invertColors) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                            modifier = Modifier.padding(end = 0.75f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = "Invert colors",
                            variant = LightTextVariant.Copy,
                        )
                    }

                    // Clear scan history
                    if (state.scanCount > 0) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable {
                                    navigateTo(
                                        screenFactory = {
                                            ConfirmActionScreen(
                                                it,
                                                message = "Delete all ${state.scanCount} saved scan${if (state.scanCount != 1) "s" else ""}? This can't be undone.",
                                                title = "Clear history",
                                                confirmLabel = "DELETE",
                                            )
                                        },
                                        resultCallback = { confirmed ->
                                            if (confirmed == true) viewModel.deleteAllScans()
                                        },
                                    )
                                }
                                .padding(vertical = 0.75f.gridUnitsAsDp()),
                        ) {
                            LightText(
                                text = "Clear scan history",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                            )
                            LightText(
                                text = "${state.scanCount} scan${if (state.scanCount != 1) "s" else ""} saved",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(top = 0.15f.gridUnitsAsDp()),
                            )
                        }
                    }

                    // Version info
                    LightText(
                        text = "NFC Reader v1.1.0",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp()),
                    )
                }

                LightBottomBar(items = listOf())
            }
        }
    }
}
