// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The phone's language list, as a test sets it. */
@Implements(LocaleManagerCompat::class)
object ShadowPhoneLanguages {
    var locales: List<Locale> = listOf(Locale.US)
    @Implementation
    @JvmStatic
    fun getSystemLocales(context: Context): LocaleListCompat = LocaleListCompat.create(*locales.toTypedArray())
}

/**
 * Owner 2026-10-11: at the first start one real default keyboard is stored in the list (no "list empty, a default is
 * shown"): the first of the phone's languages the app has a built-in dictionary for, else English (US). It is an
 * ordinary keyboard whose own settings are the main ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowPhoneLanguages::class])
class DefaultKeyboardTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)

    private fun firstStart(vararg phone: Locale) {
        real.edit().clear().commit()
        ShadowPhoneLanguages.locales = phone.toList()
        SubtypeSettings.init(ctx)
    }
    private fun stored(): List<SettingsSubtype> =
        SubtypeSettings.createSettingsSubtypes(real.getString(Settings.PREF_ENABLED_SUBTYPES, "")!!)

    @Before fun setUp() { KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() {
        ShadowPhoneLanguages.locales = listOf(Locale.US)
        SubtypeSettings.init(ctx)
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    @Test fun `the first start stores the phone's first language that has a built-in dictionary`() {
        // Thai has keyboards but no built-in dictionary: skipped (before, the list stayed empty and showed it; was Korean,
        // built in since 2026-10-18)
        firstStart(Locale.forLanguageTag("th"), Locale.FRANCE)
        assertEquals(listOf("fr"), stored().map { it.locale.language })
        assertEquals(stored().single().toPref(), real.getString(Settings.PREF_SELECTED_SUBTYPE, null))
        assertEquals(stored(), SubtypeSettings.getEnabledSubtypes(false).map { with(SettingsSubtype) { it.toSettingsSubtype() } })
        // (owner 2026-10-11: keyboards have unique ids; the default is stored with a new one, in the added list too)
        assertNotNull(stored().single().keyboardId)
        assertTrue(stored().single() in SubtypeSettings.createSettingsSubtypes(real.getString(Settings.PREF_ADDITIONAL_SUBTYPES, "")!!))
    }

    @Test fun `a language-level dictionary counts`() {
        firstStart(Locale.forLanguageTag("de-AT"))
        assertEquals(listOf("de"), stored().map { it.locale.language })
    }

    @Test fun `no phone language with a built-in dictionary - English (US)`() {
        firstStart(Locale.forLanguageTag("th"), Locale.JAPAN) // (Korean is built in since 2026-10-18)
        assertEquals(listOf(Locale.US), stored().map { it.locale })
    }

    @Test fun `the default is an ordinary keyboard, its own settings the main ones`() {
        firstStart(Locale.FRANCE)
        val default = stored().single()
        assertEquals(default, KeyboardProfiles.mainKeyboard(real))
        ProfilePreferences(real) { KeyboardProfiles.SHARED }.edit().putFloat(Settings.PREF_KEY_HORIZONTAL_GAP, 3f).commit()
        assertEquals(3f, real.getFloat(KeyboardProfiles.ownKey(default.keyboardId!!, Settings.PREF_KEY_HORIZONTAL_GAP), 0f))
    }

    @Test fun `a stored list is left as it is, and one found empty gets the default again`() {
        firstStart(Locale.FRANCE)
        val firstId = stored().single().keyboardId
        val de = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMAN).first()
        SubtypeSettings.addEnabledSubtype(real, de)
        assertEquals(listOf("fr", "de"), stored().map { it.locale.language }) // (the default stays first)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        assertEquals(listOf("fr", "de"), stored().map { it.locale.language })
        real.edit().putString(Settings.PREF_ENABLED_SUBTYPES, "").commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        assertEquals(listOf("fr"), stored().map { it.locale.language })
        // (a new keyboard: a new id, 2026-10-11)
        assertNotNull(stored().single().keyboardId)
        assertNotEquals(firstId, stored().single().keyboardId)
    }
}
