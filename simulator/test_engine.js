const E = require('./engine.js');
const assert = require('assert');
let n = 0;
function t(name, fn) { fn(); n++; console.log('ok  ' + name); }
const fresh = () => E.createState(9 * E.H);
const hatched = () => { const s = fresh(); E.ship(s, 'commit'); return s; };

t('new pet is an egg and never starves', () => {
  const s = fresh();
  assert.equal(E.status(s), 'egg');
  E.advance(s, 24 * 30);
  assert.equal(E.status(s), 'egg');
  assert.equal(s.graveyard.length, 0);
});
t('first commit hatches a sprout', () => {
  const s = fresh();
  const r = E.ship(s, 'commit');
  assert.ok(r.ok && r.hatched);
  assert.equal(E.status(s), 'happy');
  assert.equal(s.pet.form, 'sprout');
  assert.equal(s.pet.streak, 1);
});
t('happy until 24h, hungry until 48h, then monster', () => {
  const s = hatched();
  E.advance(s, 23.9); assert.equal(E.status(s), 'happy');
  E.advance(s, 0.1); assert.equal(E.status(s), 'hungry');
  E.advance(s, 23.9); assert.equal(E.status(s), 'hungry');
  E.advance(s, 0.1); assert.equal(E.status(s), 'monster');
});
t('dies after 72h as a monster, at the exact hour even on a big jump', () => {
  const s = hatched();
  const fed = s.pet.lastFedAt;
  E.advance(s, 119.9); assert.equal(E.status(s), 'monster');
  E.advance(s, 500);
  assert.equal(E.status(s), 'dead');
  assert.equal(s.pet.dead.cause, 'starved');
  assert.equal(s.pet.dead.at, fed + 120 * E.H);
  assert.equal(s.graveyard.length, 1);
  assert.equal(s.graveyard[0].days, 5);
});
t('a commit turns a monster back and resets the clock', () => {
  const s = hatched();
  E.advance(s, 60);
  const r = E.ship(s, 'commit');
  assert.equal(r.was, 'monster');
  assert.equal(E.status(s), 'happy');
  assert.equal(E.hoursSinceFed(s), 0);
  assert.equal(E.fullness(s), 1);
});
t('mercy unlock only works on a monster and kills the pet', () => {
  const s = hatched();
  assert.equal(E.mercy(s).ok, false);
  E.advance(s, 30); assert.equal(E.mercy(s).ok, false);
  E.advance(s, 30); assert.equal(E.mercy(s).ok, true);
  assert.equal(E.status(s), 'dead');
  assert.equal(s.graveyard[0].cause, 'mercy');
});
t('dead pets ignore ships, CI and mercy', () => {
  const s = hatched(); E.advance(s, 200);
  assert.equal(E.ship(s, 'commit').ok, false);
  assert.equal(E.setCI(s, true).ok, false);
  assert.equal(E.mercy(s).ok, false);
  assert.equal(s.graveyard.length, 1);
});
t('streak counts consecutive calendar days, same day does not double count', () => {
  const s = hatched();            // day 0
  E.ship(s, 'commit'); assert.equal(s.pet.streak, 1);
  E.advance(s, 24); E.ship(s, 'commit'); assert.equal(s.pet.streak, 2);
  E.advance(s, 24); E.ship(s, 'merge'); assert.equal(s.pet.streak, 3);
});
t('a skipped day resets the streak but keeps the best', () => {
  const s = hatched();
  E.advance(s, 24); E.ship(s, 'commit');
  E.advance(s, 50); E.ship(s, 'commit');
  assert.equal(s.pet.streak, 1);
  assert.equal(s.pet.bestStreak, 2);
});
t('evolves at 7 and 30 day streaks and never regresses', () => {
  const s = hatched();
  let ev = null;
  for (let i = 0; i < 6; i++) { E.advance(s, 24); ev = E.ship(s, 'commit').evolved; }
  assert.equal(s.pet.streak, 7); assert.equal(s.pet.form, 'leafling'); assert.equal(ev, 'leafling');
  E.advance(s, 60); E.ship(s, 'commit');
  assert.equal(s.pet.streak, 1); assert.equal(s.pet.form, 'leafling');
  for (let i = 0; i < 29; i++) { E.advance(s, 24); E.ship(s, 'commit'); }
  assert.equal(s.pet.streak, 30); assert.equal(s.pet.form, 'bloom');
});
t('hatching a new egg needs a dead pet and rotates the name', () => {
  const s = hatched();
  assert.equal(E.hatchNew(s).ok, false);
  E.advance(s, 130);
  assert.equal(E.hatchNew(s).ok, true);
  assert.equal(E.status(s), 'egg');
  assert.equal(s.pet.name, 'Mochi');
  assert.equal(s.graveyard[0].name, 'Pip');
});
t('fullness and next-state countdown track the thresholds', () => {
  const s = hatched();
  E.advance(s, 12); assert.equal(E.fullness(s), 0.75); assert.equal(E.hoursToNext(s), 12);
  E.advance(s, 24); assert.equal(E.fullness(s), 0.25); assert.equal(E.hoursToNext(s), 12);
  E.advance(s, 24); assert.equal(E.fullness(s), 0); assert.equal(E.hoursToNext(s), 60);
});
t('CI flag toggles and a commit does not clear it', () => {
  const s = hatched();
  E.setCI(s, true); E.ship(s, 'commit'); assert.equal(s.pet.ciFailing, true);
  E.setCI(s, false); assert.equal(s.pet.ciFailing, false);
});
t('epitaphs read correctly', () => {
  assert.equal(E.epitaph({ name: 'Pip', days: 9, cause: 'starved' }), 'R.I.P. Pip. Lived 9 days. Starved after 72 hours as a monster.');
  assert.equal(E.epitaph({ name: 'Dot', days: 1, cause: 'mercy' }), 'R.I.P. Dot. Lived 1 day. Mercy-unlocked so its owner could scroll.');
  assert.equal(E.epitaph({ name: 'Fig', days: 0, cause: 'mercy' }), 'R.I.P. Fig. Lived less than a day. Mercy-unlocked so its owner could scroll.');
});
t('state survives a JSON round trip', () => {
  const s = hatched(); E.advance(s, 30);
  const c = JSON.parse(JSON.stringify(s));
  assert.equal(E.status(c), 'hungry');
  E.ship(c, 'commit'); assert.equal(E.status(c), 'happy');
});
console.log('\n' + n + ' tests passed');
