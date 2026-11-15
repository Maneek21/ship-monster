# Ship Monster: game design (v1)

The rules the app implements. `android/app/src/main/java/dev/shipmonster/app/core/` is the code, this file is the intent. When they disagree, fix one of them.

## The fantasy

You keep a horde of monsters that eat shipped work. Pushes are food. Merged pull requests are treasure. Fed monsters grow from specks into titans. Stop shipping and the horde goes feral, takes your distraction apps hostage, and eventually falls.

## Two layers

| Layer | Driven by | Controls |
|---|---|---|
| The horde clock (the stick) | Time since your last ship | Calm, restless, feral, the fall, and the app lock |
| The pantry (the carrot) | How much you ship | Bellies, growth, evolution, party size, eggs |

The lock only ever follows the horde clock. One ship always lifts it. Shipping too little for a big party never locks anything, it only slows growth.

## The horde clock

Measured in awake hours since the last ship. Rest days do not count.

| State | Awake hours | Effect |
|---|---|---|
| Calm | under 24 | None |
| Restless | 24 to 48 | Warning. Monsters complain |
| Feral | 48 and over | Chosen apps lock. Streak resets to 0. Every party monster drops one stage |
| The fall | 72 hours feral | Party commons wander off for good. Party rares and above turn to stone. A hall of fame entry is written |

- Any push or merged PR returns the horde to calm at once.
- After a fall the clock restarts. A den monster steps up if there is one. Otherwise you pick a new starter egg.
- Rest days default to Saturday and Sunday and can be changed. On a rest day the clock is paused, bellies do not drain, and shipping still pays.
- Hibernate pauses everything for up to 14 days, once every 30 days.

## Currencies

| Currency | From | Spent on |
|---|---|---|
| Chow | Every push. Pushes 1 to 3 of the day give 10, pushes 4 to 6 give 5, later ones give 2. A merged PR also gives 10 | Monsters graze on it automatically. Treats |
| Gems | 1 per merged PR (up to 3 a day). 1 per keeper level. Streak milestones. Weekly bounty: 5 shipping days in a week gives 1 | An egg, a feast, 30 chow, reviving a statue, a truce |

Streak milestone gems: day 7 gives 1, 14 gives 1, 30 gives 2, 60 gives 2, 100 gives 3, 180 gives 5, 365 gives 10.

### Flavours

Chow carries the flavour of when it was pushed, in your local time. Each species has a favourite. A monster that ate its favourite that day grows a little faster. Flavour never affects survival.

| Flavour | Local time |
|---|---|
| Dawn | 05:00 to 10:59 |
| Day | 11:00 to 16:59 |
| Dusk | 17:00 to 22:59 |
| Midnight | 23:00 to 04:59 |

## Feeding

| Mechanic | Rule |
|---|---|
| Belly | 0 to 100 per monster. Drains 50 per awake day |
| Appetite | Chow needed per awake day: common 5, rare 8, epic 12, legendary 16. One chow restores 50 divided by appetite |
| Grazing | Automatic. Whenever there is chow, party monsters eat until full, in party order, favourite flavour first |
| Treat | Costs 2 chow. Adds 10 belly and 1 growth point, 2 if it is the favourite flavour. Three per monster per day |
| Feast | Costs 1 gem. Fills every belly, gives every party monster 10 growth points, revives every statue |
| Stock up | Costs 1 gem. Adds 30 chow |
| Pantry cap | 60 plus 20 per keeper level. Overflow turns into growth for the lead monster, 1 point per 10 chow |
| Underfed | A party monster stuck at an empty belly for 24 awake hours, while the horde is not feral, sulks off to the den. The last party monster never leaves |

## Growth and evolution

Growth points are awarded to each party monster at local midnight.

| Belly at midnight | Growth points |
|---|---|
| Over 50 | 10 |
| 1 to 50 | 4 |
| Empty | 0 |

Plus 2 if it ate its favourite flavour that day. Each star adds 10 percent.

| Stage | Name | Growth points | Days if well fed |
|---|---|---|---|
| 0 | Egg | | |
| 1 | Speck | 0 | 0 |
| 2 | Nibbler | 30 | 3 |
| 3 | Biter | 70 | 7 |
| 4 | Brute | 140 | 14 |
| 5 | Beast | 300 | 30 |
| 6 | Behemoth | 600 | 60 |
| 7 | Colossus | 1000 | 100 |
| 8 | Titan | 1800 | 180 |

Going feral drops each party monster to the start of the stage below. A Titan carries its species epithet.

## Eggs

One gem buys one egg. Tap it to crack it.

