package helium314.keyboard.keyboard.emoji

import android.content.Context
import android.os.Build
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

object SupportedEmojis {
    private val unsupportedEmojis = hashSetOf<String>()
    /** the newest Android level in emoji/minApi.txt: at or above it every emoji shows */
    const val LATEST = 36

    /** The default: the emojis of this phone's Android version (newer ones would show as empty boxes in its font). */
    val DEFAULT get() = Build.VERSION.SDK_INT

    fun load(context: Context) {
        val maxSdk = context.prefs().getInt(Settings.PREF_EMOJI_MAX_SDK, DEFAULT)
        unsupportedEmojis.clear()
        context.assets.open("emoji/minApi.txt").reader().readLines().forEach {
            val s = it.split(" ")
            val minApi = s.first().toInt()
            if (minApi > maxSdk)
                unsupportedEmojis.addAll(s.drop(1))
        }
    }

    fun isUnsupported(emoji: String) = emoji in unsupportedEmojis
}
