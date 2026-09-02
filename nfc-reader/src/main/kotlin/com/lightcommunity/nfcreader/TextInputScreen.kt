package com.lightcommunity.nfcreader

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import kotlinx.coroutines.flow.MutableStateFlow

/** Full-screen text entry. Returns the entered string via goBack(result). */
class TextInputScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initialValue: String = "",
    private val submitLabel: String = "DONE",
    private val singleLine: Boolean = true,
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state = rememberTextFieldState(initialValue)
        val keyboardOptions = remember { MutableStateFlow(defaultKeyboardOptions()) }

        LightTheme(colors = themeColors) {
            LightTextInputEditor(
                title = title,
                state = state,
                keyboardOptionsFlow = keyboardOptions,
                onSubmit = { text -> goBack(text.toString()) },
                onBack = { goBack(null) },
                submitLabel = submitLabel,
                singleLine = singleLine,
            )
        }
    }
}
