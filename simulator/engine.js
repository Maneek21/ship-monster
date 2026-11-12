/* Ship Monster engine: pure state logic, no DOM. Time is simulated milliseconds. */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ShipPetEngine = api;
})(typeof self !== 'undefined' ? self : this, function () {
  var H = 3600e3, D = 24 * H;
  var RULES = { hungryAt: 24, monsterAt: 48, monsterHours: 72, leaflingStreak: 7, bloomStreak: 30 };
  var DEAD_AT = RULES.monsterAt + RULES.monsterHours; // hours since last ship
  var NAMES = ['Pip', 'Mochi', 'Byte', 'Nugget', 'Dot', 'Fig', 'Zip', 'Pebble'];
  var FORMS = ['egg', 'sprout', 'leafling', 'bloom'];

  function newPet(now, n) {
    return {
      name: NAMES[n % NAMES.length], bornAt: now, hatchedAt: null, lastFedAt: null,
      streak: 0, bestStreak: 0, lastShipDay: null, form: 'egg',
      commits: 0, merges: 0, ciFailing: false, dead: null
    };
  }

  function createState(now) {
    return { now: now || 0, petCount: 1, pet: newPet(now || 0, 0), graveyard: [], log: [] };
  }

  function hoursSinceFed(s) {
    var p = s.pet;
    if (p.lastFedAt == null) return 0;
    var end = p.dead ? p.dead.at : s.now;
    return (end - p.lastFedAt) / H;
  }

  function status(s) {
    var p = s.pet;
    if (p.dead) return 'dead';
    if (p.hatchedAt == null) return 'egg';
    var h = hoursSinceFed(s);
    if (h >= RULES.monsterAt) return 'monster';
    if (h >= RULES.hungryAt) return 'hungry';
    return 'happy';
  }

  /* 1 = just fed, 0 = monster threshold reached */
  function fullness(s) {
    var st = status(s);
    if (st === 'egg') return 1;
    if (st === 'dead') return 0;
    return Math.max(0, Math.min(1, 1 - hoursSinceFed(s) / RULES.monsterAt));
  }

  function hoursToNext(s) {
    var st = status(s), h = hoursSinceFed(s);
    if (st === 'happy') return RULES.hungryAt - h;
    if (st === 'hungry') return RULES.monsterAt - h;
    if (st === 'monster') return DEAD_AT - h;
    return null;
  }

  function formFor(p) {
    var target = p.streak >= RULES.bloomStreak ? 'bloom' : p.streak >= RULES.leaflingStreak ? 'leafling' : 'sprout';
    return FORMS.indexOf(target) > FORMS.indexOf(p.form) ? target : p.form;
  }

  function die(s, cause, at) {
    var p = s.pet;
    p.dead = { at: at, cause: cause };
    var start = p.hatchedAt == null ? p.bornAt : p.hatchedAt;
    s.graveyard.unshift({
      name: p.name, form: p.form, cause: cause, diedAt: at,
      days: Math.max(0, Math.floor((at - start) / D)),
      bestStreak: p.bestStreak, ships: p.commits + p.merges
    });
  }

  function ship(s, kind) {
    var p = s.pet;
    if (p.dead) return { ok: false, reason: 'dead' };
    var was = status(s);
    var hatched = false;
    if (p.hatchedAt == null) { p.hatchedAt = s.now; hatched = true; }
    var day = Math.floor(s.now / D);
    if (p.lastShipDay == null || day > p.lastShipDay + 1) p.streak = 1;
    else if (day === p.lastShipDay + 1) p.streak += 1;
    p.lastShipDay = day;
    if (p.streak > p.bestStreak) p.bestStreak = p.streak;
    p.lastFedAt = s.now;
    if (kind === 'merge') p.merges += 1; else p.commits += 1;
    var before = p.form;
    p.form = formFor(p);
    return { ok: true, was: was, hatched: hatched, evolved: before !== p.form && !hatched ? p.form : null, kind: kind === 'merge' ? 'merge' : 'commit' };
  }

  function advance(s, hours) {
    var before = status(s);
    s.now += hours * H;
    var p = s.pet;
    if (!p.dead && p.hatchedAt != null && hoursSinceFed(s) >= DEAD_AT) {
      die(s, 'starved', p.lastFedAt + DEAD_AT * H);
    }
    return { before: before, after: status(s) };
  }

  function mercy(s) {
    if (status(s) !== 'monster') return { ok: false };
    die(s, 'mercy', s.now);
    return { ok: true };
  }

  function hatchNew(s) {
    if (!s.pet.dead) return { ok: false };
    s.pet = newPet(s.now, s.petCount);
    s.petCount += 1;
    return { ok: true };
  }

  function setCI(s, failing) {
    if (s.pet.dead) return { ok: false };
    s.pet.ciFailing = !!failing;
    return { ok: true };
  }

  function epitaph(g) {
    var lived = g.days < 1 ? 'Lived less than a day.' : 'Lived ' + g.days + (g.days === 1 ? ' day.' : ' days.');
    var how = g.cause === 'mercy'
      ? 'Mercy-unlocked so its owner could scroll.'
      : 'Starved after ' + RULES.monsterHours + ' hours as a monster.';
    return 'R.I.P. ' + g.name + '. ' + lived + ' ' + how;
  }

  return {
    H: H, D: D, RULES: RULES, DEAD_AT: DEAD_AT, FORMS: FORMS,
    createState: createState, status: status, hoursSinceFed: hoursSinceFed, fullness: fullness,
    hoursToNext: hoursToNext, ship: ship, advance: advance, mercy: mercy, hatchNew: hatchNew,
    setCI: setCI, epitaph: epitaph
  };
});
