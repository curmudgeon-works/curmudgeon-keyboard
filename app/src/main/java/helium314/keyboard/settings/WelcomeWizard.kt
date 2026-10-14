// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.previewDark
import kotlinx.coroutines.delay

/** The setup, always in Curmudgeon's Black with orange (2026-10-10; it had the phone's Dynamic colors, blue before
 *  Android 12), whatever colors the keyboard has. */
@Composable
fun WelcomeWizard(
    close: () -> Unit,
    finish: () -> Unit
) = MaterialTheme(colorScheme = helium314.keyboard.latin.utils.AppLook.curmudgeonScheme, typography = MaterialTheme.typography) {
    WelcomeWizardSteps(close, finish)
}

/** The setup's checklist, the same rows on every screen (owner 2026-10-14: one list, the step's text in a card above). */
internal enum class SetupRow { ENABLE, SWITCH, THEME, CONFIGURE, FINISH }

/** How a row of the checklist looks: done (faded orange with a check), the step to take now (orange, framed), later
 *  (faint), open (orange, as a step can be taken again), or the filled Finished. */
internal enum class SetupRowLook { DONE, CURRENT, LATER, OPEN, FILLED }

internal class SetupRowState(val look: SetupRowLook, val clickable: Boolean)

/**
 * Row [row] of the checklist on screen [step]: 1 the keyboard isn't enabled, 2 enabled but not the one in use, 3 in use.
 * Configure looks faint until 3 but can be tapped from 2 on (owner 2026-10-14: "maybe even faded Configure button should
 * let you go into our settings?"; on 1 the settings' Switch bar would open a picker without the keyboard in it);
 * Finished only on 3.
 */
internal fun setupRowState(row: SetupRow, step: Int): SetupRowState = when (row) {
    SetupRow.ENABLE -> if (step <= 1) SetupRowState(SetupRowLook.CURRENT, true) else SetupRowState(SetupRowLook.DONE, false)
    SetupRow.SWITCH -> when {
        step <= 1 -> SetupRowState(SetupRowLook.LATER, false)
        step == 2 -> SetupRowState(SetupRowLook.CURRENT, true)
        else -> SetupRowState(SetupRowLook.DONE, false)
    }
    SetupRow.THEME -> if (step >= 3) SetupRowState(SetupRowLook.OPEN, true) else SetupRowState(SetupRowLook.LATER, false)
    SetupRow.CONFIGURE -> when {
        step <= 1 -> SetupRowState(SetupRowLook.LATER, false)
        step == 2 -> SetupRowState(SetupRowLook.LATER, true)
        else -> SetupRowState(SetupRowLook.OPEN, true)
    }
    SetupRow.FINISH -> if (step >= 3) SetupRowState(SetupRowLook.FILLED, true) else SetupRowState(SetupRowLook.LATER, false)
}

