// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.settings.KeyboardScopeContext
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.settings.getTransitionAnimationScale
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.settings.screens.AboutScreen
import helium314.keyboard.settings.screens.AdvancedSettingsScreen
import helium314.keyboard.settings.screens.AppearanceScreen
import helium314.keyboard.settings.screens.ColorsScreen
import helium314.keyboard.settings.screens.DebugScreen
import helium314.keyboard.settings.screens.DictionaryScreen
import androidx.compose.runtime.getValue
import helium314.keyboard.settings.screens.KeyboardsScreen
import helium314.keyboard.settings.screens.LanguageListScreen
import helium314.keyboard.settings.screens.LanguageScreen
import helium314.keyboard.settings.screens.MainSettingsScreen
import helium314.keyboard.settings.screens.PersonalDictionariesScreen
import helium314.keyboard.settings.screens.PersonalDictionaryScreen
import helium314.keyboard.settings.screens.PreferencesScreen
import helium314.keyboard.settings.screens.SecondaryLayoutScreen
import helium314.keyboard.settings.screens.SubtypeScreen
import helium314.keyboard.settings.screens.SwipeTuningScreen
import helium314.keyboard.settings.screens.LayoutFilesScreen
import helium314.keyboard.settings.screens.CustomizePopupsScreen
import helium314.keyboard.settings.screens.TextCorrectionScreen
import helium314.keyboard.settings.screens.ToolbarScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun SettingsNavHost(
    onClickBack: () -> Unit,
    startDestination: String? = null,
    navController: NavHostController = rememberNavController(),
) {
    val dir = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val target = SettingsDestination.navTarget.collectAsState()

    // duration does not change when system setting changes, but that's rare enough to not care
    val duration = (250 * getTransitionAnimationScale(LocalContext.current)).toInt()
    val animation = tween<IntOffset>(durationMillis = duration)

    fun goBack() {
        if (!navController.popBackStack()) onClickBack()
    }

    NavHost(
        navController = navController,
        startDestination = startDestination ?: SettingsDestination.Keyboards,
        enterTransition = { slideInHorizontally(initialOffsetX = { +it * dir }, animationSpec = animation) },
        exitTransition = { slideOutHorizontally(targetOffsetX = { -it * dir }, animationSpec = animation) },
        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it * dir }, animationSpec = animation) },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { +it * dir }, animationSpec = animation) }
    ) {
        composable(SettingsDestination.Keyboards) { // (no keyboard of its own: the main choice)
            KeyboardsScreen(onClickBack = ::goBack)
        }
        screen(SettingsDestination.Languages + "{keyboard}") {
            LanguageListScreen(initialKeyboard = it.arguments?.getString("keyboard")!!.toSettingsSubtype(), onClickBack = ::goBack)
        }
        screen(SettingsDestination.Settings + "{keyboard}") {
            MainSettingsScreen(
                keyboard = it.arguments?.getString("keyboard")!!.toSettingsSubtype(),
                onClickBack = ::goBack,
            )
        }
        screen(SettingsDestination.About) {
            AboutScreen(onClickBack = ::goBack)
        }
        screen(SettingsDestination.TextCorrection) {
            androidx.compose.runtime.CompositionLocalProvider(LocalSettingsMenu provides helium314.keyboard.latin.settings.KeyboardProfiles.Group.TEXT_CORRECTION) { TextCorrectionScreen(onClickBack = ::goBack) }
        }
        screen(SettingsDestination.Preferences) {
            PreferencesScreen(onClickBack = ::goBack)
        }
        screen(SettingsDestination.Toolbar) {
            androidx.compose.runtime.CompositionLocalProvider(LocalSettingsMenu provides helium314.keyboard.latin.settings.KeyboardProfiles.Group.LAYOUT) { ToolbarScreen(onClickBack = ::goBack) }
        }
/*      will be added as part of passive data gathering
        composable(SettingsDestination.DataReview) {
            ReviewScreen(onClickBack = ::goBack)
        }*/
        screen(SettingsDestination.Advanced) {
            AdvancedSettingsScreen(onClickBack = ::goBack)
        }
        screen(SettingsDestination.LearningSwiping) {
            androidx.compose.runtime.CompositionLocalProvider(LocalSettingsMenu provides helium314.keyboard.latin.settings.KeyboardProfiles.Group.REFINE) { helium314.keyboard.settings.screens.LearningSwipingScreen(onClickBack = ::goBack) }
        }
        screen(SettingsDestination.Debug) {
            DebugScreen(onClickBack = ::goBack)
        }
        screen(SettingsDestination.Appearance) {
            androidx.compose.runtime.CompositionLocalProvider(LocalSettingsMenu provides helium314.keyboard.latin.settings.KeyboardProfiles.Group.APPEARANCE) { AppearanceScreen(onClickBack = ::goBack) }
        }
        screen(SettingsDestination.PersonalDictionary + "{locale}") {
            val locale = it.arguments?.getString("locale")?.takeIf { loc -> loc.isNotBlank() }?.constructLocale()
            PersonalDictionaryScreen(
                onClickBack = ::goBack,
                locale = locale
            )
        }
        screen(SettingsDestination.PersonalDictionaries) {
            PersonalDictionariesScreen(onClickBack = ::goBack)
        }
        screen(SettingsDestination.LearnedWords) {
            helium314.keyboard.settings.screens.LearnedWordsScriptsScreen(onClickBack = ::goBack)
        }
        screen(SettingsDestination.LearnedWordsOfScript + "{script}/{list}/{pool}") {
            helium314.keyboard.settings.screens.LearnedWordsScreen(onClickBack = ::goBack, script = it.arguments?.getString("script") ?: "",
                blacklisted = it.arguments?.getString("list") == helium314.keyboard.settings.screens.LIST_BLACKLISTED,
                // the keyboard whose own learned words these are, 0: shared by all (see LearnedStores)
                pool = it.arguments?.getString("pool")?.toIntOrNull() ?: 0)
        }
        screen(SettingsDestination.AllKeyboards) {
            // the list of all keyboards incl. disabled ones; the keyboards screen shows the enabled ones as entries
            LanguageScreen(onClickBack = ::goBack)
        }
        screen(SettingsDestination.Dictionaries) {
            DictionaryScreen(onClickBack = ::goBack)
        }
        screen(SettingsDestination.Layouts) {
            SecondaryLayoutScreen(onClickBack = ::goBack)
        }
        screen(SettingsDestination.CustomizePopups + "{subtype}") {
            CustomizePopupsScreen(keyboard = it.arguments?.getString("subtype")!!.toSettingsSubtype(), onClickBack = ::goBack)
        }
        screen(SettingsDestination.LayoutFiles + "{subtype}") {
            LayoutFilesScreen(keyboard = it.arguments?.getString("subtype")!!.toSettingsSubtype(), onClickBack = ::goBack)
        }
        screen(SettingsDestination.Colors + "{theme}") {
            ColorsScreen(isNight = false, theme = it.arguments?.getString("theme"), onClickBack = ::goBack)
        }
        screen(SettingsDestination.ColorsNight + "{theme}") {
            ColorsScreen(isNight = true, theme = it.arguments?.getString("theme"), onClickBack = ::goBack)
        }
        screen(SettingsDestination.Subtype + "{subtype}") {
            androidx.compose.runtime.CompositionLocalProvider(LocalSettingsMenu provides helium314.keyboard.latin.settings.KeyboardProfiles.Group.LAYOUT) { SubtypeScreen(initialSubtype = it.arguments?.getString("subtype")!!.toSettingsSubtype(), onClickBack = ::goBack) }
        }
        screen(SettingsDestination.SwipeTuning + "{subtype}") {
            androidx.compose.runtime.CompositionLocalProvider(LocalSettingsMenu provides helium314.keyboard.latin.settings.KeyboardProfiles.Group.SWIPE) { SwipeTuningScreen(keyboard = it.arguments?.getString("subtype")!!.toSettingsSubtype(), onClickBack = ::goBack) }
        }
    }
    // after a factory reset or a restore: the main screen, as the keyboards' set ids the screens above it name are gone or
    // belong to other keyboards now (their reads already fall back to the main choice, KeyboardProfiles.scopedId)
    val toMain = (LocalContext.current.getActivity() as? SettingsActivity)?.backToMain?.collectAsState()
    val toMainCount = toMain?.value ?: 0
    LaunchedEffect(toMainCount) {
        if (toMainCount > 0) navController.popBackStack(SettingsDestination.Keyboards, inclusive = false)
    }
    // once per new target, not on every redraw: the target stays set for 50 ms (navigateTo), and each redraw in that
    // time added one more copy of the screen, so Back needed a press per copy (2026-10-08, up to 6 on a 120 Hz phone)
    val route = target.value
    LaunchedEffect(route) {
        if (route != SettingsDestination.Keyboards) navController.navigate(route = route)
    }
}

