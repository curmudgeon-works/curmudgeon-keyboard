// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.*
import androidx.core.content.edit
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.ShadowFacilitator2.Companion.lastAddedWord
import helium314.keyboard.latin.ShadowFacilitator2.Companion.unlearnedWords
import helium314.keyboard.latin.ShadowFacilitator2.Companion.addedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.common.StringUtils
import helium314.keyboard.latin.inputlogic.InputLogic
import helium314.keyboard.latin.inputlogic.SpaceState
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.TIMESTAMP_FORMAT
import helium314.keyboard.latin.utils.prefs
import org.junit.Ignore
import kotlin.test.assertNotEquals
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLog
import java.util.*
import kotlin.math.min
import kotlin.streams.asSequence
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowLocaleManagerCompat::class,
    ShadowInputMethodManager2::class,
    ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class,
    ShadowHandler::class,
    ShadowFacilitator2::class,
])
class InputLogicTest {
    private lateinit var latinIME: LatinIME
    private val settingsValues get() = Settings.getValues()
    private val inputLogic get() = latinIME.mInputLogic
    private val connection: RichInputConnection get() = inputLogic.mConnection
    private val composerReader = InputLogic::class.java.getDeclaredField("mWordComposer").apply { isAccessible = true }
    private val composer get() = composerReader.get(inputLogic) as WordComposer
    private val spaceStateReader = InputLogic::class.java.getDeclaredField("mSpaceState").apply { isAccessible = true }
    private val spaceState get() = spaceStateReader.get(inputLogic) as Int
    private val beforeComposingReader = RichInputConnection::class.java.getDeclaredField("mCommittedTextBeforeComposingText").apply { isAccessible = true }
    private val connectionTextBeforeComposingText get() = (beforeComposingReader.get(connection) as CharSequence).toString()
    private val composingReader = RichInputConnection::class.java.getDeclaredField("mComposingText").apply { isAccessible = true }
    private val connectionComposingText get() = (composingReader.get(connection) as CharSequence).toString()

    @BeforeTest
    fun setUp() {
        latinIME = Robolectric.setupService(LatinIME::class.java)
        // start logging only after latinIME is created, avoids showing the stack traces if library is not found
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
    }

    @Test fun inputCode() {
        reset()
        input('c')
        assertEquals("c", textBeforeCursor)
        assertEquals("c", getText())
        assertEquals("", textAfterCursor)
        assertEquals("c", composingText)
        latinIME.mHandler.onFinishInput()
        assertEquals("", composingText)
    }

    // hold-backspace after the editor left the cursor position unknown (a chat app's composer rewriting its text):
    // every repeat tick must take a word, not only the first (d2cc6ea0 fixed the first, the connection's delete
    // bookkeeping then claimed position 0 and the second tick fell back to a letter)
    @Test fun `hold backspace with unknown cursor deletes a word on every tick`() {
        reset()
        setText("hello there world ")
        val start = RichInputConnection::class.java.getDeclaredField("mExpectedSelStart").apply { isAccessible = true }
        val end = RichInputConnection::class.java.getDeclaredField("mExpectedSelEnd").apply { isAccessible = true }
        start.setInt(connection, -1); end.setInt(connection, -1)
        repeatBackspace()
        assertEquals("hello there ", text)
        repeatBackspace() // the fake editor sends no selection update between ticks
        assertEquals("hello ", text)
    }

    // cursor dropped in the middle of a word, backspace held: the part of the word before the cursor goes at once
    @Test fun `hold backspace with the cursor inside a word deletes the part before the cursor`() {
        reset()
        setText("hello wonderful world")
        setCursorPosition(10) // after "hello wond"
        repeatBackspace()
        assertEquals("hello erful world", text)
    }

    private fun repeatBackspace() {
        latinIME.onEvent(Event.createSoftwareKeypressEvent(Event.NOT_A_CODE_POINT, KeyCode.DELETE, 0, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, true))
        handleMessages()
    }

    @Test fun delete() {
        reset()
        setText("hello there ")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello there", text)
        assertEquals("there", composingText)
    }

    // a digit mistapped inside a word ("Ha0py" for happy): space + backspace brings back the whole word for its
    // suggestions, not just "py" after the digit
    @Test fun `delete after a word with a digit resumes the whole word`() {
        reset()
        chainInput("Ha0py ")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("Ha0py", text)
        assertEquals("Ha0py", composingText)
        assertEquals("Ha0py", composer.typedWord)
    }

    // a single digit mistapped at the start of a word ("3stimate" for estimate) becomes part of the composing word
    // once 3 letters follow it, so suggestions are for the whole word
    @Test fun `lone digit before 3 letters is composed with the word`() {
        reset()
        chainInput("3st")
        assertEquals("3st", text)
        assertEquals("st", composingText)
        input('i')
        assertEquals("3sti", text)
        assertEquals("3sti", composingText)
        assertEquals("3sti", composer.typedWord)
        chainInput("mate")
        assertEquals("3stimate", text)
        assertEquals("3stimate", composingText)
        assertEquals("3stimate", composer.typedWord)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("3stimat", composingText)
    }

    @Test fun `lone digit before 3 letters is composed after a space`() {
        reset()
        chainInput("so 3stimate")
        assertEquals("so 3stimate", text)
        assertEquals("3stimate", composingText)
    }

    @Test fun `delete after a word starting with a lone digit resumes the whole word`() {
        reset()
        chainInput("so 3stimate ")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("so 3stimate", text)
        assertEquals("3stimate", composingText)
        assertEquals("3stimate", composer.typedWord)
    }

    @Test fun `digit before fewer than 3 letters or more digits stays out of the word`() {
        reset()
        chainInput("5pm")
        assertEquals("pm", composingText)
        input(' ')
        assertEquals("5pm ", text)
        reset()
        chainInput("100mph")
        assertEquals("100mph", text)
        assertEquals("mph", composingText)
        reset()
        chainInput("10:3stimate")
        assertEquals("10:3stimate", text)
        assertEquals("stimate", composingText)
    }

    @Test fun deleteInsideWord() {
        reset()
        setText("hello you there")
        setCursorPosition(8) // after o in you
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello yu there", text)
        assertEquals("yu", composingText)
    }

    @Test fun insertLetterIntoWord() {
        reset()
        setText("hello")
        setCursorPosition(3) // after first l
        input('i')
        assertEquals("helilo", getWordAtCursor())
        assertEquals("helilo", getText())
        assertEquals(4, getCursorPosition())
        assertEquals(4, cursor)
        assertEquals("", composingText)
    }

    @Test fun insertLetterIntoWordWithWeirdEditor() {
        reset()
        currentInputType = 180225 // should not change much, but just to be sure
        setText("hello")
        setCursorPosition(3, weirdTextField = true) // after first l
        input('i')
        assertEquals("helilo", getWordAtCursor())
        assertEquals("helilo", getText())
        assertEquals(4, getCursorPosition())
        assertEquals(4, cursor)
    }

    @Test fun insertLetterIntoOneOfSeveralWords() {
        reset()
        setText("hello my friend")
        setCursorPosition(7) // between m and y
        input('a')
        assertEquals("may", getWordAtCursor())
        assertEquals("hello may friend", getText())
        assertEquals(8, getCursorPosition())
        assertEquals(8, cursor)
    }

    @Test fun combineHangul() {
        reset()
        val ko = SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first()
        latinIME.switchToSubtype(ko)
        chainInput("ㅂㄱㅑ")
        assertEquals("ㅂ갸", text)
    }

    // todo: make it work, but it might not be that simple because adding is done in combiner
    //  https://github.com/HeliBorg/HeliBoard/issues/214
    @Ignore("HeliBoard known failure, issue 214 (Hangul combining); skipped like in their runTests build")
    @Test fun insertLetterIntoWordHangulFails() {
        reset()
        latinIME.switchToSubtype(SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first())
        chainInput("ㅛㅎㄹㅎㅕㅛ")
        setCursorPosition(3)
        input('ㄲ') // fails, as expected from the hangul issue when processing the event in onCodeInput
        assertEquals("ㅛㅎㄹㄲ혀ㅛ", getWordAtCursor())
        assertEquals("ㅛㅎㄹㄲ혀ㅛ", getText())
        assertEquals("ㅛㅎㄹㄲ혀ㅛ", textBeforeCursor + textAfterCursor)
        assertEquals(4, getCursorPosition())
        assertEquals(4, cursor)
    }

    // see issue 1447
    @Test fun separatorAfterHangul() {
        reset()
        latinIME.switchToSubtype(SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first())
        chainInput("ㅛ.")
        assertEquals("ㅛ.", text)
    }

    @Test fun deleteHangulInDebugMode() { // issue 1551, later only happened on phone
        reset()
        latinIME.switchToSubtype(SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first())
        setText("ㅛㅛ ")
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
    }

    @Test fun separatorUnselectsWord() {
        reset()
        setText("hello")
        assertEquals("hello", composingText)
        input('.')
        assertEquals("", composingText)
    }