@Composable
private fun WelcomeWizardSteps(
    close: () -> Unit,
    finish: () -> Unit
) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    // (no Welcome picture screen since 2026-10-14, owner: "Perhaps we don't even need the first screen at all? Start with
    // Enable in settings as screen 1")
    fun determineStep(): Int = when {
        !UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm) -> 1
        !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm) -> 2
        else -> 3
    }
    var step by rememberSaveable { mutableIntStateOf(determineStep()) }
    // the keyboard picker is a window over this screen: on 2, looked at until the keyboard is the one in use (closed
    // without a switch, nothing changes)
    LaunchedEffect(step) {
        if (step != 2) return@LaunchedEffect
        while (!UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)) delay(50)
        step = 3
    }
    val useWideLayout = isWideScreen()
    // black, the rows a shade lighter, the title and what to tap in orange; the card's text a dimmer gray (owner
    // 2026-10-14: "White text is too bright. It can be dimmer gray")
    val stepBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = MaterialTheme.colorScheme.primary
    val titleColor = MaterialTheme.colorScheme.primary
    val cardTitleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val cardTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val appName = stringResource(ctx.applicationInfo.labelRes)
    @Composable fun bigText() {
        // "Welcome to" on the first screen, "Setting up" on the later ones (owner 2026-10-14)
        val resource = if (step <= 1) R.string.setup_welcome_title else R.string.setup_steps_title
        Column(Modifier.padding(bottom = 36.dp)) {
            Text(
                stringResource(resource, appName),
                style = MaterialTheme.typography.displayMedium,
                textAlign = TextAlign.Center,
                color = titleColor,
            )
        }
    }
    @Composable fun card(title: String, text: String?) {
        Column(Modifier.fillMaxWidth().background(color = stepBackgroundColor).padding(16.dp)) {
            Text(title, color = cardTitleColor)
            if (text != null)
                Text(text, style = MaterialTheme.typography.bodyLarge.merge(color = cardTextColor))
        }
    }
    @Composable fun row(row: SetupRow, text: String, icon: Painter, action: () -> Unit) {
        val state = setupRowState(row, step)
        val primary = MaterialTheme.colorScheme.primary
        val color = when (state.look) {
            SetupRowLook.DONE -> primary.copy(alpha = 0.45f) // (owner 2026-10-14: "faded orange instead of faded gray")
            SetupRowLook.CURRENT, SetupRowLook.OPEN -> primary
            SetupRowLook.LATER -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            SetupRowLook.FILLED -> MaterialTheme.colorScheme.onPrimary
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth()
                .then(if (state.clickable) Modifier.clickable { action() } else Modifier)
                .background(color = if (state.look == SetupRowLook.FILLED) primary else stepBackgroundColor)
                .then(if (state.look == SetupRowLook.CURRENT) Modifier.border(2.dp, primary) else Modifier)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val shown = if (state.look == SetupRowLook.DONE) painterResource(R.drawable.ic_setup_check) else icon
            Icon(shown, null, Modifier.padding(end = 6.dp).size(32.dp), tint = color)
            Text(text, Modifier.weight(1f), color = color)
        }
    }
    @Composable fun steps() {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                step = determineStep()
            }
            when (step) {
                1 -> card(stringResource(R.string.setup_enable_card, appName), null)
                2 -> card(stringResource(R.string.setup_switch_card), null)
                else -> card(stringResource(R.string.setup_done_title), stringResource(R.string.setup_done_hint))
            }
            row(SetupRow.ENABLE, stringResource(R.string.setup_step1_action), painterResource(R.drawable.ic_setup_key)) {
                val intent = Intent()
                intent.action = Settings.ACTION_INPUT_METHOD_SETTINGS
                intent.addCategory(Intent.CATEGORY_DEFAULT)
                launcher.launch(intent)
            }
            row(SetupRow.SWITCH, stringResource(R.string.setup_step2_action), painterResource(R.drawable.ic_setup_select),
                imm::showInputMethodPicker)
            // (no Dynamic colors before Android 12: nothing to pick there, the keyboard is Curmudgeon's)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                row(SetupRow.THEME, stringResource(R.string.setup_theme_title), painterResource(R.drawable.ic_settings_appearance)) { }
            // (no "(optional)": owner 2026-10-14)
            row(SetupRow.CONFIGURE, stringResource(R.string.setup_step3_action),
                painterResource(R.drawable.sym_keyboard_language_switch), close)
            if (step >= 3) DictionaryDownloads(stepBackgroundColor, textColor)
            // on every screen, faint until the keyboard is in use
            row(SetupRow.FINISH, stringResource(R.string.setup_finish_action), painterResource(R.drawable.ic_setup_check), finish)
        }
    }
    Surface {
        CompositionLocalProvider(
            LocalContentColor provides textColor,
            LocalTextStyle provides MaterialTheme.typography.titleLarge.merge(color = textColor),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (useWideLayout)
                    Row {
                        Box(Modifier.weight(0.4f)) {
                            bigText()
                        }
                        Box(Modifier.weight(0.6f)) {
                            steps()
                        }
                    }
                else
                    Column {
                        bigText()
                        steps()
                    }
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}

@Preview(
    // content cut off on real device, but not here... great?
    device = "spec:orientation=landscape,width=400dp,height=780dp"
)
@Composable
private fun WidePreview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}

/**
 * In the last step: the phone's languages that have a dictionary to download and none here yet, each a link to it
 * (owner 2026-10-10: "Should think about making downloads a part of keyboard onboarding flow"). Nothing when there are none.
 */
@Composable
private fun DictionaryDownloads(background: androidx.compose.ui.graphics.Color, textColor: androidx.compose.ui.graphics.Color) {
    val ctx = LocalContext.current
    val downloads = androidx.compose.runtime.remember {
        val system = androidx.core.os.ConfigurationCompat.getLocales(android.content.res.Resources.getSystem().configuration)
        (0 until system.size()).mapNotNull { system[it] }
            .filter { !helium314.keyboard.settings.screens.getUserAndInternalDictionaries(ctx, it).let { (own, internal) -> internal || own.isNotEmpty() } }
            .mapNotNull { locale -> helium314.keyboard.latin.utils.OnDemandDictionaries.forLocale(ctx, locale)?.let { locale to it } }
            .distinctBy { it.second.tag }
    }
    if (downloads.isEmpty()) return
    Spacer(Modifier.height(4.dp))
    Column(Modifier.background(color = background).padding(16.dp)) {
        Text(stringResource(R.string.setup_dictionaries_title))
        for ((locale, entry) in downloads) {
            val label = stringResource(R.string.dictionary_get_ours, entry.name,
                android.text.format.Formatter.formatShortFileSize(ctx, entry.bytes))
            Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp).clickable {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse(helium314.keyboard.latin.utils.OnDemandDictionaries.pageFor(locale)))) }
                })
        }
    }
}
