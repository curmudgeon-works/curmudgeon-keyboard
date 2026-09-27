// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs

/**
 * Android can silence the keyboard's key-press vibration and sound whatever our own switches say.
 * When it does, a line under the switch says so and opens the system page that turns it back on.
 */
object SystemFeedback {
    /**
     * The Android switches that stop our key-press vibration now, by name; empty when nothing does.
     * Measured on a Pixel (Android 16, dumpsys vibrator_manager): the normal key tap is the system's keyboard haptic
     * (usage IME) and plays even with touch feedback and keyboard vibration off; only the master switch stops it.
     * A custom vibration duration is a plain vibration (usage TOUCH), which touch feedback off does stop.
     */
    fun vibrationBlockers(ctx: Context): List<Int> {
        val cr = ctx.contentResolver
        fun off(key: String) = Settings.System.getInt(cr, key, 1) == 0
        val customDuration = ctx.prefs().getInt(helium314.keyboard.latin.settings.Settings.PREF_VIBRATION_DURATION_SETTINGS,
            helium314.keyboard.latin.settings.Defaults.PREF_VIBRATION_DURATION_SETTINGS) >= 0
        return listOfNotNull(
            if (off("vibrate_on")) R.string.system_vibration else null,
            if (customDuration && off(Settings.System.HAPTIC_FEEDBACK_ENABLED)) R.string.system_touch_feedback else null,
        )
    }

    /** Why our key sounds can't play now (see AudioAndHapticFeedbackManager), or null. */
    fun soundBlocker(ctx: Context): Int? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        if (Settings.Global.getInt(ctx.contentResolver, "zen_mode", 0) != 0) return R.string.system_sound_dnd
        // silent: both are needed (tested 2026-09-27: ring volume alone wasn't enough, whatever the key sound)
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return R.string.system_sound_silent
        // key sounds are system touch sounds: Android drops them while Tap & click sounds is off (checked on a Pixel)
        if (Settings.System.getInt(ctx.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 0) return R.string.system_sound_touch
        // key clicks play on the system stream, which follows the ring volume
        if (am.getStreamVolume(AudioManager.STREAM_SYSTEM) == 0) return R.string.system_sound_volume
        return null
    }

    fun openVibrationSettings(ctx: Context) {
        // Pixel and AOSP have a "Vibration & haptics" page without a public action; elsewhere the sound page holds it
        val page = Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$VibrationSettingsActivity"))
        if (!start(ctx, page)) openSoundSettings(ctx)
    }

    fun openSoundSettings(ctx: Context) {
        start(ctx, Intent(Settings.ACTION_SOUND_SETTINGS))
    }

    private fun start(ctx: Context, intent: Intent) =
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

/** [switch] with a tappable line below it when the user switches it on while Android keeps that feedback off.
 *  The line stays for this visit until switched off again or Android's setting is fixed (checked on every return). */
@Composable
fun SystemFeedbackNote(blocked: (Context) -> String?, open: (Context) -> Unit, switch: @Composable (onSwitched: (Boolean) -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var switchedOn by rememberSaveable { mutableStateOf(false) }
    var resumed by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column {
        switch { switchedOn = it }
        // cheap reads, done on every recomposition; reading `resumed` recomposes when the screen comes back from Android's settings
        val text = if (switchedOn && resumed >= 0) blocked(ctx) else null
        if (text != null)
            Text(
                text = text, // each says what's off and what to turn on
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable { open(ctx) }
                    .padding(start = if (LocalCompactPreferences.current) 10.dp else 12.dp, end = 12.dp, bottom = 8.dp),
            )
    }
}
