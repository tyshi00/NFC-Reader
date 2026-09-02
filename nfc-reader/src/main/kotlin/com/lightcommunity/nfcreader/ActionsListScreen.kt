package com.lightcommunity.nfcreader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
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
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ActionsListViewModel(private val actionRepo: TagActionRepository) : LightViewModel<Unit>() {

    private val _actions = MutableStateFlow<List<TagAction>>(emptyList())
    val actions: StateFlow<List<TagAction>> = _actions.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        reload()
    }

    private fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            _actions.value = actionRepo.getAll()
        }
    }
}

class ActionsListScreen(
    sealedActivity: SealedLightActivity,
    private val actionRepo: TagActionRepository,
    private val scanRepo: NfcReaderRepository,
) : LightScreen<Unit, ActionsListViewModel>(sealedActivity) {

    override val viewModelClass: Class<ActionsListViewModel>
        get() = ActionsListViewModel::class.java

    override fun createViewModel() = ActionsListViewModel(actionRepo)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val actions by viewModel.actions.collectAsState()

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
                    center = LightTopBarCenter.Text("Actions"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                if (actions.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 1.5f.gridUnitsAsDp()),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LightText(
                            text = "No actions yet",
                            variant = LightTextVariant.Heading,
                            align = TextAlign.Center,
                        )
                        LightText(
                            text = "Scan a tag, then tap Add Action to assign a webhook or note to it.",
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
                            actions.forEach { action ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .lightClickable {
                                            navigateTo(
                                                screenFactory = {
                                                    SetupActionScreen(
                                                        it, actionRepo, scanRepo,
                                                        serial = action.serialNumber,
                                                        existingAction = action,
                                                    )
                                                },
                                            )
                                        }
                                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                                ) {
                                    LightText(
                                        text = action.label,
                                        variant = LightTextVariant.Copy,
                                    )
                                    LightText(
                                        text = "${action.actionType.label}  ·  ${ScanFormatting.formatSerial(action.serialNumber)}",
                                        variant = LightTextVariant.Detail,
                                        lighten = true,
                                        modifier = Modifier.padding(top = 0.15f.gridUnitsAsDp()),
                                    )
                                    LightText(
                                        text = action.summary(),
                                        variant = LightTextVariant.Detail,
                                        lighten = true,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                LightBottomBar(items = listOf())
            }
        }
    }
}
