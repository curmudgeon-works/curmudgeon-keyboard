package helium314.keyboard.keyboard.emoji

import android.content.Context
import android.os.Build
import helium314.keyboard.latin.settings.Settings

object SupportedEmojis {
    /** each emoji's Android level (emoji/minApi.txt), read once */
    private val minApi = HashMap<String, Int>()
    /** the newest Android level in emoji/minApi.txt: at or above it every emoji shows */
    const val LATEST = 36

    /** The default: the emojis of this phone's Android version (newer ones would show as empty boxes in its font). */
    val DEFAULT get() = Build.VERSION.SDK_INT

    @Synchronized
    fun load(context: Context) {
        if (minApi.isNotEmpty()) return
        context.assets.open("emoji/minApi.txt").reader().readLines().forEach {
            val s = it.split(" ")
            val level = s.first().toIntOrNull() ?: return@forEach
            s.drop(1).forEach { emoji -> minApi[emoji] = level }
        }
    }

    /** Newer than the emoji version of the keyboard in use (a per-keyboard setting, so a switch changes it). */
    fun isUnsupported(emoji: String): Boolean {
        val level = minApi[emoji] ?: return false
        val max = Settings.getValues()?.mEmojiMaxSdk ?: DEFAULT
        return level > max
    }
}