/** A destination that can be one keyboard's: opened with [SettingsDestination.SET] in its route, its content edits that
 *  keyboard's set ([KeyboardScopeContext] as its context), however long it stays composed (a slide, a dialog's work). */
private fun NavGraphBuilder.screen(route: String, content: @Composable (NavBackStackEntry) -> Unit) =
    composable("$route?${SettingsDestination.SET}={${SettingsDestination.SET}}",
        arguments = listOf(navArgument(SettingsDestination.SET) { type = NavType.IntType; defaultValue = SettingsDestination.NO_SET })
    ) { entry ->
        KeyboardScope(SettingsDestination.setIdOf(entry)) { content(entry) }
    }

/** [content] edits keyboard set [keyboardId] (null: as it is): the context it gets is a [KeyboardScopeContext]. */
@Composable
fun KeyboardScope(keyboardId: Int?, content: @Composable () -> Unit) {
    if (keyboardId == null) return content()
    val base = LocalContext.current
    val scoped = remember(base, keyboardId) { KeyboardScopeContext(base, keyboardId) }
    CompositionLocalProvider(LocalContext provides scoped, content)
}

object SettingsDestination {
    const val Keyboards = "keyboards"
    const val Settings = "settings/"
    const val About = "about"
    const val TextCorrection = "text_correction"
    const val Preferences = "preferences"
    const val Toolbar = "toolbar"
    const val Advanced = "advanced"
    const val Debug = "debug"
    const val Appearance = "appearance"
    const val Colors = "colors/"
    const val ColorsNight = "colors_night/"
    const val PersonalDictionaries = "personal_dictionaries"
    const val PersonalDictionary = "personal_dictionary/"
    const val LearnedWords = "learned_words"
    const val LearnedWordsOfScript = "learned_words/"
    const val Languages = "languages/"
    const val AllKeyboards = "all_keyboards"
    const val Subtype = "subtype/"
    const val SwipeTuning = "swipe_tuning/"
    const val LearningSwiping = "learning_swiping"
    const val Layouts = "layouts"
    const val LayoutFiles = "layout_files/"
    const val CustomizePopups = "customize_popups/"
    const val Dictionaries = "dictionaries"
    val navTarget = MutableStateFlow(Keyboards)

