// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.launch

/** The setup, always in Curmudgeon's Black with orange (2026-10-10; it had the phone's Dynamic colors, blue before
 *  Android 12), whatever colors the keyboard has. */
@Composable
fun WelcomeWizard(
    close: () -> Unit,
    finish: () -> Unit
) = MaterialTheme(colorScheme = helium314.keyboard.latin.utils.AppLook.curmudgeonScheme, typography = MaterialTheme.typography) {
    WelcomeWizardSteps(close, finish)
}

@Composable
private fun WelcomeWizardSteps(
    close: () -> Unit,
    finish: () -> Unit
) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    fun determineStep(): Int = when {
        !UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm) -> 0
        !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm) -> 2
        else -> 3
    }
    var step by rememberSaveable { mutableIntStateOf(determineStep()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(step) {
        if (step == 2)
            scope.launch {
                while (step == 2 && !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)) {
                    delay(50)
                }
                step = 3
            }
    }
    val useWideLayout = isWideScreen()
    // black, the steps a shade lighter, the title and what to tap in orange
    val stepBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = MaterialTheme.colorScheme.primary
    val textColorDim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val titleColor = MaterialTheme.colorScheme.primary
    val appName = stringResource(ctx.applicationInfo.labelRes)
    @Composable fun bigText() {
        val resource = if (step == 0) R.string.setup_welcome_title else R.string.setup_steps_title
        Column(Modifier.padding(bottom = 36.dp)) {
            Text(
                stringResource(resource, appName),
                style = MaterialTheme.typography.displayMedium,
                textAlign = TextAlign.Center,
                color = titleColor,
            )
        }
    }
    @Composable
    fun ColumnScope.Step(step: Int, title: String, instruction: String, actionText: String, icon: Painter, action: () -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1", color = if (step == 1) titleColor else textColorDim)
            Text("2", color = if (step == 2) titleColor else textColorDim)
            Text("3", color = if (step == 3) titleColor else textColorDim)
        }
        Column(Modifier
            .background(color = stepBackgroundColor)
            .padding(16.dp)
        ) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            Text(instruction, style = MaterialTheme.typography.bodyLarge.merge(color = MaterialTheme.colorScheme.onSurfaceVariant))
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.clickable { action() }
                .background(color = stepBackgroundColor)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.padding(end = 6.dp).size(32.dp), tint = textColor)
            Text(actionText, Modifier.weight(1f))
        }
    }
    @Composable fun steps() {
        if (step == 0)
            Step0 { step = 1 }
        else
            Column {
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    step = determineStep()
                }
                if (step == 1) {
                    Step(
                        step,
                        stringResource(R.string.setup_step1_title, appName),
                        stringResource(R.string.setup_step1_instruction, appName),
                        stringResource(R.string.setup_step1_action),
                        painterResource(R.drawable.ic_setup_key)
                    ) {
                        val intent = Intent()
                        intent.action = Settings.ACTION_INPUT_METHOD_SETTINGS
                        intent.addCategory(Intent.CATEGORY_DEFAULT)
                        launcher.launch(intent)
                    }
                } else if (step == 2) {
                    Step(
                        step,
                        stringResource(R.string.setup_step2_title, appName),
                        stringResource(R.string.setup_step2_instruction, appName),
                        stringResource(R.string.setup_step2_action),
                        painterResource(R.drawable.ic_setup_select),
                        imm::showInputMethodPicker
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.clickable { close() }
                            .background(color = stepBackgroundColor)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(R.drawable.sym_keyboard_language_switch),
                            null,
                            Modifier.padding(end = 6.dp).size(32.dp),
                            tint = textColor
                        )
                        Text(stringResource(R.string.setup_step3_action), Modifier.weight(1f))
                    }
                } else { // step 3
                    Step(
                        step,
                        stringResource(R.string.setup_step3_title),
                        stringResource(R.string.setup_step3_instruction, appName),
                        stringResource(R.string.setup_step3_action),
                        painterResource(R.drawable.sym_keyboard_language_switch),
                        close
                    )
                    DictionaryDownloads(stepBackgroundColor, textColor)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.clickable { finish() }
                            .background(color = stepBackgroundColor)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_setup_check),
                            null,
                            Modifier.padding(end = 6.dp).size(32.dp),
                            tint = textColor
                        )
                        Text(stringResource(R.string.setup_finish_action), Modifier.weight(1f))
                    }
                }
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

@Composable
fun Step0(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.setup_welcome_image), null)
        Row(Modifier.clickable { onClick() }
            .padding(top = 4.dp, start = 4.dp, end = 4.dp)
            //.background(color = MaterialTheme.colorScheme.primary)
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.setup_start_action),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
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
