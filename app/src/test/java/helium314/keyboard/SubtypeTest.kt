package helium314.keyboard

import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_NORMAL
import helium314.keyboard.keyboard.internal.keyboard_parser.addLocaleKeyTextsToParams
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.POPUP_KEYS_LAYOUT
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodManager2::class
])
class SubtypeTest {
    private lateinit var latinIME: LatinIME
    private lateinit var params: KeyboardParams

    @BeforeTest fun setUp() {
        latinIME = Robolectric.setupService(LatinIME::class.java)
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
        params = KeyboardParams()
        params.mId = KeyboardLayoutSet.getFakeKeyboardId(KeyboardId.ELEMENT_ALPHABET)
        params.mPopupKeyTypes.add(POPUP_KEYS_LAYOUT)
        addLocaleKeyTextsToParams(latinIME, params, POPUP_KEYS_NORMAL)
    }

    @Test fun emptyAdditionalSubtypesResultsInEmptyList() {
        // avoid issues where empty string results in additional subtype for undefined locale
        val prefs = latinIME.prefs()
        // (owner 2026-10-11: keyboards have unique ids; every listed keyboard is in the added list, here only the
        // default stored at the first start: nothing else, no keyboard of an undefined locale)
        prefs.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, "").apply()
        val listed = storedEnabled(prefs)
        assertTrue(listed.all { it.keyboardId != null })
        assertEquals(listed, SubtypeSettings.getAdditionalSubtypes().map { it.toSettingsSubtype() })
        val from = SubtypeSettings.getResourceSubtypesForLocale("es".constructLocale()).first()

        // no change, and "changed" subtype actually is resource subtype -> still nothing added
        SubtypeUtilsAdditional.changeAdditionalSubtype(from.toSettingsSubtype(), from.toSettingsSubtype(), latinIME)
        assertEquals(listed, SubtypeSettings.getAdditionalSubtypes().map { it.toSettingsSubtype() })
    }

    @Test fun subtypeStaysEnabledOnEdits() {
        val prefs = latinIME.prefs()
        prefs.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, "").apply() // clear it for convenience

        // edit enabled resource subtype
        val from = SubtypeSettings.getResourceSubtypesForLocale("es".constructLocale()).first()
        // (the default keyboard stored at the first start stays first: owner 2026-10-11, one real default keyboard; the
        // first keyboard's own settings are the common ones)
        // (owner 2026-10-11: keyboards have unique ids; the added keyboard gets one and keeps it through its edits)
        val added = SubtypeSettings.addEnabledSubtype(prefs, from)
        assertEquals(from.toSettingsSubtype(), added.withoutIds())
        val to = added.withLayout(LayoutType.SYMBOLS, "symbols_arabic")
        SubtypeUtilsAdditional.changeAdditionalSubtype(added, to, latinIME)
        assertEquals(to, SubtypeSettings.getEnabledSubtypes(false).last().toSettingsSubtype())

        // change the new subtype to effectively be the same as original resource subtype: still the user's keyboard
        // (with its id it stays in the added list; before, it left it and became the built-in one)
        val toNew = to.withoutLayout(LayoutType.SYMBOLS)
        assertEquals(added, toNew)
        SubtypeUtilsAdditional.changeAdditionalSubtype(to, toNew, latinIME)
        assertTrue(toNew in SubtypeSettings.getAdditionalSubtypes().map { it.toSettingsSubtype() })
        assertEquals(toNew, SubtypeSettings.getEnabledSubtypes(false).last().toSettingsSubtype())
        assertEquals(added.keyboardId, SubtypeSettings.getEnabledSubtypes(false).last().toSettingsSubtype().keyboardId)
    }
}
