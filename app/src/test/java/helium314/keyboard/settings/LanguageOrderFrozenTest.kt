// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LanguagePriority
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.LANGUAGE_ROW_TAG
import helium314.keyboard.settings.screens.LanguageListScreen
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Languages screen keeps its order while it is open (2026-10-24): a language set from Off to H stays where it is
 * (before, the list re-sorted at once and the row jumped to the top group, out of view); opened again, the list is
 * sorted as before, the language among the keyboard's own. And the screen has the try-it box the other screens have.
 * Fail before: the row moved to index 1 right after the tap (no try-it box: no "Try it here" field).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class LanguageOrderFrozenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val keyboard get() = available("en-US").withId(1)
    private var scenario: ActivityScenario<SettingsActivity>? = null

    @Before fun setUp() {
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        LearnedStores.init(ctx)
        useKeyboards(ctx, real, keyboard)
        KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }
    @After fun tearDown() {
        scenario?.close()
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        KeyboardProfiles.loadGroups(real)
    }

    private fun idle() { compose.waitForIdle(); shadowOf(Looper.getMainLooper()).idle() }

    private fun open(k: SettingsSubtype) {
        scenario?.close()
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity {
            it.setContent { LanguageListScreen(initialKeyboard = k, onClickBack = { }) } }
        idle()
    }

    /** The language rows composed now, top to bottom, by language tag. */
    private fun rows(): List<String> = compose.onAllNodes(SemanticsMatcher("a language row") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(LANGUAGE_ROW_TAG) == true
    }).fetchSemanticsNodes().sortedBy { it.positionInRoot.y }.map { it.config[SemanticsProperties.TestTag].removePrefix(LANGUAGE_ROW_TAG) }

    // the keyboard as stored now (the screen changed it)
    private fun stored() = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }.first { it.keyboardId == keyboard.keyboardId }

    @Test fun `Off to H leaves the row where it is, the next open sorts it to the top`() {
        open(keyboard)
        val before = rows()
        assertTrue(before.size > 3, "rows composed: $before")
        assertEquals("en-US", before[0]) // the keyboard's own language first
        val index = 2 // a Latin-script language among the others (the same script comes first there)
        val target = before[index]
        compose.onAllNodesWithText(ctx.getString(R.string.language_priority_high))[index].performClick()
        idle()
        assertEquals(LanguagePriority.HIGH, LanguagePriority.get(DeviceProtectedUtils.getSharedPreferences(ctx), Locale.forLanguageTag(target)), "stored")
        assertTrue(Locale.forLanguageTag(target) in stored().let { listOf(it.locale) + helium314.keyboard.latin.utils.getSecondaryLocales(it.extraValues) }, "on the keyboard")
        // the row is still where it was, the rows around it too, and shows H
        val after = rows()
        assertEquals(before.take(index + 2), after.take(index + 2), "order while the screen is open")
        compose.onAllNodesWithText(ctx.getString(R.string.language_priority_high))[index].assertIsSelected()
        // opened again: sorted as before, the language among the keyboard's own (both H: English first, added first)
        open(stored())
        val reopened = rows()
        assertEquals(1, reopened.indexOf(target), "order on the next open: $reopened")
    }

    @Test fun `the screen has the try-it box`() {
        open(keyboard)
        compose.onNodeWithText(ctx.getString(R.string.key_popups_try)).assertExists()
    }
}
