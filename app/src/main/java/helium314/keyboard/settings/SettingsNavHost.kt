// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.settings.getTransitionAnimationScale
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
) {
    val navController = rememberNavController()
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
        composable(SettingsDestination.Keyboards) {
            KeyboardsScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Languages + "{keyboard}") {
            LanguageListScreen(initialKeyboard = it.arguments?.getString("keyboard")!!.toSettingsSubtype(), onClickBack = ::goBack)
        }
        composable(SettingsDestination.Settings + "{keyboard}") {
            MainSettingsScreen(
                keyboard = it.arguments?.getString("keyboard")!!.toSettingsSubtype(),
                onClickBack = ::goBack,
            )
        }
        composable(SettingsDestination.About) {
            AboutScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.TextCorrection) {
            TextCorrectionScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Preferences) {
            PreferencesScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Toolbar) {
            ToolbarScreen(onClickBack = ::goBack)
        }
/*      will be added as part of passive data gathering
        composable(SettingsDestination.DataReview) {
            ReviewScreen(onClickBack = ::goBack)
        }*/
        composable(SettingsDestination.Advanced) {
            AdvancedSettingsScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.LearningSwiping) {
            helium314.keyboard.settings.screens.LearningSwipingScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Debug) {
            DebugScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Appearance) {
            AppearanceScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.PersonalDictionary + "{locale}") {
            val locale = it.arguments?.getString("locale")?.takeIf { loc -> loc.isNotBlank() }?.constructLocale()
            PersonalDictionaryScreen(
                onClickBack = ::goBack,
                locale = locale
            )
        }
        composable(SettingsDestination.PersonalDictionaries) {
            PersonalDictionariesScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.LearnedWords) {
            helium314.keyboard.settings.screens.LearnedWordsScriptsScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.LearnedWordsOfScript + "{script}/{list}/{pool}") {
            helium314.keyboard.settings.screens.LearnedWordsScreen(onClickBack = ::goBack, script = it.arguments?.getString("script") ?: "",
                blacklisted = it.arguments?.getString("list") == helium314.keyboard.settings.screens.LIST_BLACKLISTED,
                // the keyboard whose own learned words these are, 0: shared by all (see LearnedStores)
                pool = it.arguments?.getString("pool")?.toIntOrNull() ?: 0)
        }
        composable(SettingsDestination.AllKeyboards) {
            // the list of all keyboards incl. disabled ones; the keyboards screen shows the enabled ones as entries
            LanguageScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Dictionaries) {
            DictionaryScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Layouts) {
            SecondaryLayoutScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.CustomizePopups + "{subtype}") {
            CustomizePopupsScreen(keyboard = it.arguments?.getString("subtype")!!.toSettingsSubtype(), onClickBack = ::goBack)
        }
        composable(SettingsDestination.LayoutFiles + "{subtype}") {
            LayoutFilesScreen(keyboard = it.arguments?.getString("subtype")!!.toSettingsSubtype(), onClickBack = ::goBack)
        }
        composable(SettingsDestination.Colors + "{theme}") {
            ColorsScreen(isNight = false, theme = it.arguments?.getString("theme"), onClickBack = ::goBack)
        }
        composable(SettingsDestination.ColorsNight + "{theme}") {
            ColorsScreen(isNight = true, theme = it.arguments?.getString("theme"), onClickBack = ::goBack)
        }
        composable(SettingsDestination.Subtype + "{subtype}") {
            SubtypeScreen(initialSubtype = it.arguments?.getString("subtype")!!.toSettingsSubtype(), onClickBack = ::goBack)
        }
        composable(SettingsDestination.SwipeTuning + "{subtype}") {
            SwipeTuningScreen(keyboard = it.arguments?.getString("subtype")!!.toSettingsSubtype(), onClickBack = ::goBack)
        }
    }
    if (target.value != SettingsDestination.Keyboards/* && target.value != navController.currentBackStackEntry?.destination?.route*/)
        navController.navigate(route = target.value)
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

    private val navScope = CoroutineScope(Dispatchers.Default)
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
