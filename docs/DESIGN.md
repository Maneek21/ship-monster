# Ship Monster: how the app works and why

Decisions made while building the app, with the reason for each. Facts marked "checked" were verified against the live GitHub API or the Android documentation on 12 Nov 2026. Everything else is a design choice. The game rules themselves are in [GAME.md](GAME.md).

## The loop

| Step | What happens |
|---|---|
| You push or merge a pull request | GitHub records it in the repo's activity feed |
| The app syncs (every 15 minutes, on open, or on demand) | Pushes become chow, merged pull requests become chow and a gem, the horde clock resets |
| Monsters graze | Party monsters eat from the pantry on their own. Fed monsters grow at midnight |
| 24 awake hours pass without a ship | Restless. A warning, nothing locks |
| 48 awake hours | Feral. Streak resets, the party shrinks a size, the apps you picked are covered by a lock screen |
| You ship | The horde calms down, apps unlock |
| 72 more hours, or a surrender | The fall. Party commons leave, the rest turn to stone |

## How the code is laid out

| Piece | Where | Why |
|---|---|---|
| The game | `core/` (Dex, Game, Engine, Lines) | Pure Kotlin with no Android in it, so every rule runs in plain unit tests. Time is passed in, never read |
| Saving | `GameStore` | One JSON blob in private preferences. Every read first advances the game to now, so nothing depends on a timer having fired |
| Dialogue | `assets/lines.tsv` and `LineBank` | Lines are data, not code. A line is a pure function of the situation and a seed, so the app and the widget agree without sharing state |
| Art | `art/generate.py` writes `assets/sprites/*.png` | Monsters are drawn by a script: body shape by family, features by species, growing with the stage. Changing a species is a code change, not 56 hand-edited frames |
| Screens | `MainActivity` builds views in code with `Ui` | Four tabs that are rebuilt from the game state on every change. No fragments, no view state to get out of step |
| Background | `SyncJobService`, `TickReceiver`, `PetRuntime` | A 15 minute job for sync, one alarm at the next moment the state can change, and one function that brings the widget, alarm and lock in line |

## Where ships come from

| Option | Result | Decision |
|---|---|---|
| User events feed (`/users/{user}/events`) | Zero setup, but delayed. A push made at 15:09 UTC was still missing from the feed 12 minutes later (checked) | Rejected. A lock that stays shut after you ship is the worst failure this app can have |
| Commit search by author | Misses work pushed by coding agents, which is authored by the agent | Rejected |
| Repo activity feed (`/repos/{repo}/activity`) | Immediate, names who pushed, covers every branch, marks pull request merges (checked) | Used |

How a sync works:

1. One request lists your 15 most recently pushed repos. It uses an ETag, so an unchanged list costs nothing against the rate limit.
2. Only repos whose push time changed get a second request for their activity, filtered to your username.
3. `push`, `force_push` and `branch_creation` count as a commit. `pr_merge` and `merge_queue_merge` count as a merged PR.
4. One more request reads the latest finished Actions run of the repo you last shipped to. A failed run shows up in what the monsters say.

| Mode | Sees | Limit |
|---|---|---|
| Username only | Public repos you own | 60 requests an hour per IP address, shared with others on your network |
| With a token | Also private repos and repos you collaborate on | 5,000 requests an hour |

If the activity feed cannot be read, sync falls back to the commit list of the default branch, where every commit counts because that list does not say who pushed.

Verified on Android 14 and 15 emulators: with a username and no token, the first sync read the activity feed and hatched the starter with the real streak.

## First sync

A new horde should start with your real streak and should never arrive already feral. So the first sync looks back 30 days and replays only the most recent unbroken run of shipping: the newest ship must be under 48 hours old, and no gap inside the run may reach 48 hours. If your last ship is older than that, you get an egg that hatches on your next push.

## The lock

| Constraint | Consequence |
|---|---|
| Android only lets a foreground service start while the app is visible or at boot. On Android 15 the old exception for apps with the overlay permission now needs an overlay already on screen (checked) | The guard service runs for as long as the lock is switched on, not only while the pet is a monster |
| A service that polls all day would drain the battery | It idles until the horde goes feral, then checks which app is in front once a second, and stops checking while the screen is off |
| The Accessibility API is restricted on Google Play | The lock uses usage access plus an overlay window instead |
| No blocker on Android is unbreakable, because the user can revoke its permissions | The exit is offered openly as surrender, and it costs the horde's life |
| A lock that outlives your commit is unacceptable | While the horde is feral the service syncs every 5 minutes, and the lock screen has an "I shipped. Check now" button |

What the lock reads: the package name of the app in front. Nothing inside any app.

What is never offered for locking: this app, the launcher, settings, your default phone and SMS apps, Google Maps, the common Indian payment apps, GitHub, Termux and Claude.

If Android kills the guard and refuses to restart it in the background, and the horde is feral, the app posts a notification asking you to open it. Opening the app re-arms the lock.

## Permissions

| Permission | Why | When asked |
|---|---|---|
| Internet, network state | GitHub sync | Install |
| Run at startup | Keep sync and the lock alive after a reboot | Install |
| Notifications | Restless, feral, calm again, the fall, evolutions, hatchings | When you turn them on |
| Usage access | See which app is in front | When you set up the lock |
| Display over other apps | Show the lock screen | When you set up the lock |
| Foreground service | Keep the guard running | With the lock |

The widget, the horde and sync all work without the lock permissions.

## Privacy

| Data | Where it goes |
|---|---|
| GitHub username | Stored on the phone. Sent to api.github.com |
| GitHub token | Encrypted with a key in the Android Keystore. Sent only to api.github.com. Excluded from backups |
| Which apps you picked, which app is in front | Stays on the phone |
| The game: monsters, pantry, gems, log and hall of fame | Stays on the phone |

There is no server and no analytics.

## What is tested and what is not

| Area | State |
|---|---|
| Game rules: clock, rest days, streaks, bellies, growth, eggs and pity, feral, fall, truce, hibernation, save and load | Unit tests |
| Dialogue bank: every situation has a line for every voice, variables resolve, no repeats | Unit tests |
| Sync parsing, first-sync selection, feeding | Unit tests against sample payloads shaped like the live API |
| Real GitHub sync with a username and no token | Runs on Android 14 and 15 emulators in CI |
| Onboarding, den, nest, horde, log, setup, widget, share image, notifications | Driven and screenshotted on both emulators in CI |
| Lock: overlay over a chosen app, check-now, back to home, re-lock, truce, surrender | Driven on both emulators in CI |
| Reboot: guard and sync job come back | Driven on both emulators in CI |
| Sync with a token, private repos | Not tested |
| A real phone, especially Xiaomi, Oppo, Vivo and Samsung battery managers | Not tested. These can stop background services that stock Android leaves alone |

## Known gaps

| Gap | Note |
|---|---|
| Org repos without a token | Not seen. Add a token |
| Pushes to repos outside your 15 most recent | Not seen until that repo moves up the list |
| Gaming with empty pushes | Allowed. The rules reward any push |
| Shared repos | Only your own pushes count, because activity is filtered to your username |
| Test lab and the game clock | Skipping time moves the game clock ahead of real time. Real pushes are moved by the same amount, so they still feed the horde |
| Play Store | Not submitted. A blocker app needs a policy review of the usage access, overlay and special-use service declarations |
| iOS | Not started |
