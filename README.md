# Ship Monster

A pet that lives on your Android home screen and eats your commits. Stop shipping and it turns into a monster that locks your distraction apps. Leave it a monster too long and it dies.

**Status: early prototype.** The pet rules, sprites and widget work. GitHub sync and the app lock are not built yet.

## What is here

| Folder | What it is | State |
|---|---|---|
| `simulator/` | Browser simulator: a fake home screen with the widget, monster lock and mercy unlock, driven by buttons | Working |
| `android/` | Android app (Kotlin): pet engine, pixel sprites, home screen widget, test screen with fake-commit and skip-time buttons | v0, no GitHub sync, no lock |
| `.github/workflows/android.yml` | Builds a debug APK on every push that touches `android/` | See the Actions tab |

## The rules

| State | Trigger | Effect |
|---|---|---|
| Happy | Shipped in the last 24 hours | None |
| Hungry | 24 hours without a ship | A warning. Nothing locks |
| Monster | 48 hours without a ship | Chosen distraction apps lock until you ship (planned) |
| Dead | 72 hours as a monster, or a mercy unlock | New egg, streak resets, pet goes to the graveyard |

The pet grows with your streak: Sprout, then Leafling at 7 days, then Bloom at 30.

These rules live in two places that must stay in step: `simulator/engine.js` and `android/app/src/main/java/dev/shipmonster/app/PetEngine.kt`. Both have tests covering the same cases.

## Try the Android build

Each green build publishes the newest debug APK to the `debug-latest` prerelease:

https://github.com/Maneek21/ship-monster/releases/download/debug-latest/ship-monster-debug.apk

Install it, open the app, then add the widget: long press the home screen, Widgets, Ship Monster. Use the test buttons to fake commits and skip time.

It is a debug build signed with the public key in `android/debug.keystore`. That key is committed on purpose so each build installs over the last one. Do not use it for a real release.

## Build it yourself

Android (needs JDK 17, Gradle 8.10 or newer, and the Android SDK):

```
cd android
gradle testDebugUnitTest assembleDebug
```

Simulator:

```
cd simulator
node test_engine.js     # rule tests
python3 build.py        # writes index.html, open it in a browser
```

## Next

1. GitHub sign-in and polling, so real commits feed the pet
2. The monster lock: usage access, an overlay, an allowlist, and the mercy unlock
3. Share card for the graveyard
4. Background refresh that reacts faster than the widget's 30 minute tick

## Licence

GPL-3.0. See `LICENSE`.
