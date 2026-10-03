// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import java.text.SimpleDateFormat
import java.util.Calendar

fun getTimestamp(context: Context): String = getTimestampFormatter(context).format(Calendar.getInstance().time)

/** A popup key's output text starting with this types the time in the format after it, e.g. "Date|!timestamp/yyyy-MM-dd". */
const val TIMESTAMP_TEXT_PREFIX = "!timestamp/"

/** [text] with a timestamp format behind [TIMESTAMP_TEXT_PREFIX]: the time now in it (a broken format: the text as is). */
fun resolveTimestampText(text: String): String {
    if (!text.startsWith(TIMESTAMP_TEXT_PREFIX)) return text
    val format = text.substring(TIMESTAMP_TEXT_PREFIX.length)
    return runCatching { SimpleDateFormat(format, Settings.getValues().mLocale).format(Calendar.getInstance().time) }.getOrDefault(text)
}

fun getTimestampFormatter(context: Context): SimpleDateFormat {
    val format = context.prefs().getString(Settings.PREF_TIMESTAMP_FORMAT, Defaults.PREF_TIMESTAMP_FORMAT)
    return runCatching<SimpleDateFormat> { SimpleDateFormat(format, Settings.getValues().mLocale) }.getOrNull()
        ?: SimpleDateFormat(Defaults.PREF_TIMESTAMP_FORMAT, Settings.getValues().mLocale)
}

fun checkTimestampFormat(format: String) = runCatching { SimpleDateFormat(format, Settings.getValues().mLocale) }.isSuccess