| Rarity | Odds | Species |
|---|---|---|
| Common | 60% | 5 |
| Rare | 28% | 4 |
| Epic | 10% | 2 |
| Legendary | 2% | 1 |

- Pity: the 5th egg without a rare or better is at least rare. The 20th without an epic or better is at least epic. The 60th without a legendary is legendary.
- A species you already own is rerolled once, half the time.
- A duplicate gives the owned monster a star, up to 5, and revives it if it is a statue. At 5 stars a duplicate pays 30 chow instead.
- The odds are shown in the app. Gems cannot be bought.

## The keeper

Your level comes from lifetime chow earned.

| Level | Chow | Title | Party slots |
|---|---|---|---|
| 1 | 0 | Hatchling Keeper | 1 |
| 2 | 50 | Stablehand | 2 |
| 3 | 150 | Feeder | 2 |
| 4 | 300 | Wrangler | 3 |
| 5 | 500 | Handler | 3 |
| 6 | 750 | Tamer | 4 |
| 7 | 1050 | Warden | 4 |
| 8 | 1400 | Hordemaster | 5 |
| 9 | 1800 | Beastlord | 5 |
| 10 | 2250 | Monarch of Merges | 5 |

Monsters outside the party rest in the den. They do not eat, grow, or fall.

## Species

| # | Name | Rarity | Shape | Voice | Favourite | Titan epithet |
|---|---|---|---|---|---|---|
| 1 | Mossback | Common | Blob | Needy | Dawn | the Evergreen |
| 2 | Cindermite | Common | Chunk | Gremlin | Midnight | the Unquenched |
| 3 | Puddlewump | Common | Wide | Anxious | Day | the Deep |
| 4 | Gravelkin | Common | Chunk | Deadpan | Dusk | the Immovable |
| 5 | Fuzzlet | Common | Tall | Sleepy | Dawn | the Well Rested |
| 6 | Gloomfang | Rare | Tall | Smug | Midnight | Eater of Deadlines |
| 7 | Brasshorn | Rare | Chunk | Noble | Day | the Gilded |
| 8 | Thornback | Rare | Wide | Gremlin | Dusk | the Thousand Spines |
| 9 | Frostnip | Rare | Blob | Deadpan | Dawn | the Long Winter |
| 10 | Voidling | Epic | Tall | Cryptic | Midnight | the Space Between |
| 11 | Sunmaw | Epic | Wide | Noble | Day | the Second Sun |
| 12 | Mergewyrm | Legendary | Wyrm | Cryptic | Dusk | Born of a Thousand Merges |

The first three commons are the starter choices.

## Dialogue

Lines are pre-written and chosen offline. No AI at runtime.

| Input | Values |
|---|---|
| Voice | Needy, Gremlin, Anxious, Deadpan, Sleepy, Smug, Noble, Cryptic |
| Size | Tiny (stages 1 to 2) speaks in lower case. Mid (3 to 5) as written. Giant (6 to 8) speaks in capitals |
| Situation | Idle states (fed, restless, feral, rest day, hibernating, night, morning), events (fed, merged, egg, hatched, evolved, dropped a stage, treat, favourite food, feast, CI red, CI green again, streak milestone, level up, came back, poked, benched, statue, revived, fall, pantry low, new day) |
| Variables | The monster's name, streak, hours since the last ship, the locked app, chow |

Selection: among lines for the situation, the most specific match wins (voice, then generic). Recent lines are not repeated. The lead monster speaks on the home screen, the widget, notifications and the lock screen.

## First run

1. Three cards explain the deal.
2. Enter a GitHub username.
3. Pick one of three starter eggs.
4. The first sync replays your latest unbroken run of shipping. If you shipped in the last 48 hours the egg hatches at once, with your real streak and up to 30 days of growth. If not, it hatches on your next push.
5. Add the widget. The lock is optional and offered last.

## The lock

Active only while the horde is feral and the lock is switched on.

| Exit | Cost |
|---|---|
| Ship | Free. The lock lifts at the next sync, or at once with "I shipped. Check now" |
| Truce | 1 gem buys 12 hours. Once per feral spell |
| Surrender | The horde falls now |

## Screens

| Screen | Holds |
|---|---|
| Den (home) | The party, the lead monster talking, streak, chow, gems, the horde clock, treat and feast |
| Horde | Every monster you own, the dex of all 12 species, a detail page per monster |
| Nest | Eggs, the odds, feast, stock up |
| Log | What happened, lifetime stats, the hall of fame |
| Settings | GitHub, rest days, lock, notifications, hibernate, test lab |
