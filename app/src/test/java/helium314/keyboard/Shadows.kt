// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import com.android.inputmethod.latin.utils.BinaryDictionaryUtils
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.common.StringUtils
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInputMethodManager
import java.util.*

@Implements(LocaleManagerCompat::class)
object ShadowLocaleManagerCompat {
    @Implementation
    @JvmStatic
    fun getSystemLocales(context: Context) = LocaleListCompat.create(Locale.ENGLISH)
}

// why doesn't the original ShadowInputMethodManager simply work?
@Implements(InputMethodManager::class)
class ShadowInputMethodManager2 : ShadowInputMethodManager() {
    @Implementation
    override fun getInputMethodList() = listOfNotNull(
        if (BuildConfig.BUILD_TYPE == "debug" || BuildConfig.BUILD_TYPE == "debugNoMinify")
            InputMethodInfo("helium314.keyboard.debug", "LatinIME", "HeliBoard debug", null)
        else InputMethodInfo("helium314.keyboard", "LatinIME", "HeliBoard", null),
        ownKeyboard(),
    )
    // our keyboard enabled in Android, as a test says (the setup's steps): the one shadow for every test, as
    // robolectric.properties asks (a class with a shadow of its own trips on the leftovers of the class before)
    @Implementation
    override fun getEnabledInputMethodList() = listOfNotNull(ownKeyboard())
    @Implementation
    fun getShortcutInputMethodsAndSubtypes() = emptyMap<InputMethodInfo, List<InputMethodSubtype>>()
    private fun ownKeyboard(): InputMethodInfo? {
        if (!ownKeyboardEnabled) return null
        val ctx: Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        return InputMethodInfo(ctx.packageName, helium314.keyboard.latin.LatinIME::class.java.name, "Curmudgeon Keyboard", null)
    }
    companion object {
        /** Whether Android lists our keyboard as installed and enabled (false: not even installed, the setup's screen 1). */
        var ownKeyboardEnabled = false
    }
}

@Implements(BinaryDictionaryUtils::class)
object ShadowBinaryDictionaryUtils {
    @Implementation
    @JvmStatic
    fun calcNormalizedScore(beforeString: String, afterString: String, score: Int): Float {
        val before = StringUtils.toCodePointArray(beforeString)
        val after = StringUtils.toCodePointArray(afterString)
        val distance = editDistance(beforeString, afterString)
        val beforeLength = before.size
        val afterLength = after.size
        if (0 == beforeLength || 0 == afterLength) return 0.0f
        var spaceCount = 0
        for (j: Int in after) {
            if (j == KeyEvent.KEYCODE_SPACE) ++spaceCount
        }
        if (spaceCount == afterLength) return 0.0f
        if (score <= 0 || distance >= afterLength) {
            // normalizedScore must be 0.0f (the minimum value) if the score is less than or equal to 0,
            // or if the edit distance is larger than or equal to afterLength.
            return 0.0f
        }
        // add a weight based on edit distance.
        val weight = 1.0f - distance.toFloat() / afterLength.toFloat()
        return score.toFloat() / 1000000.0f * weight
    }

    private fun editDistance(x: String, y: String): Int {
        val dp = Array(x.length + 1) {
            IntArray(
                y.length + 1
            )
        }
        for (i in 0..x.length) {
            for (j in 0..y.length) {
                if (i == 0) {
                    dp[i][j] = j
                } else if (j == 0) {
                    dp[i][j] = i
                } else {
                    dp[i][j] = min(
                        dp[i - 1][j - 1]
                                + costOfSubstitution(x[i - 1], y[j - 1]),
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1
                    )
                }
            }
        }
        return dp[x.length][y.length]
    }

    private fun min(vararg numbers: Int): Int {
        var min = Int.MAX_VALUE
        for (n: Int in numbers) {
            if (n < min) min = n
        }
        return min
    }

    private fun costOfSubstitution(a: Char, b: Char): Int {
        return if (a == b) 0 else 1
    }

}
