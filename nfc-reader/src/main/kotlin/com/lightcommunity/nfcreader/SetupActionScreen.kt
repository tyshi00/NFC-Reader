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
import com.thelightphone.sdk.ui.LightTextField
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
import kotlinx.coroutines.withContext

data class SetupState(
    val label: String = "",
    val actionType: ActionType = ActionType.WEBHOOK,
    val webhookUrl: String = "",
    val webhookMethod: String = "POST",
    val webhookHeaders: String = "",
    val webhookBody: String = "",
    val skipSsl: Boolean = false,
    val noteText: String = "",
    val dialNumber: String = "",
    val testResult: String? = null,
    val saving: Boolean = false,
    val isEdit: Boolean = false,
)

class SetupActionViewModel(
    private val actionRepo: TagActionRepository,
    private val serial: String,
    existingAction: TagAction?,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow(
        if (existingAction != null) {
            SetupState(
                label = existingAction.label,
                actionType = existingAction.actionType,
                webhookUrl = existingAction.webhookUrl ?: "",
                webhookMethod = existingAction.webhookMethod ?: "POST",
                webhookHeaders = existingAction.webhookHeaders ?: "",
                webhookBody = existingAction.webhookBody ?: "",
                skipSsl = existingAction.skipSsl,
                noteText = existingAction.noteText ?: "",
                dialNumber = existingAction.dialNumber ?: "",
                isEdit = true,
            )
        } else {
            SetupState()
        },
    )
    val state: StateFlow<SetupState> = _state.asStateFlow()

    fun setLabel(value: String) { _state.value = _state.value.copy(label = value) }
    fun setWebhookUrl(value: String) { _state.value = _state.value.copy(webhookUrl = value, testResult = null) }
    fun setWebhookHeaders(value: String) { _state.value = _state.value.copy(webhookHeaders = value, testResult = null) }
    fun setWebhookBody(value: String) { _state.value = _state.value.copy(webhookBody = value, testResult = null) }
    fun setNoteText(value: String) { _state.value = _state.value.copy(noteText = value) }
    fun setDialNumber(value: String) { _state.value = _state.value.copy(dialNumber = value) }
    fun toggleSkipSsl() { _state.value = _state.value.copy(skipSsl = !_state.value.skipSsl, testResult = null) }

    fun toggleActionType() {
        val next = when (_state.value.actionType) {
            ActionType.WEBHOOK -> ActionType.NOTE
            ActionType.NOTE -> ActionType.DIAL
            ActionType.DIAL -> ActionType.WEBHOOK
        }
        _state.value = _state.value.copy(actionType = next, testResult = null)
    }

    fun toggleMethod() {
        val next = when (_state.value.webhookMethod) {
            "GET" -> "POST"
            "POST" -> "PUT"
            else -> "GET"
        }
        _state.value = _state.value.copy(webhookMethod = next, testResult = null)
    }

    fun testWebhook() {
        val s = _state.value
        if (s.webhookUrl.isBlank()) {
            _state.value = s.copy(testResult = "Enter a URL first")
            return
        }
        _state.value = s.copy(testResult = "Testing...")
        viewModelScope.launch(Dispatchers.IO) {
            val action = buildAction()
            val result = ActionExecutor.execute(action)
            _state.value = _state.value.copy(
                testResult = when (result) {
                    is ActionResult.Success -> "Success: ${result.message}"
                    is ActionResult.Error -> "Error: ${result.message}"
                },
            )
        }
    }

    fun save(onDone: () -> Unit) {
        _state.value = _state.value.copy(saving = true)
        viewModelScope.launch(Dispatchers.IO) {
            actionRepo.save(buildAction())
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            actionRepo.delete(serial)
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    private fun buildAction() = TagAction(
        serialNumber = serial,
        label = _state.value.label.ifBlank { "Tag ${ScanFormatting.formatSerial(serial)}" },
        actionType = _state.value.actionType,
        webhookUrl = _state.value.webhookUrl.takeIf { it.isNotBlank() },
        webhookMethod = _state.value.webhookMethod,
        webhookHeaders = _state.value.webhookHeaders.takeIf { it.isNotBlank() },
        webhookBody = _state.value.webhookBody.takeIf { it.isNotBlank() },
        skipSsl = _state.value.skipSsl,
        noteText = _state.value.noteText.takeIf { it.isNotBlank() },
        dialNumber = _state.value.dialNumber.takeIf { it.isNotBlank() },
        createdAt = System.currentTimeMillis(),
    )

    fun canSave(): Boolean {
        val s = _state.value
        return when (s.actionType) {
            ActionType.WEBHOOK -> s.webhookUrl.isNotBlank()
            ActionType.NOTE -> s.noteText.isNotBlank()
            // Blank is allowed: a contact tag supplies its own number at scan time.
            ActionType.DIAL -> true
        }
    }
}

class SetupActionScreen(
    sealedActivity: SealedLightActivity,
    private val actionRepo: TagActionRepository,
    private val scanRepo: NfcReaderRepository,
    private val serial: String,
    private val existingAction: TagAction? = null,
) : LightScreen<Unit, SetupActionViewModel>(sealedActivity) {

    override val viewModelClass: Class<SetupActionViewModel>
        get() = SetupActionViewModel::class.java

    override fun createViewModel() = SetupActionViewModel(actionRepo, serial, existingAction)

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
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text(
                        if (state.isEdit) "Edit Action" else "New Action",
                    ),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    // Tag ID (read-only)
                    LightText(text = "Tag", variant = LightTextVariant.Detail, lighten = true)
                    LightText(
                        text = ScanFormatting.formatSerial(serial),
                        variant = LightTextVariant.Fine, monospace = true,
                        modifier = Modifier.padding(bottom = 0.75f.gridUnitsAsDp()),
                    )

                    // Label
                    LightTextField(
                        label = "Label",
                        value = state.label,
                        placeholder = "e.g. Front door, Desk tag",
                        onClick = {
                            navigateTo(
                                screenFactory = { TextInputScreen(it, title = "Label", initialValue = state.label) },
                                resultCallback = { result -> if (result != null) viewModel.setLabel(result) },
                            )
                        },
                    )

                    // Action type toggle
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .lightClickable { viewModel.toggleActionType() }
                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            LightText(text = "Action type", variant = LightTextVariant.Detail)
                            LightText(text = state.actionType.label, variant = LightTextVariant.Copy)
                        }
                        LightText(text = "TAP TO CHANGE", variant = LightTextVariant.Detail, lighten = true)
                    }

                    // ── Webhook config ────────────────────────────────────
                    when (state.actionType) {
                        ActionType.WEBHOOK -> {
                            // Method toggle
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .lightClickable { viewModel.toggleMethod() }
                                    .padding(vertical = 0.75f.gridUnitsAsDp()),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    LightText(text = "Method", variant = LightTextVariant.Detail)
                                    LightText(text = state.webhookMethod, variant = LightTextVariant.Copy)
                                }
                                LightText(text = "TAP TO CHANGE", variant = LightTextVariant.Detail, lighten = true)
                            }

                            // URL
                            LightTextField(
                                label = "URL",
                                value = state.webhookUrl,
                                placeholder = "https://homeassistant.local/api/...",
                                onClick = {
                                    navigateTo(
                                        screenFactory = { TextInputScreen(it, title = "URL", initialValue = state.webhookUrl) },
                                        resultCallback = { result -> if (result != null) viewModel.setWebhookUrl(result) },
                                    )
                                },
                            )

                            // Headers
                            LightTextField(
                                label = "Headers",
                                value = state.webhookHeaders,
                                placeholder = "Authorization: Bearer token123",
                                onClick = {
                                    navigateTo(
                                        screenFactory = {
                                            TextInputScreen(
                                                it, title = "Headers",
                                                initialValue = state.webhookHeaders,
                                                singleLine = false,
                                            )
                                        },
                                        resultCallback = { result -> if (result != null) viewModel.setWebhookHeaders(result) },
                                    )
                                },
                            )

                            // Body (for POST/PUT)
                            if (state.webhookMethod != "GET") {
                                LightTextField(
                                    label = "Body",
                                    value = state.webhookBody,
                                    placeholder = "{\"action\": \"trigger\"}",
                                    onClick = {
                                        navigateTo(
                                            screenFactory = {
                                                TextInputScreen(
                                                    it, title = "Body",
                                                    initialValue = state.webhookBody,
                                                    singleLine = false,
                                                )
                                            },
                                            resultCallback = { result -> if (result != null) viewModel.setWebhookBody(result) },
                                        )
                                    },
                                )
                            }

                            // Skip SSL toggle
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .lightClickable { viewModel.toggleSkipSsl() }
                                    .padding(vertical = 0.75f.gridUnitsAsDp()),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LightIcon(
                                    icon = if (state.skipSsl) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                                    modifier = Modifier.padding(end = 0.75f.gridUnitsAsDp()),
                                )
                                Column {
                                    LightText(text = "Skip SSL verification", variant = LightTextVariant.Copy)
                                    LightText(
                                        text = "For self-signed certificates",
                                        variant = LightTextVariant.Detail, lighten = true,
                                    )
                                }
                            }

                            // Test result
                            state.testResult?.let { result ->
                                LightText(
                                    text = result,
                                    variant = LightTextVariant.Detail, lighten = true,
                                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                                )
                            }
                        }

                        // ── Note config ──────────────────────────────────────
                        ActionType.NOTE -> {
                            LightTextField(
                                label = "Note",
                                value = state.noteText,
                                placeholder = "Text to display when scanned",
                                onClick = {
                                    navigateTo(
                                        screenFactory = {
                                            TextInputScreen(
                                                it, title = "Note",
                                                initialValue = state.noteText,
                                                singleLine = false,
                                            )
                                        },
                                        resultCallback = { result -> if (result != null) viewModel.setNoteText(result) },
                                    )
                                },
                            )
                        }

                        // ── Dialer config ────────────────────────────────────
                        ActionType.DIAL -> {
                            LightTextField(
                                label = "Phone number",
                                value = state.dialNumber,
                                placeholder = "Leave blank to use a contact tag's number",
                                onClick = {
                                    navigateTo(
                                        screenFactory = { TextInputScreen(it, title = "Phone number", initialValue = state.dialNumber) },
                                        resultCallback = { result -> if (result != null) viewModel.setDialNumber(result) },
                                    )
                                },
                            )
                            LightText(
                                text = "Asks LightOS to open the dialer. Some builds may not support this yet.",
                                variant = LightTextVariant.Detail, lighten = true,
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                        }
                    }
                }

                // Bottom bar: [trash] [TEST] [SAVE]  or  [TEST] [SAVE]
                LightBottomBar(
                    items = buildList {
                        if (state.isEdit) {
                            add(LightBarButton.LightIcon(
                                icon = LightIcons.TRASH,
                                onClick = {
                                    navigateTo(
                                        screenFactory = {
                                            ConfirmActionScreen(
                                                it,
                                                message = "Delete this action? The tag will go back to showing scan results only.",
                                                title = "Delete action",
                                                confirmLabel = "DELETE",
                                            )
                                        },
                                        resultCallback = { confirmed ->
                                            if (confirmed == true) viewModel.delete { goBack() }
                                        },
                                    )
                                },
                                contentDescription = "Delete",
                            ))
                        }
                        if (state.actionType == ActionType.WEBHOOK) {
                            add(LightBarButton.Text(text = "TEST", onClick = { viewModel.testWebhook() }))
                        }
                        add(LightBarButton.Text(
                            text = if (state.saving) "SAVING..." else "SAVE",
                            onClick = { if (viewModel.canSave()) viewModel.save { goBack() } },
                        ))
                    },
                )
            }
        }
    }
}
