# Ship Monster

A horde of pixel monsters that lives on your Android home screen and eats shipped code. Pushes are food. Merged pull requests are treasure. Feed them and they grow from specks into titans. Stop shipping and the horde goes feral, locks your distraction apps, and eventually falls.

**Status: v1.** Every push is built, unit tested, installed on Android 14 and 15 emulators and driven through onboarding, sync, feeding, eggs, the feral lock, truce, surrender, the widget, sharing and a reboot.

## What it does

| Feature | Notes |
|---|---|
| 12 species, 8 sizes each | Speck to Titan. Common, rare, epic and legendary. All drawn in code by `art/generate.py` |
| Chow and the pantry | Every push is chow, flavoured by the time of day. Monsters graze on their own. Each species has a favourite flavour |
| Gems | From merged pull requests, keeper levels, streak milestones and a weekly bounty. Never for sale |
| Eggs | One gem each. Odds are shown, bad luck is capped, twins become stars |
| Growth | A well fed day is 10 growth. Treats, feasts, favourite flavours and stars add more |
| The horde clock | Calm, restless at 24 hours, feral at 48, the fall 72 hours later. Rest days and hibernation pause it |
| Feral lock | Covers the apps you pick until you ship. A truce buys 12 hours for a gem. Surrender ends it at a price |
| Monsters that talk | 270 pre-written lines across 8 voices, chosen by species, size, hunger, streak, time of day and what just happened |
| Home screen widget | Your lead monster, what it is saying, its belly and the state of the horde |
| GitHub sync | Username only for public repos, optional token for private ones. About every 15 minutes, on open, or on demand |
| Keeper levels | Ten ranks. More party slots and a bigger pantry as you go |
| Log and hall of fame | Everything that happened, lifetime stats, titans and fallen hordes |
| Share cards | A square image of any monster or hall of fame entry |
| Test lab | Fake a ship, skip time, add gems or growth |

The rules in full: [docs/GAME.md](docs/GAME.md). How the app is built and why: [docs/DESIGN.md](docs/DESIGN.md).

## What is here

| Folder | What it is |
|---|---|
| `android/` | The Android app, in Kotlin. No UI framework, one small AndroidX dependency |
| `android/app/src/main/java/dev/shipmonster/app/core/` | The game itself: pure Kotlin, no Android, covered by unit tests |
| `android/app/src/main/assets/` | Sprite sheets and the dialogue bank (`lines.tsv`) |
| `art/generate.py` | Draws every monster, egg and icon and writes the sprite sheets |
| `docs/` | Game rules and design notes |
| `ci/device_test.py` | Drives the APK on an emulator and checks the main flows |
| `.github/workflows/android.yml` | Unit tests, debug APK, lint, then the emulator runs on Android 14 and 15 |
| `simulator/` | Browser simulator of the old v0.2 single-pet rules. Kept for reference |

## Try the Android build

Each green build publishes the newest debug APK to the `debug-latest` prerelease:

https://github.com/Maneek21/ship-monster/releases/download/debug-latest/ship-monster-debug.apk

1. Install it and open the app.
2. Enter your GitHub username and pick one of three starter eggs. If you shipped in the last two days it hatches at once.
3. Add the widget when the app offers it.
4. Optional: set up the app lock in Setup. Grant the two permissions and pick the apps to lock.

To see every state without waiting days, use the test lab at the bottom of Setup.

It is a debug build signed with the public key in `android/debug.keystore`. That key is committed on purpose so each build installs over the last one. Do not use it for a real release.

## Build it yourself

Android (needs JDK 17, Gradle 8.10 or newer, and the Android SDK):

```
cd android
gradle testDebugUnitTest assembleDebug
```

Art (needs Python with Pillow):

```
python3 art/generate.py --preview   # rewrites the sprite sheets and writes art/preview/ to look at
```

Simulator:

```
cd simulator
node test_engine.js     # rule tests
python3 build.py        # writes index.html, open it in a browser
```

## Next

1. Run it on real phones, especially ones with aggressive battery managers, and fix what breaks
2. Sign in with GitHub instead of pasting a token
3. Play Store submission, which needs a policy review for a blocker app
4. A proper release signing key

## Licence

GPL-3.0. See `LICENSE`.
