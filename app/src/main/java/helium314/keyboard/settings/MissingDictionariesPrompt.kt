// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.getKnownDictionariesForLocale
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.settings.screens.getUserAndInternalDictionaries
import java.util.Locale

/**
 * The phone's languages that have no dictionary (18 are built in), each with Download (the browser fetches
 * the file from the dictionaries repository: the keyboard has no internet access) and Import (pick the downloaded
 * file). Nothing is shown when every phone language has one.
 */
@Composable
fun MissingDictionariesPrompt(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val missing = remember {
        val phone = ConfigurationCompat.getLocales(ctx.resources.configuration)
        (0 until phone.size()).mapNotNull { phone[it] }.distinctBy { it.language }.mapNotNull { locale ->
            val (userDicts, hasInternal) = getUserAndInternalDictionaries(ctx, locale)
            if (hasInternal || userDicts.isNotEmpty()) return@mapNotNull null
            // the main dictionary to offer: the regular one if there is one, else the experimental one
            val links = getKnownDictionariesForLocale(locale, ctx).map { it.second }.filter { it.contains("/main_") }
            val link = links.firstOrNull { !it.contains("experimental") } ?: links.firstOrNull() ?: return@mapNotNull null
            locale to link
        }
    }
    if (missing.isEmpty()) return
    Column(modifier) {
        Text(stringResource(R.string.setup_missing_dictionaries), style = MaterialTheme.typography.bodyLarge)
        for ((locale, link) in missing) MissingDictionaryRow(locale, link)
    }
}

@Composable
private fun MissingDictionaryRow(locale: Locale, link: String) {
    val ctx = LocalContext.current
    val importer = dictionaryFilePicker(locale)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(locale.localizedDisplayName(ctx.resources), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } }) {
            Text(stringResource(R.string.setup_dictionary_download))
        }
        TextButton({
            importer.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
        }) { Text(stringResource(R.string.setup_dictionary_import)) }
    }
}
