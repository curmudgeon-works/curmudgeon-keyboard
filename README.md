# Curmudgeon Keyboard

Swipe typing done right. Highly customizable, no tracking, no AI claptrap.

Curmudgeon Keyboard is an Android keyboard for people who are fed up with one-size-fits-all keyboards that can't swipe correctly and keep second-guessing what you typed. It does what you tell it to, no more and no less.

It is built on the excellent [HeliBoard](https://github.com/HeliBorg/HeliBoard) keyboard, and most of what makes it a solid keyboard is their work. See [Credits](#credits).

- No internet permission: the app cannot send anything anywhere.
- No ads, no accounts.
- Android 5.0 and newer.

Curmudgeon Keyboard is in closed testing on Google Play; the public release comes later.

## Table of Contents

- [What's different](#whats-different)
- [Everything else, from HeliBoard](#everything-else-from-heliboard)
- [Building](#building)
- [Feedback](#feedback)
- [License](#license)
- [Credits](#credits)

## What's different

- **Swipe typing with our own open-source decoder**, built into the app. No closed-source gesture library needed.
- **Dozens of suggestions** when a swipe is ambiguous, with rules for what shows where on the suggestion strip.
- **Your words come first**, even when the dictionary doesn't know them.
- **No space needed after swiping.**
- **Tune the swiping algorithm yourself** under the hood, if you want to.
- **Customize almost everything**: swiping behavior, suggestions, layouts and fonts, key popups, undo/redo lengths, clipboard memory, background pictures.

## Everything else, from HeliBoard

These come from HeliBoard (and before it OpenBoard and the AOSP keyboard), and work as they do there:

- Dictionaries for suggestions and spell check; more can be added from the [dictionaries repository](https://codeberg.org/Helium314/aosp-dictionaries)
- Multilingual typing
- Themes: style, colors, background image, day/night, dynamic colors on Android 12+
- Custom keyboard layouts, including symbols, numbers and functional keys (see [layouts.md](layouts.md))
- Clipboard history, one-handed mode, split keyboard, number pad
- Emoji search (with an emoji dictionary)
- Backup and restore of settings and learned words

HeliBoard's [wiki](https://github.com/HeliBorg/HeliBoard/wiki), including its [FAQ](https://github.com/HeliBorg/HeliBoard/wiki/FAQ) and [hidden features](https://github.com/HeliBorg/HeliBoard/wiki/9.-Hidden-features), mostly applies here too.

## Building

```
./gradlew :app:assemblePlayDebug
```

The APK lands in `app/build/outputs/apk/play/debug/`. Release builds (`:app:assemblePlayNouserlib`) are signed only if `~/.android-keys/curmudgeon-upload.properties` exists; without it they come out unsigned, so sign them with your own key. Please use your own application ID if you publish a build.

Code notes in [CONTRIBUTING.md](CONTRIBUTING.md) are HeliBoard's, and still describe most of the codebase.

## Feedback

Complaints are the roadmap: support@curmudgeon.works

## License

Curmudgeon Keyboard is licensed under the GNU General Public License v3.0, like HeliBoard and OpenBoard before it. See [LICENSE](LICENSE). The complete source of every build is in this repository. If you build on it, keep the attribution, both ours and HeliBoard's.

Since the code is based on the Apache 2.0 licensed AOSP Keyboard, an [Apache 2.0](LICENSE-Apache-2.0) license file is included. HeliBoard's icon, which is still in the source tree, is licensed under [Creative Commons BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) ([license file](LICENSE-CC-BY-SA-4.0)).

## Credits

- **[HeliBoard](https://github.com/HeliBorg/HeliBoard)** and its [contributors](https://github.com/HeliBorg/HeliBoard/graphs/contributors): the keyboard this is a fork of. If you want a keyboard with a large community, their F-Droid build and their [Weblate](https://translate.codeberg.org/projects/heliboard/) (where most of this app's translations come from) are the place to go.
- HeliBoard's own credits, which this fork inherits:
  - Icon by [Fabian OvrWrt](https://github.com/FabianOvrWrt) with contributions from [The Eclectic Dyslexic](https://github.com/the-eclectic-dyslexic)
  - [OpenBoard](https://github.com/openboard-team/openboard)
  - [AOSP Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/)
  - [LineageOS](https://review.lineageos.org/admin/repos/LineageOS/android_packages_inputmethods_LatinIME)
  - [Simple Keyboard](https://github.com/rkkr/simple-keyboard)
  - [Indic Keyboard](https://gitlab.com/indicproject/indic-keyboard)
  - [FlorisBoard](https://github.com/florisboard/florisboard/)
- [AOSP dictionaries](https://codeberg.org/Helium314/aosp-dictionaries) maintained by Helium314
