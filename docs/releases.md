# Curmudgeon Keyboard releases

Version names are `0.MINOR.NNN`, version codes `MINOR * 1000 + NNN` (0.1.0 went out as code 1). One release per settings
area; the build files are kept as `Curmudgeon_Keyboard_<name>_vc<code>` next to each other.

| Release | Code | Theme | What's in it |
|---|---|---|---|
| 0.1.0 | 1 ("1000") | Launch MVP | First Play closed-test build: our own swipe decoder, Midnight look, symbol popup map, simple / advanced settings. |
| 0.1.001 | 1001 | Test releasing | The Play release path end to end: own signing key, internal-testing release. |
| 0.1.002 | 1002 | Refine preferences | Keyboards with their own languages, layouts and (optionally) settings; the Preferences screen rebuilt around input and layout; popups per key, spiral duplicate removal; clipboard history by length. |
| 0.1.003 | 1003 | Refine appearance | Appearance with a live preview keyboard, accept / reject, saved and built-in themes; text style dialogs (font list, size, bold / italic / underline); hide-symbols switch; key sound choice and phone-setting hints for sound and vibration. |
| 0.1.004 | 1004 | Optimize code | Clean-up: repeated code merged, dead code removed, less work per swipe and per suggestion. |
| 0.2.000 | 2000 | Own swipe decoder, rebuilt | Everything in 0.1.004 so far, plus a swipe decoder built only on long-published methods: Swype-style word picking (first and last letter where the swipe starts and ends, letters matched in order along the path, a wider second search if nothing fits); inflection points scored together with where the whole path runs; slowing down over a key as a soft preference; stops no longer mark letters. The swiped word shows in the suggestion strip only. Replay of 469 recorded swipes: top-3 85.1 %, top-8 92.5 %. |
| 0.2.001 | 2001 | Swipe results and previews (not released on its own) | A word typed up to its apostrophe suggests its contractions first. The swipe recorder captures a swiped word edited into another; an opt-in log of every swipe's result (first choice right, picked from the strip, never offered) with the phone's decode time, also shown in the swipe statistics. More Layout & Typing rows try themselves on the preview keyboard. No decoder changes. |
| 0.3.000 | 3000 | Swipe engine, measured | Everything in 0.2.001, plus the swipe decoder changes measured on 1,428 of our own swipes and 40,245 FUTO swipes: a corner after a long reach may stop short of its key; fast swipes lean more on word frequency and get relaxed corner matching. First choice 68.8 → 71.3 % and 80.2 → 82.1 %; words never offered 11.8 → 9.9 % and 8.3 → 7.2 %; decode time unchanged (11 ms on the P11). |