    @Test fun autospace() {
        reset()
        setText("hello")
        input('.')
        input('a')
        assertEquals("hello.a", textBeforeCursor)
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        setText("hello")
        input('.')
        input('a')
        assertEquals("hello. a", textBeforeCursor)
    }

    @Test fun autospaceButWithTextAfter() {
        reset()
        setText("hello there")
        setCursorPosition(5) // after hello
        input('.')
        input('a')
        assertEquals("hello.a", textBeforeCursor)
        assertEquals("hello.a there", text)
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        setText("hello there")
        setCursorPosition(5) // after hello
        input('.')
        input('a')
        assertEquals("hello. a", textBeforeCursor)
        assertEquals("hello. a there", text)
    }

    @Test fun noAutospaceInUrlField() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("example.net")
        assertEquals("example. net", text)
        lastAddedWord = ""
        setText("")
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("example.net")
        assertEquals("", lastAddedWord)
        assertEquals("example.net", text)
        assertEquals("example.net", composingText)
    }

    @Test fun noAutospaceInUrlFieldWhenPickingSuggestion() {
        reset()
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("exam")
        pickSuggestion("example")
        assertEquals("example", text)
        input('.')
        assertEquals("example.", text)
    }

    @Test fun noAutospaceForDetectedUrl() { // "light" version, should work without url detection
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("http://example.net")
        assertEquals("http://example.net", text)
        assertEquals("http", lastAddedWord)
        assertEquals("example.net", composingText)
    }

    @Test fun noAutospaceForDetectedEmail() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, false) } // (ours is on)
        chainInput("mail@example.com")
        assertEquals("mail@example.com", text)
        assertEquals("mail@example", lastAddedWord) // todo: do we want this? not really nice, but don't want to be too aggressive with URL detection disabled
        assertEquals("com", composingText) // todo: maybe this should still see the whole address as a single word? or don't be too aggressive?
        setText("")
        lastAddedWord = ""
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("mail@example.com")
        assertEquals("", lastAddedWord)
        assertEquals("mail@example.com", composingText)
    }

    // learning (2026-10-04): a space learns letters with ' - . between them and email addresses; a strip tap
    // learns anything typed without a space
    @Test fun `space learns words, words with full stops and email addresses`() {
        for (word in listOf("hello.wrold", "don't", "well-known", "google.com", "sender.first.last@gmail.com",
                "first_last@x.co.uk", "user2024@gmail.com")) {
            reset()
            lastAddedWord = ""
            chainInput("$word ")
            assertEquals(word, lastAddedWord)
        }
    }

    @Test fun `space doesn't learn digits, other symbols or the end of a longer run`() {
        for (word in listOf("user2024", "ha0py", "3stimate", "user:pass@host.com", "sender+tag@gmail.com")) {
            reset()
            lastAddedWord = ""
            chainInput("$word ")
            assertEquals("$word ", text)
            assertNotEquals(word, lastAddedWord)
            assertNotEquals("tag@gmail.com", lastAddedWord)
        }
    }

    @Test fun `strip tap learns anything without a space`() {
        reset()
        lastAddedWord = ""
        chainInput("user2024")
        pickSuggestion("user2024")
        assertEquals("user2024", lastAddedWord)
    }

    @Test fun `the whole run tapped in the strip is learned, the text stays`() {
        reset()
        lastAddedWord = ""
        chainInput("sender+tag@gmail.com")
        assertEquals("tag@gmail.com", composingText)
        val info = SuggestedWordInfo("sender+tag@gmail.com", "", 0, SuggestedWordInfo.KIND_WHOLE_RUN, null, 0, 0)
        latinIME.pickSuggestionManually(info)
        assertEquals("sender+tag@gmail.com", lastAddedWord)
        assertEquals("sender+tag@gmail.com", text.trimEnd())
    }

    // auto-space after a full stop, taken back for addresses (2026-10-04)
    private fun autospaceOn() = latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }

    @Test fun `full stop auto-space is taken back at the at sign and put back by backspace`() {
        reset()
        autospaceOn()
        lastAddedWord = ""
        chainInput("sender.first.last")
        assertEquals("sender. first. last", text)
        inputRewriting('@')
        assertEquals("sender.first.last@", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("sender. first. last", text)
        inputRewriting('@')
        chainInput("gmail.com ")
        assertEquals("sender.first.last@gmail.com ", text)
        assertEquals("sender.first.last@gmail.com", lastAddedWord)
    }

    @Test fun `full stop auto-space is taken back after a web ending`() {
        reset()
        autospaceOn()
        lastAddedWord = ""
        chainInput("google.com")
        assertEquals("google. com", text)
        inputRewriting(' ')
        assertEquals("google.com ", text)
        assertEquals("google.com", lastAddedWord)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("google. com", text)
    }

    @Test fun `no full stop auto-space after a single letter, between digits, or after www`() {
        for ((typed, expected) in listOf("e.g.x" to "e.g.x", "U.S.A" to "U.S.A", "v2.0" to "v2.0", "3.14" to "3.14",
                "www.google.com" to "www.google.com", "hello.how" to "hello. how")) {
            reset()
            autospaceOn()
            chainInput(typed)
            assertEquals(expected, text)
        }
    }

    @Test fun `no full stop auto-space after the at sign, up to 5 full stops`() {
        reset()
        autospaceOn()
        chainInput("x@aa.bb.cc.dd.ee.ff") // URL detection on: one word, never a space inside
        assertEquals("x@aa.bb.cc.dd.ee.ff", text)
        reset()
        autospaceOn()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, false) }
        chainInput("x@aa.bb.cc.dd.ee.ff")
        assertEquals("x@aa.bb.cc.dd.ee. ff", text)
    }

    @Test fun urlDetectionThings() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("...h")
        assertEquals("...h", text)
        assertEquals("h", composingText)
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("bla..")
        assertEquals("bla..", text)
        assertEquals("", composingText)
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("bla.c")
        assertEquals("bla.c", text)
        assertEquals("bla.c", composingText)
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        latinIME.prefs().edit { putBoolean(Settings.PREF_SHIFT_REMOVES_AUTOSPACE, true) }
        input("bla")
        input('.')
        functionalKeyPress(KeyCode.SHIFT) // should remove the phantom space (in addition to normal effect)
        input('c')
        assertEquals("bla.c", text)
        assertEquals("bla.c", composingText)
    }

    @Test fun stripSeparatorsBeforeAddingToHistoryWithURLDetection() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("example.com.")
        assertEquals("example.com.", composingText)
        input(' ')
        assertEquals("example.com", lastAddedWord)
    }

    @Test fun dontSelectConsecutiveSeparatorsWithURLDetection() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("bla..")
        assertEquals("", composingText)
        assertEquals("bla..", text)
    }

    @Test fun selectDoesSelect() {
        reset()
        setText("this is some text")
        setCursorPosition(3, 8)
        assertEquals("s is ", text.substring(3, 8))
    }

    @Test fun noComposingForPasswordFields() {
        reset()
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        input('a')
        input('b')
        assertEquals("", composingText)
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        input('.')
        input('c')
        assertEquals("", composingText)
    }

    @Test fun `don't select whole thing as composing word if URL detection disabled`() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, false) } // (ours is on)
        setText("http://example.com")
        setCursorPosition(13) // between l and e
        assertEquals("example", composingText)
    }

    @Test fun `select whole thing except http(s) as composing word if URL detection enabled and selecting`() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setText("http://example.com")
        setCursorPosition(13) // between l and e
        assertEquals("example.com", composingText)
        setText("http://bla.com http://example.com ")
        setCursorPosition(29) // between l and e
        assertEquals("example.com", composingText)
    }

    @Test fun `select whole thing except http(s) as composing word if URL detection enabled and typing`() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("http://example.com")
        assertEquals("example.com", composingText)
    }

    @Test fun `don't add partial URL to history`() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setText("http:/") // just so lastAddedWord isn't set to http
        chainInput("/bla.com")
        assertEquals("", lastAddedWord)
    }

    @Test fun urlProperlySelected() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        setText("http://example.com/here")
        setCursorPosition(18) // after .com
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE) // delete com
        // todo: do we really want no composing text?
        //  probably not... try not to break composing
        assertEquals("", composingText)
        chainInput("net")
        assertEquals("example.net", composingText)
    }

    @Test fun urlProperlySelectedWhenNotDeletingFullTld() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setText("http://example.com/here")
        setCursorPosition(18) // after .com
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE) // delete om
        // todo: this is a weird difference to deleting the full TLD (see urlProperlySelected)
        //  what do we want here? (probably consistency)
        assertEquals("example.c/here", composingText)
        chainInput("z")
        assertEquals("", composingText) // todo: this is a weird difference to deleting the full TLD