    /** The route argument with the keyboard set a screen edits (a keyboard's own menus; absent: the main choice). */
    const val SET = "set"
    internal const val NO_SET = -1
    /** [route] for the screens of keyboard set [setId] (null: no keyboard's own). */
    fun inSet(route: String, setId: Int?): String = if (setId == null) route else "$route?$SET=$setId"
    /** The keyboard set of a back stack entry, or null. */
    fun setIdOf(entry: NavBackStackEntry?): Int? = entry?.arguments?.getInt(SET, NO_SET)?.takeIf { it != NO_SET }
    /** [target] opened from a screen with context [from]: it edits the same keyboard (Colors from that keyboard's
     *  Appearance, Learned words from its Text correction). */
    fun navigateTo(target: String, from: android.content.Context) = navigateTo(inSet(target, KeyboardScopeContext.idOf(from)))

    private val navScope = CoroutineScope(Dispatchers.Default)
    /** [route] for [keyboard]: its settings string encoded, as Navigation decodes the route's arguments (review
     *  2026-10-06: a renamed keyboard's name is stored encoded, and decoded once more it named no keyboard). */
    fun withKeyboard(route: String, keyboard: helium314.keyboard.latin.settings.SettingsSubtype): String =
        route + android.net.Uri.encode(keyboard.toPref())

    fun navigateTo(target: String) {
        if (navTarget.value == target) {
            // triggers recompose twice, but that's ok as it's a rare event
            navTarget.value = Keyboards
            navScope.launch { delay(10); navTarget.value = target }
        } else
            navTarget.value = target
        navScope.launch { delay(50); navTarget.value = Keyboards }
    }
}
