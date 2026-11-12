# Ship Monster

A pet that lives on your Android home screen and eats your commits. Stop shipping and it turns into a monster that locks your distraction apps. Leave it a monster too long and it dies.

**Status: v0.2, feature complete but untested on a real phone.** Everything compiles and the logic has unit tests. Nobody has run it on a device yet, so expect rough edges in the screens and the lock.

## What it does

| Feature | State |
|---|---|
| Home screen widget with the pet, its mood, belly and streak | Built |
| GitHub sync: pushes and merged pull requests feed the pet | Built. Username only for public repos, optional token for private ones |
| Background sync about every 15 minutes | Built |
| Notifications when the pet gets hungry, turns monster, turns back or dies | Built |
| Monster lock: covers the apps you pick until you ship | Built |
| Mercy unlock: get your apps back now, at the cost of the pet | Built |
| Sick pet when your last CI run failed | Built |
| Share image for the pet and for each grave | Built |
| Test lab: fake a ship or skip time | Built |
| Browser simulator of the rules | Built, in `simulator/` |

How it works and why it is built this way: [docs/DESIGN.md](docs/DESIGN.md).

## What is here

| Folder | What it is |
|---|---|
| `android/` | The Android app, in Kotlin. No UI framework, one small AndroidX dependency |
| `simulator/` | Browser simulator: a fake home screen with the widget, monster lock and mercy unlock, driven by buttons |
| `docs/` | Design notes |
| `.github/workflows/android.yml` | Runs the unit tests and builds a debug APK on every push that touches `android/` |

## The rules

| State | Trigger | Effect |
|---|---|---|
| Happy | Shipped in the last 24 hours | None |
| Hungry | 24 hours without a ship | A warning. Nothing locks |
| Monster | 48 hours without a ship | The apps you picked lock until you ship |
| Dead | 72 hours as a monster, or a mercy unlock | New egg, streak resets, pet goes to the graveyard |

The pet grows with your streak: Sprout, then Leafling at 7 days, then Bloom at 30.

These rules live in two places that must stay in step: `simulator/engine.js` and `android/app/src/main/java/dev/shipmonster/app/PetEngine.kt`. Both have tests covering the same cases.

## Try the Android build

Each green build publishes the newest debug APK to the `debug-latest` prerelease:

https://github.com/Maneek21/ship-monster/releases/download/debug-latest/ship-monster-debug.apk

1. Install it and open the app.
2. Tap "Connect GitHub" and enter your username. Add a token if you want private repos to count.
3. Tap "Add the home screen widget".
4. Optional: tap "Arm the monster lock", grant the two permissions, and pick the apps to lock.

To see every state without waiting days, use the test lab at the bottom of Setup.

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

1. Run it on real phones and fix what breaks
2. Sign in with GitHub instead of pasting a token
3. Play Store submission, which needs a policy review for a blocker app
4. A proper release signing key

## Licence

GPL-3.0. See `LICENSE`.