//        assertEquals("example.cz", composingText) // fails, but probably would be better than above
    }

    @Test fun dontCommitPartialUrlBeforeFirstPeriod() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        // type http://bla. -> bla not selected, but clearly url, also means http://bla is committed which we probably don't want
        chainInput("http://bla.")
        assertEquals("bla.", composingText)
    }

    @Test fun `intermediate commits in text field without protocol`() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, false) } // (ours is on)
        chainInput("bla.")
        assertEquals("bla", lastAddedWord)
        // the pieces after it aren't learned by themselves (the end of a longer run, 2026-10-04): still "bla"
        chainInput("com/")
        assertEquals("bla", lastAddedWord)
        chainInput("img.jpg")
        assertEquals("bla", lastAddedWord)
        assertEquals("jpg", composingText)
    }

    @Test fun `intermediate commit in text field without protocol and with URL detection`() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("bla.com/img.jpg")
        assertEquals("bla", lastAddedWord)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `only protocol commit in text field with protocol and URL detection`() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("http://bla.com/img.jpg")
        assertEquals("http", lastAddedWord)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `no intermediate commit in URL field with protocol`() {
        reset()
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("http://bla.com/img.jpg")
        assertEquals("http", lastAddedWord) // todo: somehow avoid?
        assertEquals("http://bla.com/img.jpg", text)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `no intermediate commit in URL field with protocol and URL detection`() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("http://bla.com/img.jpg")
        assertEquals("http", lastAddedWord) // todo: somehow avoid?
        assertEquals("http://bla.com/img.jpg", text)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `no intermediate commit in URL field without protocol`() {
        reset()
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("bla.com/img.jpg")
        assertEquals("", lastAddedWord)
        assertEquals("bla.com/img.jpg", text)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `no intermediate commit in URL field without protocol and with URL detection`() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("bla.com/img.jpg")
        assertEquals("", lastAddedWord)
        assertEquals("bla.com/img.jpg", text)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `don't accidentally detect some other text fields as URI`() {
        // see comment in InputLogic.textBeforeCursorMayBeUrlOrSimilar
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE)
        chainInput("Hey,why")
        assertEquals("Hey, why", text)
    }

    @Test fun `URL detection does not trigger on non-words`() {
        // first make sure it works without URL detection
        reset()
        chainInput("15:50-17")
        assertEquals("15:50-17", text)
        assertEquals("", composingText)
        // then with URL detection
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("15:50-17")
        assertEquals("15:50-17", text)
        assertEquals("", composingText)
    }

    @Test fun `autospace after selecting a suggestion`() {
        reset()
        pickSuggestion("this")
        input('b')
        assertEquals("this b", text)
        assertEquals("b", composingText)
    }

    @Test fun `autospace works in URL field when input isn't URL`() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        pickSuggestion("this")
        input('b')
        assertEquals("this b", text)
        assertEquals("b", composingText)
    }

    // https://github.com/HeliBorg/HeliBoard/issues/215
    // https://github.com/HeliBorg/HeliBoard/issues/229
    @Test fun `autospace works in URL field when input isn't URL, also for multiple suggestions`() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        pickSuggestion("this")
        pickSuggestion("is")
        assertEquals("this is", text)
        pickSuggestion("not")
        assertEquals("this is not", text)
        input('c')
        assertEquals("this is not c", text)
        assertEquals("c", composingText)
    }

    @Test fun `emoji is added to dictionary`() {
        // check both text and codepoint input
        reset()
        chainInput("hello ")
        input(0x1F36D)
        assertEquals(StringUtils.newSingleCodePointString(0x1F36D), lastAddedWord)
        reset()
        chainInput("hello ")
        input("🤗")
        assertEquals("\uD83E\uDD17", lastAddedWord)

        reset()
        chainInput("hello ")
        input("why 🤗 ") // not added because it's not only emoji (input can come from pasting)
        assertEquals("hello", lastAddedWord)
    }

    @Test fun `emoji uses phantom space`() {
        // check both text and codepoint input
        reset()
        pickSuggestion("hi")
        input("🤗")
        assertEquals("\uD83E\uDD17", lastAddedWord)
        assertEquals("hi \uD83E\uDD17", text)
        reset()
        pickSuggestion("hi")
        input(0x1F36D)
        assertEquals(StringUtils.newSingleCodePointString(0x1F36D), lastAddedWord)
        assertEquals("hi ${StringUtils.newSingleCodePointString(0x1F36D)}", text)
    }

    // https://github.com/HeliBorg/HeliBoard/issues/230
    @Test fun `no autospace after opening quotes`() {
        reset()
        chainInput("\"Hi\" \"h")
        assertEquals("\"Hi\" \"h", text)
        assertEquals("h", composingText)
        reset()
        chainInput("\"Hi\", \"h")
        assertEquals("\"Hi\", \"h", text)
        assertEquals("h", composingText)
    }

    @Test fun `autospace works in URL field when starting with quotes`() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        input("\"")
        pickSuggestion("this")
        input("i")
        assertEquals("\"this i", text)
    }

    @Test fun `double space results in period and space, and delete removes the period`() {
        reset()
        chainInput("hello")
        input(' ')
        input(' ')
        assertEquals("hello. ", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello ", text)
    }

    @Test fun `no weird space inside multi-"`() {
        reset()
        chainInput("\"\"\"")
        assertEquals("\"\"\"", text)

        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("\"\"\"")
        assertEquals("\"\"\"", text)
    }

    @Test fun `autospace still happens after "`() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("\"hello\"you")
        assertEquals("\"hello\" you", text)
    }

    @Test fun `autospace still happens after " if next word is in quotes`() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("\"hello\"\"you\"")
        assertEquals("\"hello\" \"you\"", text)
    }

    @Test fun `autospace propagates over "`() {
        reset()
        input('"')
        pickSuggestion("hello")
        assertEquals(spaceState, SpaceState.PHANTOM) // picking a suggestion sets phantom space state
        chainInput("\"you")
        assertEquals("\"hello\" you", text)
    }

    @Test fun `autospace still happens after " if nex word is in " and after comma`() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("\"hello\",\"you\"")
        assertEquals("\"hello\", \"you\"", text)
    }

    @Test fun `autospace in json editor`() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("{\"label\":\"")
        assertEquals("{\"label\": \"", text)
        input('c')
        assertEquals("{\"label\": \"c", text)
    }

    @Test fun `text input and delete`() {
        reset()
        input("hello")
        assertEquals("hello", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hell", text)

        reset()
        input("hello ")
        assertEquals("hello ", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello", text)
    }

    @Test fun `emoji text input and delete`() {
        reset()
        input("🕵🏼")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("", text)

        reset()
        input("\uD83D\uDD75\uD83C\uDFFC")
        input(' ')
        assertEquals("🕵🏼 ", text)
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("", text)
    }

    // emoRegex update to unicode 16.0 was required, https://github.com/HeliBorg/HeliBoard/issues/1760
    @Test fun `emojis deleted one by one`() {
        reset()
        chainInput("\uD83E\uDEC6\uD83E\uDEC6\uD83E\uDEC6")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("\uD83E\uDEC6\uD83E\uDEC6", text)
    }

    @Test fun `revert autocorrect on delete`() {
        reset()
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        chainInput("hullo")
        getAutocorrectedWithSpaceAfter("hello", "hullo")
        assertEquals("hello ", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hullo", text)

        reset()
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        latinIME.prefs().edit { putBoolean(Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT, false) }
        chainInput("hullo")
        getAutocorrectedWithSpaceAfter("hello", "hullo")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello", text)
    }

    @Test fun `remove glide typing word on delete`() {
        reset()
        glideTypingInput("hello")
        assertEquals("hello", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("", text)

        // todo: now we want some way to disable delete-all on backspace, either per setting or something else
        //  need to avoid getting into the mWordComposer.isBatchMode() part of handleBackspaceEvent
    }

    // ---- learning rules: a correction takes back only the use it undoes ----

    @Test fun `reverting an auto-correction takes one use back from the correction, the typed word counts when committed`() {
        reset()
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        chainInput("hullo")
        getAutocorrectedWithSpaceAfter("hello", "hullo")
        assertEquals("hello", lastAddedWord)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hullo", text)
        assertEquals(listOf("hello"), unlearnedWords)
        input(' ')
        assertEquals("hullo ", text)
        assertEquals("hullo", lastAddedWord)
        assertEquals(listOf("hello"), unlearnedWords) // the typed word put back is no accepted word changed
    }

    @Test fun `deleting a fresh swipe takes nothing back`() {
        reset()
        glideTypingInput("hello")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("", text)
        assertEquals(listOf(), unlearnedWords)
        assertEquals("", lastAddedWord) // and it was never counted
    }

    @Test fun `changing an accepted word at its end takes its use back`() {
        reset()
        chainInput("helo ")
        assertEquals("helo", lastAddedWord)
        functionalKeyPress(KeyCode.DELETE) // the space: helo is picked up again
        functionalKeyPress(KeyCode.DELETE)
        chainInput("lo ")
        assertEquals("hello ", text)
        assertEquals(listOf("helo"), unlearnedWords)
        assertEquals("hello", lastAddedWord)
        assertEquals(5, helium314.keyboard.latin.ShadowFacilitator2.lastAddedExtraUses) // corrected by hand: more than a strip pick (2026-10-05)
    }

    @Test fun `picking the picked up word again from the strip counts like any strip pick`() {
        reset()
        chainInput("hello ")
        functionalKeyPress(KeyCode.DELETE) // picked up again
        lastAddedWord = ""
        pickSuggestion("hello")
        assertEquals("hello", lastAddedWord) // a re-tap is a deliberate confirmation: counted (2026-10-04)
        assertEquals(3, helium314.keyboard.latin.ShadowFacilitator2.lastAddedExtraUses) // 1 + 3 extra, as every strip pick
        assertEquals(listOf(), unlearnedWords)
    }

    @Test fun `committing a picked up word unchanged takes nothing back and adds nothing`() {
        reset()
        chainInput("hello ")
        functionalKeyPress(KeyCode.DELETE)
        lastAddedWord = ""
        input(' ')
        assertEquals("hello ", text)
        assertEquals(listOf(), unlearnedWords)
        assertEquals("", lastAddedWord) // and isn't counted again (space, backspace, space: maybe just a pause)
    }

    @Test fun `deleting an accepted word takes nothing back`() {
        reset()
        chainInput("hello there ")
        repeat(6) { functionalKeyPress(KeyCode.DELETE) }
        assertEquals("hello ", text)
        chainInput("you ")
        assertEquals(listOf(), unlearnedWords)
        assertEquals("you", lastAddedWord)
    }

    @Test fun `changing an accepted word in the middle takes its use back, the new word counts when the cursor leaves`() {
        reset()
        setText("helo there")
        setCursorPosition(3) // hel|o: the word is picked up again
        input('l')
        assertEquals("hello there", text)
        assertEquals(listOf("helo"), unlearnedWords)
        input('l') // further changes take nothing more back
        assertEquals(listOf("helo"), unlearnedWords)
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE) // (a backspace picks up the half-changed word again: not an accepted one)
        input('l')
        assertEquals("hello there", text)
        assertEquals(listOf("helo"), unlearnedWords)
        setCursorPosition(text.length)
        assertEquals("hello", lastAddedWord)
        assertEquals(5, helium314.keyboard.latin.ShadowFacilitator2.lastAddedExtraUses) // corrected by hand: more than a strip pick (2026-10-05)
    }

    @Test fun `a word still being typed takes nothing back when changed in the middle`() {
        reset()
        chainInput("helo")
        setCursorPosition(3)
        input('l')
        assertEquals("hello", text)
        assertEquals(listOf(), unlearnedWords)
    }

    @Test fun `changing an accepted word at its end after a tap takes its use back when the cursor leaves`() {
        reset()
        chainInput("hello world ")
        assertEquals(listOf("hello", "world"), addedWords)
        setCursorPosition(5) // a tap at the end of hello: picked up again
        functionalKeyPress(KeyCode.DELETE)
        input('p')
        assertEquals("hellp world ", text)
        setCursorPosition(text.length) // a tap elsewhere: the edit is over
        assertEquals(listOf("hello"), unlearnedWords)
        assertEquals(listOf("hello", "world", "hellp"), addedWords)
        assertEquals(5, helium314.keyboard.latin.ShadowFacilitator2.lastAddedExtraUses) // corrected by hand: more than a strip pick (2026-10-05)
        input(' ')
        assertEquals(listOf("hello"), unlearnedWords)
        assertEquals(listOf("hello", "world", "hellp"), addedWords)
        setCursorPosition(11) // the end of world
        functionalKeyPress(KeyCode.DELETE)
        input('f')
        setCursorPosition(0) // the start of the box
        assertEquals(listOf("hello", "world"), unlearnedWords)
        assertEquals(listOf("hello", "world", "hellp", "worlf"), addedWords)
        setCursorPosition(5) // only shortened: text being deleted, nothing changes
        functionalKeyPress(KeyCode.DELETE)
        setCursorPosition(0)
        assertEquals("hell worlf. ", text) // (the second space made a period)
        assertEquals(listOf("hello", "world"), unlearnedWords)
        assertEquals(listOf("hello", "world", "hellp", "worlf"), addedWords)
    }

    // Some editors answer reads with their text from before the keyboard's last edit (see RichInputConnection
    // getTextBeforeCursorAndDetectLaggyConnection); the keyboard then reloads a cursor position from before the edit too,
    // so the editor's report of the edit looks like a tap. On a Pixel 8 that learned "w" while "hellp worlf  " was
    // deleted with backspace, and "p" from "3stimate 5pm ".
    @Test fun `deleting text with backspace learns nothing, also in a laggy editor`() {
        reset()
        chainInput("hellp worlf  ")
        addedWords.clear()
        laggyEditor = true
        while (text.isNotEmpty()) laggyKeyPress(KeyCode.DELETE)
        laggyEditor = false
        setCursorPosition(0)
        assertEquals(listOf(), addedWords)
        assertEquals(listOf(), unlearnedWords)
        reset()
        chainInput("3stimate 5pm ")
        addedWords.clear()
        laggyEditor = true
        while (text.isNotEmpty()) laggyKeyPress(KeyCode.DELETE)
        laggyEditor = false
        assertEquals(listOf(), addedWords)
        assertEquals(listOf(), unlearnedWords)
        // a word starting with ' isn't picked up again, so backspace deletes it as plain text
        reset()
        chainInput("so 'tisx ")
        addedWords.clear()
        repeat(2) { functionalKeyPress(KeyCode.DELETE) }
        assertEquals("so 'tis", text)
        setCursorPosition(0) // a tap elsewhere
        assertEquals(listOf(), addedWords)
    }

    // on a Pixel 8 the first undo in "the keyxboard" (cursor after x) logged "keyboard" as learned
    @Test fun `undo and redo of a step that learned nothing change nothing, also in a laggy editor`() {
        reset()
        chainInput("the keyboard")
        android.os.SystemClock.sleep(500) // a tap comes a while after typing
        setCursorPosition(7) // key|board
        addedWords.clear()
        laggyEditor = true
        laggyKeyPress('x'.code)
        assertEquals("the keyxboard", text)
        laggyKeyPress(KeyCode.UNDO)
        assertEquals("the keyboard", text)
        laggyKeyPress(KeyCode.REDO)
        assertEquals("the keyxboard", text)
        laggyKeyPress(KeyCode.UNDO)
        laggyEditor = false
        setCursorPosition(0)
        assertEquals(listOf(), addedWords)
        assertEquals(listOf(), unlearnedWords)
    }

    // ---- undo and redo: exactly what the step's learning did, taken back and given again ----

    // uses each word got (+) or lost (-) since the last reset of the facilitator's lists
    private fun usesOf(word: String) =
        helium314.keyboard.latin.ShadowFacilitator2.addedUses.filter { it.first == word }.sumOf { it.second } -
            unlearnedWords.count { it == word }

    private fun clearLearning() {
        addedWords.clear()
        unlearnedWords.clear()
        helium314.keyboard.latin.ShadowFacilitator2.addedUses.clear()
    }

    @Test fun `undo takes back a typed word's use, redo gives it again, back and forth nets zero`() {
        reset()
        chainInput("hello world ")
        assertEquals(listOf("hello", "world"), addedWords)
        clearLearning()
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("hello ", text)
        assertEquals(listOf("world"), unlearnedWords)
        assertEquals(-1, usesOf("world"))
        functionalKeyPress(KeyCode.REDO)
        assertEquals("hello world ", text)
        assertEquals(listOf("world"), addedWords)
        assertEquals(0, helium314.keyboard.latin.ShadowFacilitator2.lastAddedExtraUses) // plain +1
        assertEquals(0, usesOf("world"))
        repeat(3) {
            functionalKeyPress(KeyCode.UNDO)
            functionalKeyPress(KeyCode.REDO)
        }
        assertEquals("hello world ", text)
        assertEquals(0, usesOf("world"))
        assertEquals(0, usesOf("hello"))
        // two steps back: each word its own use
        functionalKeyPress(KeyCode.UNDO)
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("", text)
        assertEquals(-1, usesOf("world"))
        assertEquals(-1, usesOf("hello"))
        functionalKeyPress(KeyCode.REDO)
        functionalKeyPress(KeyCode.REDO)
        assertEquals("hello world ", text)
        assertEquals(0, usesOf("world"))
        assertEquals(0, usesOf("hello"))
        functionalKeyPress(KeyCode.REDO) // nothing left to redo
        assertEquals(0, usesOf("world"))
    }

    @Test fun `undo and redo of a typed word in a laggy editor`() {
        reset()
        chainInput("hello world ")
        clearLearning()
        laggyEditor = true
        laggyKeyPress(KeyCode.UNDO)
        assertEquals("hello ", text)
        laggyKeyPress(KeyCode.REDO)
        assertEquals("hello world ", text)
        laggyKeyPress(KeyCode.UNDO)
        laggyEditor = false
        assertEquals("hello ", text)
        assertEquals(-1, usesOf("world"))
        assertEquals(0, usesOf("hello"))
    }

    @Test fun `undo takes back all a strip pick gave, redo gives it again`() {
        reset()
        chainInput("hel")
        pickSuggestion("hello")
        assertEquals("hello", lastAddedWord)
        assertEquals(3, helium314.keyboard.latin.ShadowFacilitator2.lastAddedExtraUses)
        clearLearning()
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("", text)
        assertEquals(-4, usesOf("hello"))
        functionalKeyPress(KeyCode.REDO)
        assertEquals(0, usesOf("hello"))
        assertEquals(3, helium314.keyboard.latin.ShadowFacilitator2.lastAddedExtraUses) // +4 in one go
        functionalKeyPress(KeyCode.UNDO)
        functionalKeyPress(KeyCode.REDO)
        assertEquals(0, usesOf("hello"))
    }

    @Test fun `undo of a word corrected by hand gives the old word its use back and takes the new one's`() {
        reset()
        chainInput("helo ")
        functionalKeyPress(KeyCode.DELETE) // the space: helo is picked up again
        functionalKeyPress(KeyCode.DELETE)
        chainInput("lo ")
        assertEquals("hello ", text)
        assertEquals(listOf("helo"), unlearnedWords)
        clearLearning()
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("helo ", text)
        assertEquals(-6, usesOf("hello"))
        assertEquals(1, usesOf("helo"))
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("", text)
        assertEquals(0, usesOf("helo")) // helo's own +1 taken back too
        functionalKeyPress(KeyCode.REDO)
        assertEquals("helo ", text)
        assertEquals(1, usesOf("helo"))
        functionalKeyPress(KeyCode.REDO)
        assertEquals("hello ", text)
        assertEquals(0, usesOf("hello"))
        assertEquals(0, usesOf("helo"))
    }

    @Test fun `undo and redo of a reverted auto-correction reverse and redo both changes`() {
        reset()
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        chainInput("hullo")
        getAutocorrectedWithSpaceAfter("hello", "hullo")
        functionalKeyPress(KeyCode.DELETE) // revert: hello -1
        input(' ') // hullo +1
        assertEquals("hullo ", text)
        clearLearning()
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("hello ", text)
        assertEquals(1, usesOf("hello"))
        assertEquals(-1, usesOf("hullo"))
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("", text)
        assertEquals(0, usesOf("hello")) // the auto-correction's own +1 taken back
        functionalKeyPress(KeyCode.REDO)
        functionalKeyPress(KeyCode.REDO)
        assertEquals("hullo ", text)
        assertEquals(0, usesOf("hello"))
        assertEquals(0, usesOf("hullo"))
    }

    @Test fun `undoing and redoing a deletion changes nothing`() {
        reset()
        chainInput("hello there ")
        repeat(6) { functionalKeyPress(KeyCode.DELETE) }
        assertEquals("hello ", text)
        clearLearning()
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("hello there ", text)
        functionalKeyPress(KeyCode.REDO)
        assertEquals("hello ", text)
        functionalKeyPress(KeyCode.UNDO)
        assertEquals(listOf(), addedWords)
        assertEquals(listOf(), unlearnedWords)
        // further back, the typing of there: its use
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("hello ", text)
        assertEquals(-1, usesOf("there"))
    }

    @Test fun `undo by character takes the word's use back only when its last letter goes`() {
        reset()
        latinIME.prefs().edit {
            putString(Settings.PREF_UNDO_UNIT, "character")
            putString(Settings.PREF_REDO_UNIT, "character")
        }
        chainInput("hello world ")
        clearLearning()
        repeat(5) { functionalKeyPress(KeyCode.UNDO) }
        assertEquals("hello w", text)
        assertEquals(listOf(), unlearnedWords)
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("hello ", text)
        assertEquals(listOf("world"), unlearnedWords)
        // back and forth over a letter, or half the word: nothing more
        functionalKeyPress(KeyCode.REDO)
        functionalKeyPress(KeyCode.REDO)
        functionalKeyPress(KeyCode.UNDO)
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("hello ", text)
        assertEquals(-1, usesOf("world"))
        repeat(5) { functionalKeyPress(KeyCode.REDO) }
        assertEquals("hello world", text)
        assertEquals(-1, usesOf("world")) // (the step isn't all back yet: its space)
        functionalKeyPress(KeyCode.REDO)
        assertEquals("hello world ", text)
        assertEquals(0, usesOf("world"))
        functionalKeyPress(KeyCode.UNDO) // a space: nothing
        assertEquals(0, usesOf("world"))
        functionalKeyPress(KeyCode.REDO)
        assertEquals(0, usesOf("world"))
    }

    @Test fun `undo and redo across a tap take back and give each step's word`() {
        reset()
        chainInput("hello world ")
        android.os.SystemClock.sleep(500) // a tap comes a while after typing
        setCursorPosition(5) // hello| world
        chainInput(" big,")
        assertEquals("hello big, world ", text)
        assertEquals("big", lastAddedWord)
        clearLearning()
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("hello  world ", text)
        assertEquals(-1, usesOf("big"))
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("hello world ", text)
        functionalKeyPress(KeyCode.UNDO) // back where typing ended before the tap
        assertEquals("hello ", text)
        assertEquals(-1, usesOf("world"))
        assertEquals(-1, usesOf("big"))
        repeat(3) { functionalKeyPress(KeyCode.REDO) }
        assertEquals("hello big, world ", text)
        assertEquals(0, usesOf("world"))
        assertEquals(0, usesOf("big"))
    }

    @Test fun `a swiped word's use belongs to the swipe that put it there`() {
        reset()
        swipe("hello")
        swipe("world") // commits hello: its use is the first swipe's
        input(' ')
        assertEquals("hello world ", text)
        assertEquals(listOf("hello", "world"), addedWords)
        clearLearning()
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("hello", text)
        assertEquals(-1, usesOf("world"))
        assertEquals(0, usesOf("hello"))
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("", text)
        assertEquals(-1, usesOf("hello"))
        functionalKeyPress(KeyCode.REDO)
        functionalKeyPress(KeyCode.REDO)
        assertEquals(0, usesOf("hello"))
        assertEquals(0, usesOf("world"))
    }

    @Test fun `undoing a word never committed takes nothing back`() {
        reset()
        chainInput("hello wor")
        clearLearning()
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("hello ", text)
        functionalKeyPress(KeyCode.REDO)
        assertEquals("hello wor", text)
        assertEquals(listOf(), unlearnedWords)
        assertEquals(listOf(), addedWords)
    }

    @Test fun `where the history is lost, undo and redo change nothing either way`() {
        reset()
        chainInput("hello world ")
        clearLearning()
        functionalKeyPress(KeyCode.UNDO)
        assertEquals(-1, usesOf("world"))
        setText(text) // the editor starts again: the history is dropped
        functionalKeyPress(KeyCode.REDO) // the app's own redo
        assertEquals(listOf(), addedWords)
        reset()
        chainInput("hello world ")
        setText(text)
        clearLearning()
        functionalKeyPress(KeyCode.UNDO) // the app's own undo
        functionalKeyPress(KeyCode.REDO)
        assertEquals(listOf(), unlearnedWords)
        assertEquals(listOf(), addedWords)
    }

    @Test fun `the corrections log has a line for each undo and redo, also those that changed nothing`() {
        reset()
        helium314.keyboard.latin.personalization.LearningEventLog.init(latinIME)
        latinIME.prefs().edit { putBoolean(Settings.PREF_LEARNING_LOG, true) }
        helium314.keyboard.latin.personalization.LearningEventLog.clear()
        val file = java.io.File(latinIME.getExternalFilesDir(null) ?: latinIME.filesDir, "learning_events.tsv")
        file.parentFile?.mkdirs()
        chainInput("hello there ")
        repeat(6) { functionalKeyPress(KeyCode.DELETE) }
        functionalKeyPress(KeyCode.UNDO) // the deletion: none
        functionalKeyPress(KeyCode.UNDO) // there -1
        functionalKeyPress(KeyCode.REDO) // there +1
        setText(text)
        functionalKeyPress(KeyCode.UNDO) // the history is gone: untracked
        Thread.sleep(1000) // (the log waits a moment for the counts after)
        val lines = file.readLines().map { it.split('\t') }.filter { it[1] == "undo" || it[1] == "redo" }
        latinIME.prefs().edit { putBoolean(Settings.PREF_LEARNING_LOG, false) }
        assertEquals(listOf("undo none", "undo typed there -1", "redo typed there +1", "undo untracked"),
            lines.map { (listOf(it[1], it[2]) + listOf(it[3] + it[4]).filter { w -> w.isNotEmpty() } + it.drop(11)).joinToString(" ") })
    }

    @Test fun `undo and redo learn nothing in incognito`() {
        reset()
        chainInput("hello world ")
        latinIME.prefs().edit { putBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, true) }
        clearLearning()
        functionalKeyPress(KeyCode.UNDO)
        functionalKeyPress(KeyCode.REDO)
        assertEquals(listOf(), unlearnedWords)
        assertEquals(listOf(), addedWords)
    }

    @Test fun `a word typed right after a digit is not learned on its own`() {
        reset()
        chainInput("5pm ")
        assertEquals(listOf(), addedWords)
        chainInput("3stimate ")
        assertEquals(listOf(), addedWords) // starts with a digit: a space doesn't learn it, a strip tap does (2026-10-04)
    }

    @Test fun `editing a swipe before its commit counts only the final word`() {
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_BACKSPACE_DELETES_SWIPED_WORD, false) }
        glideTypingInput("hello")
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
        chainInput("p ")
        assertEquals("help ", text)
        assertEquals(listOf(), unlearnedWords)
        assertEquals("help", lastAddedWord)
    }

    @Test fun timestamp() {
        reset()
        chainInput("hello")
        functionalKeyPress(KeyCode.TIMESTAMP)
        assertEquals(Calendar.getInstance().time.time.toDouble(),
            java.text.SimpleDateFormat(TIMESTAMP_FORMAT, settingsValues.mLocale).parse(text.substring(5))!!.time.toDouble(), 1000.0)
    }

    @Test fun inlineEmojiSearchStart() {
        assertEquals(true, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, ' '.code, settingsValues))
        assertEquals(false, InputLogic.isStartOfInlineEmojiSearch(' '.code, ':'.code, ' '.code, settingsValues))
        assertEquals(true, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, '.'.code, settingsValues))
        assertEquals(true, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, "🌍".codePoints().asSequence().last(), settingsValues))
        assertEquals(false, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, 't'.code, settingsValues))
        assertEquals(false, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, '3'.code, settingsValues))
    }

    @Test fun inlineEmojiSearchString() {
        assertEquals("test", InputLogic.getInlineEmojiSearchString(":test"))
        assertEquals(null, InputLogic.getInlineEmojiSearchString("test"))
        assertEquals("test", InputLogic.getInlineEmojiSearchString(" :test"))
        assertEquals(null, InputLogic.getInlineEmojiSearchString("t:test"))
        assertEquals(null, InputLogic.getInlineEmojiSearchString("6:test"))
        assertEquals("test", InputLogic.getInlineEmojiSearchString("🌍:test"))
        assertEquals("test", InputLogic.getInlineEmojiSearchString(",:test"))
        assertEquals(null, InputLogic.getInlineEmojiSearchString(":test\nt"))
        assertEquals("/48", InputLogic.getInlineEmojiSearchString("2606:127.0.0.1::/48")) // do we want this?
    }

    @Test fun `undo after a tap and a letter typed there goes back across the tap`() {
        reset()
        chainInput("the keyboard")
        android.os.SystemClock.sleep(500) // a tap comes a while after typing (one right after would be the typing's own echo)
        setCursorPosition(7) // a tap: key|board
        chainInput("x")
        assertEquals("the keyxboard", text)
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("the keyboard", text)
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("the ", text)
    }

    @Test fun `undo right after a tap works where typing ended`() {
        reset()
        chainInput("the keyboard")
        android.os.SystemClock.sleep(500)
        setCursorPosition(7)
        functionalKeyPress(KeyCode.UNDO)
        assertEquals("the ", text)
    }


    // ------- helper functions ---------

    // should be called before every test, so the same state is guaranteed
    private fun reset() {
        // reset input connection & facilitator
        currentScript = ScriptUtils.SCRIPT_LATIN
        text = ""
        batchEdit = 0
        currentInputType = InputType.TYPE_CLASS_TEXT
        lastAddedWord = ""
        unlearnedWords.clear()
        addedWords.clear()
        helium314.keyboard.latin.ShadowFacilitator2.addedUses.clear()
        laggyEditor = false
        staleText = null

        // reset settings
        latinIME.prefs().edit { clear() }

        setText("") // (re)sets selection and composing word
    }

    private fun chainInput(text: String) = text.forEach { input(it.code) }

    private fun input(char: Char) = input(char.code)

    private fun input(codePoint: Int) {
        require(codePoint > 0) { "not a codePoint: $codePoint" }
        val oldBefore = textBeforeCursor
        val oldAfter = textAfterCursor
        val insert = StringUtils.newSingleCodePointString(codePoint)
        val phantomSpaceToInsert = if (spaceState == SpaceState.PHANTOM) " " else ""
        val oldIsAtEnd = !composer.isCursorFrontOrMiddleOfComposingWord

        latinIME.onEvent(Event.createEventForCodePointFromUnknownSource(codePoint))
        handleMessages()

        if (!latinIME.prefs().getString(Settings.PREF_SELECTED_SUBTYPE, "")!!.contains("CombiningRules") // check fails if combiner merges symbols
            && !(codePoint == Constants.CODE_SPACE && oldBefore.lastOrNull() == ' ') // check fails when 2 spaces are converted into a period
            && !latinIME.mInputLogic.mSuggestedWords.mWillAutoCorrect // autocorrect obviously creates inconsistencies
            ) {
            if (phantomSpaceToInsert.isEmpty())
                assertEquals(oldBefore + insert, textBeforeCursor)
            else // in some cases autospace might be suppressed
                assert(oldBefore + phantomSpaceToInsert + insert == textBeforeCursor || oldBefore + insert == textBeforeCursor)
        }
        assertEquals(oldAfter, textAfterCursor)
        assertEquals(textBeforeCursor + textAfterCursor, getText())
        if (composer.isComposingWord) // if we're not composing any more cursor is always at the end
            assertEquals(oldIsAtEnd, !composer.isCursorFrontOrMiddleOfComposingWord)
        checkConnectionConsistency()
    }

    // a key after which the text before the cursor may have been rewritten, not just appended to
    private fun inputRewriting(char: Char) {
        latinIME.onEvent(Event.createEventForCodePointFromUnknownSource(char.code))
        handleMessages()
        assertEquals(textBeforeCursor + textAfterCursor, getText())
        checkConnectionConsistency()
    }

    private fun functionalKeyPress(keyCode: Int) {
        require(keyCode < 0) { "not a functional key code: $keyCode" }
        latinIME.onEvent(Event.createSoftwareKeypressEvent(Event.NOT_A_CODE_POINT, keyCode, 0, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
        handleMessages()
        checkConnectionConsistency()
    }

    // almost the same as codePoint input, but calls different latinIME function
    private fun input(insert: String) {
        val oldBefore = textBeforeCursor
        val oldAfter = textAfterCursor
        val phantomSpaceToInsert = if (spaceState == SpaceState.PHANTOM) " " else ""

        latinIME.onTextInput(insert)
        handleMessages()

        if (phantomSpaceToInsert.isEmpty())
            assertEquals(oldBefore + insert, textBeforeCursor)
        else // in some cases autospace might be suppressed
            assert(oldBefore + phantomSpaceToInsert + insert == textBeforeCursor || oldBefore + insert == textBeforeCursor)
        assert(oldBefore + insert == textBeforeCursor || "$oldBefore $insert" == textBeforeCursor)
        assertEquals(oldAfter, textAfterCursor)
        assertEquals(textBeforeCursor + textAfterCursor, getText())
        checkConnectionConsistency()
    }

    private fun getWordAtCursor() = connection.getWordRangeAtCursor(settingsValues.mSpacingAndPunctuations, currentScript)?.mWord

    private fun setCursorPosition(start: Int, end: Int = start, weirdTextField: Boolean = false) {
        val ei = EditorInfo()
        ei.inputType = currentInputType
        ei.initialSelStart = start
        ei.initialSelEnd = end
        // imeOptions should not matter

        // adjust text in inputConnection first, otherwise fixLyingCursorPosition will move cursor
        // to the end of the text
        val fullText = textBeforeCursor + selectedText + textAfterCursor
        assertEquals(fullText, getText())

        // need to update ic before, otherwise when reloading text cache from ic, ric will load wrong text before cursor
        val oldStart = selectionStart
        val oldEnd = selectionEnd
        selectionStart = start
        selectionEnd = end
        assertEquals(fullText, textBeforeCursor + selectedText + textAfterCursor)

        latinIME.onUpdateSelection(oldStart, oldEnd, start, end, composingStart, composingEnd)
        handleMessages()

        if (weirdTextField) {
            latinIME.mHandler.onStartInput(ei, true) // essentially does nothing
            latinIME.mHandler.onStartInputView(ei, true) // does the thing
            handleMessages()
        }

        assertEquals(fullText, getText())
        assertEquals(start, selectionStart)
        assertEquals(end, selectionEnd)
        checkConnectionConsistency()
    }

    // assumes we have nothing selected
    private fun getCursorPosition(): Int {
        assertEquals(cursor, connection.expectedSelectionStart)
        assertEquals(cursor, connection.expectedSelectionEnd)
        return cursor
    }

    // a key in a laggy editor: the editor reports the new cursor after the key (as a phone's editor does, here before
    // the keyboard's posted messages run), and only then answers reads with its current text
    private fun laggyKeyPress(code: Int) {
        val oldStart = selectionStart
        val oldEnd = selectionEnd
        latinIME.onEvent(if (code < 0) Event.createSoftwareKeypressEvent(Event.NOT_A_CODE_POINT, code, 0,
                Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            else Event.createEventForCodePointFromUnknownSource(code))
        staleText = null
        latinIME.onUpdateSelection(oldStart, oldEnd, selectionStart, selectionEnd, composingStart, composingEnd)
        handleMessages()
    }

    // just sets the text and starts input so connection it set up correctly
    private fun setText(newText: String) {
        text = newText
        selectionStart = newText.length
        selectionEnd = selectionStart
        composingStart = -1
        composingStart = -1

        // we need to start input to notify that something changed
        // restarting is false, so this is seen as a new text field
        val ei = EditorInfo()
        ei.inputType = currentInputType
        latinIME.mHandler.onStartInput(ei, false)
        latinIME.mHandler.onStartInputView(ei, false)
        handleMessages() // this is important so the composing span is set correctly
        checkConnectionConsistency()
    }

    // like selecting a suggestion from strip
    private fun pickSuggestion(suggestion: String) {
        val info = SuggestedWordInfo(suggestion, "", 0, 0, null, 0, 0)
        latinIME.pickSuggestionManually(info)
        checkConnectionConsistency()
    }

    // only works when autocorrect is on, separator after word is required
    private fun getAutocorrectedWithSpaceAfter(suggestion: String, typedWord: String?) {
        val info = SuggestedWordInfo(suggestion, "", 0, 0, null, 0, 0)
        val typedInfo = SuggestedWordInfo(typedWord, "", 0, 0, null, 0, 0)
        val sw = SuggestedWords(ArrayList(listOf(typedInfo, info)), null, typedInfo, false, true, false, 0, 0)
        latinIME.mInputLogic.setSuggestedWords(sw) // this prepares for autocorrect
        input(' ')
        checkConnectionConsistency()
    }

    // a swipe as the keyboard gets it: its start, then the word
    private fun swipe(word: String) {
        latinIME.mInputLogic.onStartBatchInput(settingsValues, KeyboardSwitcher.getInstance(), latinIME.mHandler)
        handleMessages()
        glideTypingInput(word)
        handleMessages()
    }

    private fun glideTypingInput(word: String) {
        val info = SuggestedWordInfo(word, "", 0, 0, null, 0, 0)
        val sw = SuggestedWords(ArrayList(listOf(info)), null, info, true, false, false, 0, 0)
        latinIME.mInputLogic.onUpdateTailBatchInputCompleted(settingsValues, sw, KeyboardSwitcher.getInstance())
    }

    private fun checkConnectionConsistency() {
        // RichInputConnection only has composing text up to cursor, but InputConnection has full composing text
        val expectedConnectionComposingText = if (composingStart == -1 || composingEnd == -1) ""
        else text.substring(composingStart, min(composingEnd, selectionEnd))
        assert(composingText.startsWith(expectedConnectionComposingText))
        // RichInputConnection only returns text up to cursor
        val textBeforeComposingText = if (composingStart == -1) textBeforeCursor else text.substring(0, composingStart)

        println("consistency: $selectionStart, ${connection.expectedSelectionStart}, $selectionEnd, ${connection.expectedSelectionEnd}, $textBeforeComposingText, " +
                "$connectionTextBeforeComposingText, $composingText, $connectionComposingText, $textBeforeCursor, ${connection.getTextBeforeCursor(textBeforeCursor.length, 0)}" +
                ", $textAfterCursor, ${connection.getTextAfterCursor(textAfterCursor.length, 0)}")
        assertEquals(selectionStart, connection.expectedSelectionStart)
        assertEquals(selectionEnd, connection.expectedSelectionEnd)
        assertEquals(textBeforeComposingText, connectionTextBeforeComposingText)
        assertEquals(expectedConnectionComposingText, connectionComposingText)
        assertEquals(textBeforeCursor, connection.getTextBeforeCursor(textBeforeCursor.length, 0).toString())
        assertEquals(textAfterCursor, connection.getTextAfterCursor(textAfterCursor.length, 0).toString())
    }

    private fun getText() =
        connection.getTextBeforeCursor(100, 0).toString() + (connection.getSelectedText(0) ?: "") + connection.getTextAfterCursor(100, 0)

    private fun setInputType(inputType: Int) {
        // set text to actually apply input type
        currentInputType = inputType
        setText(text)
    }

    // always need to handle messages for proper simulation
    private fun handleMessages() {
        while (messages.isNotEmpty()) {
            latinIME.mHandler.handleMessage(messages.first())
            messages.removeAt(0)
        }
        while (delayedMessages.isNotEmpty()) {
            val msg = delayedMessages.first()
            if (msg.what != 2) // MSG_UPDATE_SUGGESTION_STRIP, we want to ignore it because it's irrelevant and has a 500 ms timeout
                latinIME.mHandler.handleMessage(delayedMessages.first())
            delayedMessages.removeAt(0)
            // delayed messages may post further messages, handle before next delayed message
            while (messages.isNotEmpty()) {
                latinIME.mHandler.handleMessage(messages.first())
                messages.removeAt(0)
            }
        }
        assertEquals(0, messages.size)
        assertEquals(0, delayedMessages.size)
    }

}

private var currentInputType = InputType.TYPE_CLASS_TEXT
private var currentScript = ScriptUtils.SCRIPT_LATIN
private val messages = mutableListOf<Message>() // for latinIME / ShadowInputMethodService
private val delayedMessages = mutableListOf<Message>() // for latinIME / ShadowInputMethodService
// inputconnection stuff
private var batchEdit = 0
private var text = ""
private var selectionStart = 0
private var selectionEnd = 0
private var composingStart = -1
private var composingEnd = -1
// convenience for access
private val textBeforeCursor get() = text.substring(0, selectionStart)
private val textAfterCursor get() = text.substring(selectionEnd)
private val selectedText get() = text.substring(selectionStart, selectionEnd)
private val cursor get() = if (selectionStart == selectionEnd) selectionStart else -1
// a laggy editor (like some note apps) answers reads with its text and cursor from before the keyboard's last edit, until
// it has reported the new cursor (see laggyKeyPress)
private var laggyEditor = false
private var staleText: String? = null
private var staleSelection = 0
private fun beforeEdit() {
    if (laggyEditor && staleText == null) { staleText = text; staleSelection = selectionStart }
}

// composingText should return everything, but RichInputConnection.mComposingText only returns up to cursor
private val composingText get() = if (composingStart == -1 || composingEnd == -1) ""
    else text.substring(composingStart, composingEnd)

// essentially this is the text field we're editing in
private val ic = object : InputConnection {
    // pretty clear (though this may be slow depending on the editor)
    // bad return value here is likely the cause for that weird bug improved/fixed by fixIncorrectLength
    override fun getTextBeforeCursor(p0: Int, p1: Int): CharSequence = (staleText?.substring(0, staleSelection) ?: textBeforeCursor).take(p0)
    // pretty clear (though this may be slow depending on the editor)
    override fun getTextAfterCursor(p0: Int, p1: Int): CharSequence = (staleText?.substring(staleSelection) ?: textAfterCursor).take(p0)
    // pretty clear
    override fun getSelectedText(p0: Int): CharSequence? = if (selectionStart == selectionEnd) null
        else text.substring(selectionStart, selectionEnd)
    // inserts text at cursor (right?), and sets it as composing text
    // this REPLACES currently composing text (even if at a different position)
    // moves the cursor: positive means relative to composing text start, negative means relative to start
    override fun setComposingText(newText: CharSequence, cursor: Int): Boolean {
        beforeEdit()
        // first remove the composing text if any
        if (composingStart != -1 && composingEnd != -1)
            text = text.substring(0, composingStart) + text.substring(composingEnd)
        else // no composing span active, we should remove selected text
            if (selectionStart != selectionEnd) {
                text = textBeforeCursor + textAfterCursor
                selectionEnd = selectionStart
            }
        // then set the new text at old composing start
        // if no composing start, set it at cursor position
        val insertStart = if (composingStart == -1) selectionStart else composingStart
        text = text.substring(0, insertStart) + newText + text.substring(insertStart)
        composingStart = insertStart
        composingEnd = insertStart + newText.length
        // the cursor -1 is not clear in documentation, but
        // "So a value of 1 will always advance you to the position after the full text being inserted"
        // means that 1 must be composingEnd
        selectionStart = if (cursor > 0) composingEnd + cursor - 1
            else -cursor
        selectionEnd = selectionStart
        // todo: this should call InputMethodManager#updateSelection(View, int, int, int, int)
        //  but only after batch edit has ended
        //  this is not used in RichInputMethodManager, but probably ends up in LatinIME.onUpdateSelection
        //  -> DO IT (though it will likely only trigger that belatedSelectionUpdate thing, it might be relevant)
        return true
    }
    override fun setComposingRegion(p0: Int, p1: Int): Boolean {
        println("setComposingRegion, $p0, $p1")
        composingStart = p0
        composingEnd = p1
        return true // never checked
    }
    // sets composing text empty, but doesn't change actual text
    override fun finishComposingText(): Boolean {
        composingStart = -1
        composingEnd = -1
        return true // always true
    }
    // as per documentation: "This behaves like calling setComposingText(text, newCursorPosition) then finishComposingText()"
    override fun commitText(p0: CharSequence, p1: Int): Boolean {
        setComposingText(p0, p1)
        finishComposingText()
        return true // whether we added the text
    }
    // just tells the text field that we add many updated, and that the editor should not
    // send status updates until batch edit ended (not actually used for this simulation)
    override fun beginBatchEdit(): Boolean {
        ++batchEdit
        return true // always true
    }
    // end a batch edit, but maybe there are multiple batch edits happening
    override fun endBatchEdit(): Boolean {
        if (batchEdit > 0)
            return --batchEdit == 0
        return false // returns true if there is still a batch edit ongoing
    }
    // should notify about cursor info containing composing text, selection, ...
    // todo: maybe that could be interesting, implement it?
    override fun requestCursorUpdates(p0: Int): Boolean {
        // we call this, but don't have onUpdateCursorAnchorInfo overridden in latinIME, so it does nothing
        // also currently we don't care about the return value
        return false
    }
    override fun setSelection(p0: Int, p1: Int): Boolean {
        beforeEdit()
        selectionStart = p0
        selectionEnd = p1
        // todo: call InputMethodService.onUpdateSelection(int, int, int, int, int, int), but only after batch edit is done!
        return true
    }
    // delete beforeLength before cursor position, and afterLength after cursor position
    // chars, not codepoints or glyphs
    // todo: may delete only one half of a surrogate pair, but this should be avoided by RichInputConnection (maybe throw error)
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        beforeEdit()
        // delete only before or after selection
        text = textBeforeCursor.substring(0, textBeforeCursor.length - beforeLength) +
                text.substring(selectionStart, selectionEnd) +
                textAfterCursor.substring(afterLength)

        // if parts of the composing span are deleted, shorten the span (set end to shorter)
        if (selectionStart <= composingStart) {
            composingStart -= beforeLength // is this correct?
            composingEnd -= beforeLength
        } else if (selectionStart <= composingEnd) {
            composingEnd -= beforeLength // is this correct?
        }
        if (selectionEnd <= composingStart) {
            composingStart -= afterLength
            composingEnd -= afterLength
        } else if (selectionEnd <= composingEnd) {
            composingEnd -= afterLength
        }
        // update selection
        selectionStart -= beforeLength
        selectionEnd -= beforeLength
        return true
    }
    override fun sendKeyEvent(p0: KeyEvent): Boolean {
        if (p0.action != KeyEvent.ACTION_DOWN) return true // only change the text on key down, like RichInputConnection does
        beforeEdit()
        if (p0.keyCode == KeyEvent.KEYCODE_DEL) {
            if (selectionEnd == 0) return true // nothing to delete
            if (selectedText.isEmpty()) {
                text = text.substring(0, selectionStart - 1) + text.substring(selectionEnd)
                selectionStart -= 1
            } else {
                text = text.substring(0, selectionStart) + text.substring(selectionEnd)
            }
            selectionEnd = selectionStart
            return true
        }
        val textToAdd = when (p0.keyCode) {
            KeyEvent.KEYCODE_ENTER -> "\n"
            KeyEvent.KEYCODE_DEL -> null
            KeyEvent.KEYCODE_UNKNOWN -> p0.characters
            else -> StringUtils.newSingleCodePointString(p0.unicodeChar)
        }
        if (textToAdd != null) {
            text = text.substring(0, selectionStart) + textToAdd + text.substring(selectionEnd)
            selectionStart += textToAdd.length
            selectionEnd = selectionStart
            composingStart = -1
            composingEnd = -1
        }
        return true
    }
    // implementation is only to work with getTextBeforeCursorAndDetectLaggyConnection
    override fun getExtractedText(p0: ExtractedTextRequest?, p1: Int): ExtractedText {
        return ExtractedText().also {
            it.startOffset = 0
            it.selectionStart = if (staleText != null) staleSelection else selectionStart
            it.selectionEnd = if (staleText != null) staleSelection else selectionEnd
        }
    }
    // only effect is flashing, so whatever...
    override fun commitCorrection(p0: CorrectionInfo?): Boolean = true
    // implement only when necessary
    override fun getCursorCapsMode(p0: Int): Int = TODO("Not yet implemented")
    override fun deleteSurroundingTextInCodePoints(p0: Int, p1: Int): Boolean = TODO("Not yet implemented")
    override fun commitCompletion(p0: CompletionInfo?): Boolean = TODO("Not yet implemented")
    override fun performEditorAction(p0: Int): Boolean = TODO("Not yet implemented")
    override fun performContextMenuAction(p0: Int): Boolean = TODO("Not yet implemented")
    override fun clearMetaKeyStates(p0: Int): Boolean = TODO("Not yet implemented")
    override fun reportFullscreenMode(p0: Boolean): Boolean = TODO("Not yet implemented")
    override fun performPrivateCommand(p0: String?, p1: Bundle?): Boolean = TODO("Not yet implemented")
    override fun getHandler(): Handler = TODO("Not yet implemented")
    override fun closeConnection() = TODO("Not yet implemented")
    override fun commitContent(p0: InputContentInfo, p1: Int, p2: Bundle?): Boolean = TODO("Not yet implemented")
}

// Shadows are handled by Robolectric. @Implementation overrides built-in functionality.
// This is used for avoiding crashes (LocaleManagerCompat, InputMethodManager, KeyboardSwitcher)
// and for simulating system stuff (InputMethodService for controlling the InputConnection, which
// more or less is the contents of the text field), and for setting the current script in
// KeyboardSwitcher without having to care about InputMethodSubtypes

// could also extend LatinIME, it's not final anyway
@Implements(InputMethodService::class)
class ShadowInputMethodService {
    @Implementation
    fun getCurrentInputEditorInfo() = EditorInfo().apply {
        inputType = currentInputType
        // anything else?
    }
    @Implementation
    fun getCurrentInputConnection() = ic
    @Implementation
    fun isInputViewShown() = true // otherwise selection updates will do nothing
}

@Implements(Handler::class)
class ShadowHandler {
    @Implementation
    fun sendMessage(message: Message) {
        messages.add(message)
    }
    @Implementation
    fun sendMessageDelayed(message: Message, delay: Long) {
        delayedMessages.add(message)
    }
}

@Implements(KeyboardSwitcher::class)
class ShadowKeyboardSwitcher {
    @Implementation
    // basically only needed for null check
    fun getMainKeyboardView(): MainKeyboardView = Mockito.mock(MainKeyboardView::class.java)
    @Implementation
    // only affects view
    fun setKeyboard(keyboardId: Int, toggleState: KeyboardSwitcher.KeyboardSwitchState) = Unit
    @Implementation
    // only affects view
    fun setOneHandedModeEnabled(enabled: Boolean) = Unit
    @Implementation
    fun getCurrentKeyboardScript() = currentScript
}

@Implements(DictionaryFacilitatorImpl::class)
class ShadowFacilitator2 {
    @Implementation
    fun addToUserHistory(suggestion: String, wasAutoCapitalized: Boolean,
                         ngramContext: NgramContext, timeStampInSeconds: Long,
                         blockPotentiallyOffensive: Boolean) {
        lastAddedWord = suggestion
        lastAddedExtraUses = 0
        addedWords.add(suggestion)
        addedUses.add(suggestion to 1)
    }
    // a picked suggestion is learned with extra uses
    @Implementation
    fun addToUserHistory(suggestion: String, wasAutoCapitalized: Boolean,
                         ngramContext: NgramContext, timeStampInSeconds: Long,
                         blockPotentiallyOffensive: Boolean, extraUses: Int) {
        lastAddedWord = suggestion
        lastAddedExtraUses = extraUses
        addedWords.add(suggestion)
        addedUses.add(suggestion to 1 + extraUses)
    }
    @Implementation
    fun unlearnOneUse(word: String) {
        unlearnedWords.add(word)
    }
    companion object {
        var lastAddedWord = ""
        var lastAddedExtraUses = 0
        val unlearnedWords = mutableListOf<String>()
        val addedWords = mutableListOf<String>() // every word learned, in order
        val addedUses = mutableListOf<Pair<String, Int>>() // every word learned, with the uses it got
    }
}
