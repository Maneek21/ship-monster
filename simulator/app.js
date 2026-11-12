(function () {
  var E = self.ShipPetEngine, SP = self.ShipPetSprites;
  var $ = function (id) { return document.getElementById(id); };
  var KEY = 'shipmonster-sim-v1';
  var reduce = false;
  try { reduce = matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) {}

  var FORM_LABEL = { egg: 'Egg', sprout: 'Sprout', leafling: 'Leafling', bloom: 'Bloom' };
  var G = {
    feed: '<rect x="5" y="5" width="14" height="4" rx="1"/><rect x="5" y="10.5" width="14" height="4" rx="1"/><rect x="5" y="16" width="14" height="3" rx="1"/>',
    play: '<path d="M9 6l9.5 6L9 18z"/>',
    chirp: '<path d="M4.5 5.5h15v9.5h-7.6l-4.2 3.2V15H4.5z"/>',
    tv: '<rect x="4" y="5.5" width="16" height="10.5" rx="2"/><rect x="8.5" y="17.6" width="7" height="1.8" rx=".9"/>',
    phone: '<path d="M7.6 4h3l1.2 4-2 1.6c1 2 2.6 3.6 4.6 4.6l1.6-2 4 1.2v3c0 1.4-1 2.3-2.4 2.2C10.9 18 6 13.1 5.4 6.400 5.3 5 6.200 4 7.600 4z"/>',
    texts: '<path d="M4 5h16v10.500H10.400L6 19v-3.500H4z"/>',
    pay: '<text x="12" y="17.600" text-anchor="middle" font-size="16" font-weight="700" font-family="system-ui, sans-serif">₹</text>',
    maps: '<path fill-rule="evenodd" d="M12 3a6 6 0 0 1 6 6c0 4.500-6 11.500-6 11.500S6 13.500 6 9a6 6 0 0 1 6-6zm0 3.600a2.400 2.400 0 1 0 0 4.800 2.400 2.400 0 0 0 0-4.800z"/>',
    git: '<circle cx="7.500" cy="6" r="2.300"/><circle cx="7.500" cy="18" r="2.300"/><circle cx="16.500" cy="9" r="2.300"/><rect x="6.700" y="7.500" width="1.600" height="9"/><path d="M8.300 15.200c0-2.600 1.800-3.600 4.300-3.600 1.700 0 2.700-.4 3.100-1.500l1.500.5c-.7 2-2.400 2.600-4.600 2.600-1.800 0-2.700.6-2.700 2z"/>',
    shell: '<path d="M5 7.200l4.800 4.800L5 16.800l1.500 1.500 6.300-6.300-6.300-6.300z"/><rect x="12.800" y="16.300" width="6.700" height="2"/>',
    agent: '<path d="M12 3l2.100 6.600L20.500 12l-6.400 2.300L12 21l-2.100-6.700L3.500 12l6.400-2.400z"/>',
    pet: '<rect x="7" y="9" width="2.600" height="5.500" rx="1.300" fill="#2a1f3d"/><rect x="14.400" y="9" width="2.600" height="5.500" rx="1.300" fill="#2a1f3d"/>'
  };
  var GROUPS = [
    { id: 'fun', cap: 'Distractions, lockable', apps: [
      { name: 'Scrolly', color: '#ff5d8f', glyph: 'feed' }, { name: 'Clipz', color: '#111318', glyph: 'play' },
      { name: 'Chirp', color: '#2aa7ff', glyph: 'chirp' }, { name: 'Bingebox', color: '#c2242c', glyph: 'tv' } ] },
    { id: 'need', cap: 'Essentials, never locked', apps: [
      { name: 'Phone', color: '#23a45f', glyph: 'phone' }, { name: 'Texts', color: '#3b6cf6', glyph: 'texts' },
      { name: 'Pay', color: '#5b2bbf', glyph: 'pay' }, { name: 'Maps', color: '#e08a00', glyph: 'maps' } ] },
    { id: 'ship', cap: 'Shipping tools, never locked', apps: [
      { name: 'Git', color: '#2b2f3a', glyph: 'git' }, { name: 'Shell', color: '#0f766e', glyph: 'shell' },
      { name: 'Agent', color: '#c2603a', glyph: 'agent' }, { name: 'Monster', color: '#ffd447', glyph: 'pet' } ] }
  ];

  var S, frame = 0, joyUntil = 0, lockedApp = null, toastTimer = null, runTimer = null, mercyArmed = false, mercyTimer = null;
  var petCtx = $('petCanvas').getContext('2d'), lockCtx = $('lockCanvas').getContext('2d');

  /* ---------- formatting ---------- */
  function pad(n) { return n < 10 ? '0' + n : '' + n; }
  function dayOf(t) { return Math.floor(t / E.D) + 1; }
  function hourOf(t) { return Math.floor((t % E.D) / E.H); }
  function stamp(t) { return 'Day ' + dayOf(t) + ', ' + pad(hourOf(t)) + ':00'; }
  function dur(h) {
    h = Math.max(0, h);
    if (h < 1) return 'under 1h';
    if (h < 48) return Math.floor(h) + 'h';
    var d = Math.floor(h / 24), r = Math.floor(h % 24);
    return d + 'd' + (r ? ' ' + r + 'h' : '');
  }

  /* ---------- state ---------- */
  function addLog(text) { S.log.unshift({ t: S.now, text: text }); if (S.log.length > 30) S.log.length = 30; }
  function demo() {
    var s = E.createState(9 * E.H);
    S = s;
    E.ship(s, 'commit'); addLog('Pip hatched on its first commit.');
    E.advance(s, 26); E.ship(s, 'commit'); addLog('Commit pushed. Pip ate.');
    E.advance(s, 22); E.ship(s, 'merge'); addLog('PR merged. Feast.');
    E.advance(s, 5);
    return s;
  }
  function valid(d) { return d && typeof d.now === 'number' && d.pet && Array.isArray(d.graveyard) && Array.isArray(d.log); }
  function load() { try { var d = JSON.parse(localStorage.getItem(KEY)); return valid(d) ? d : null; } catch (e) { return null; } }
  function save() { try { localStorage.setItem(KEY, JSON.stringify(S)); } catch (e) {} }

  /* ---------- drawing ---------- */
  function mood(st) {
    if (Date.now() < joyUntil) return 'joy';
    if (st === 'hungry') return 'hungry';
    if (S.pet.ciFailing) return 'sick';
    return frame % 9 === 8 ? 'blink' : 'happy';
  }
  function drawPet() {
    var st = E.status(S);
    var o = st === 'egg' ? { kind: 'egg' } : st === 'dead' ? { kind: 'tomb' } : st === 'monster' ? { kind: 'monster' }
      : { kind: 'pet', form: S.pet.form, mood: mood(st) };
    o.frame = frame; o.still = reduce && Date.now() >= joyUntil;
    SP.draw(petCtx, o);
    if (lockedApp) SP.draw(lockCtx, { kind: 'monster', frame: frame, still: reduce });
  }

  /* ---------- toast ---------- */
  function toast(msg) {
    var t = $('toast');
    t.textContent = msg; t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.classList.remove('show'); }, 2600);
  }

  /* ---------- apps ---------- */
  function buildApps() {
    var root = $('apps');
    GROUPS.forEach(function (g) {
      var wrap = document.createElement('div');
      var cap = document.createElement('p'); cap.className = 'app-cap'; cap.textContent = g.cap; wrap.appendChild(cap);
      var row = document.createElement('div'); row.className = 'app-row';
      g.apps.forEach(function (a) {
        var b = document.createElement('button');
        b.type = 'button'; b.className = 'app'; b.dataset.group = g.id; b.dataset.name = a.name;
        var ic = document.createElement('span'); ic.className = 'icon'; ic.style.background = a.color;
        ic.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true">' + G[a.glyph] + '</svg>';
        var nm = document.createElement('span'); nm.className = 'app-name'; nm.textContent = a.name;
        b.appendChild(ic); b.appendChild(nm);
        b.addEventListener('click', function () { openApp(g.id, a.name); });
        row.appendChild(b);
      });
      wrap.appendChild(row); root.appendChild(wrap);
    });
  }
  function openApp(group, name) {
    var st = E.status(S), pn = S.pet.name;
    if (name === 'Monster') return toast('That is this app. The widget above is the whole point.');
    if (group === 'need') return toast(name + ' always opens. Essentials are never locked.');
    if (group === 'ship') return toast(name + ' always opens. You need it to feed ' + pn + '.');
    if (st === 'monster') { lockedApp = name; mercyArmed = false; clearTimeout(toastTimer); $('toast').classList.remove('show'); render(); return; }
    if (st === 'dead') return toast(name + ' opened. Nobody is guarding it now.');
    if (st === 'egg') return toast(name + ' opened. The egg cannot stop you yet.');
    if (st === 'hungry') return toast(name + ' opened. ' + pn + ' watches you scroll, hungry.');
    toast(name + ' opened. ' + pn + ' is fed, so scroll away.');
  }

  /* ---------- actions ---------- */
  function ship(kind) {
    var name = S.pet.name;
    var r = E.ship(S, kind);
    if (!r.ok) return toast(name + ' is dead. Hatch a new egg first.');
    joyUntil = Date.now() + (kind === 'merge' ? 2800 : 1500);
    var msg;
    if (r.hatched) msg = name + ' hatched on its first commit.';
    else if (r.was === 'monster') msg = (kind === 'merge' ? 'PR merged. ' : 'Commit pushed. ') + name + ' turned back. Apps unlocked.';
    else if (kind === 'merge') msg = 'PR merged. Feast.';
    else msg = 'Commit pushed. ' + name + ' ate.';
    addLog(msg);
    if (r.evolved) { addLog(name + ' grew into a ' + FORM_LABEL[r.evolved] + ' on a ' + S.pet.streak + '-day streak.'); msg = name + ' grew into a ' + FORM_LABEL[r.evolved] + '.'; }
    if (lockedApp) { msg = lockedApp + ' is open again. ' + name + ' turned back.'; lockedApp = null; }
    render(); toast(msg);
  }
  function advance(hours) {
    var name = S.pet.name;
    var r = E.advance(S, hours);
    if (r.before !== r.after) {
      if (r.after === 'hungry') { addLog(name + ' is hungry. 24 hours without a ship.'); toast(name + ' is hungry.'); }
      else if (r.after === 'monster') { addLog(name + ' turned into a monster. Distraction apps are locked.'); toast(name + ' turned into a monster.'); }
      else if (r.after === 'dead') { addLog(name + ' starved after 72 hours as a monster.'); toast(name + ' has died.'); lockedApp = null; stopRun(); }
    }
    render();
  }
  function toggleCI() {
    var next = !S.pet.ciFailing;
    if (!E.setCI(S, next).ok) return toast('No pet to make sick.');
    addLog(next ? 'CI is failing. ' + S.pet.name + ' feels sick.' : 'CI is green again.');
    render();
  }
  function mercy() {
    var name = S.pet.name;
    if (!mercyArmed) {
      mercyArmed = true; render();
      clearTimeout(mercyTimer);
      mercyTimer = setTimeout(function () { mercyArmed = false; render(); }, 4000);
      return;
    }
    clearTimeout(mercyTimer); mercyArmed = false;
    var app = lockedApp;
    if (E.mercy(S).ok) {
      addLog('Mercy unlock used. ' + name + ' died so ' + (app || 'the apps') + ' could open.');
      lockedApp = null; stopRun(); render(); toast((app || 'Apps') + ' unlocked. ' + name + ' is gone.');
    }
  }
  function hatch() {
    if (E.hatchNew(S).ok) { addLog('A new egg arrived. Its name is ' + S.pet.name + '.'); render(); toast('New egg. Push a commit to hatch it.'); }
  }
  function poke() {
    var st = E.status(S), n = S.pet.name;
    if (st === 'dead') return toast('Rest in peace, ' + n + '.');
    if (st === 'egg') return toast('The egg wobbles.');
    if (st === 'monster') return toast('It bit you.');
    joyUntil = Date.now() + 900; drawPet(); toast(n + ' wiggles.');
  }
  function stopRun() { if (runTimer) { clearInterval(runTimer); runTimer = null; } }
  function toggleRun() {
    if (runTimer) stopRun();
    else if (E.status(S) !== 'dead') runTimer = setInterval(function () { advance(1); }, 1000);
    render();
  }
  function reset() { stopRun(); lockedApp = null; joyUntil = 0; demo(); render(); toast('Back to the demo: Pip, day 3.'); }

  /* ---------- render ---------- */
  function render() {
    var st = E.status(S), p = S.pet, h = E.hoursSinceFed(S), full = E.fullness(S);
    if (st !== 'monster') lockedApp = null;
    $('screen').dataset.status = st;
    $('sbClock').textContent = pad(hourOf(S.now)) + ':00';

    $('wName').textContent = st === 'egg' ? p.name + ', still an egg' : st === 'dead' ? 'R.I.P. ' + p.name : p.name + ' the ' + FORM_LABEL[p.form];
    var kind = st === 'happy' && p.ciFailing ? 'sick' : st;
    var pill = $('wPill'); pill.dataset.kind = kind;
    pill.textContent = { egg: 'Egg', happy: 'Happy', sick: 'Sick, CI red', hungry: 'Hungry', monster: 'Monster', dead: 'Dead' }[kind];
    var belly = $('wBelly');
    belly.style.width = Math.round(full * 100) + '%';
    belly.dataset.level = full > 0.5 ? 'high' : full > 0 ? 'mid' : 'low';
    $('wBellyWrap').hidden = st === 'dead' || st === 'egg';
    $('wBellyWrap').setAttribute('aria-label', 'Belly ' + Math.round(full * 100) + ' percent full');
    $('wMeta').textContent = st === 'egg' ? 'Push a commit to hatch it.'
      : st === 'dead' ? (p.dead.cause === 'mercy' ? 'Mercy-unlocked.' : 'Starved as a monster.')
      : st === 'monster' ? 'Starving. Dies in ' + dur(E.hoursToNext(S)) + '.'
      : p.streak + '-day streak, fed ' + dur(h) + ' ago';
    $('hatch').hidden = st !== 'dead';

    var apps = document.querySelectorAll('.app');
    for (var i = 0; i < apps.length; i++) {
      var locked = st === 'monster' && apps[i].dataset.group === 'fun';
      apps[i].dataset.locked = locked ? 'true' : 'false';
      apps[i].setAttribute('aria-label', apps[i].dataset.name + (locked ? ', locked' : ''));
    }

    var lock = $('lock');
    lock.hidden = !lockedApp;
    if (lockedApp) {
      $('lockText').textContent = lockedApp + ' is closed. ' + p.name + ' has not eaten in ' + dur(h) + '. Push a commit and it turns back.';
      $('lockCount').textContent = 'Dies in ' + dur(E.hoursToNext(S));
      var mb = $('lockMercy');
      mb.dataset.armed = mercyArmed ? 'true' : 'false';
      mb.textContent = mercyArmed ? 'Tap again. ' + p.name + ' will die.' : 'Mercy unlock';
      $('lockFine').textContent = 'Mercy unlock opens ' + lockedApp + ' now and kills ' + p.name + '.';
    }

    $('lcdTime').textContent = 'DAY ' + dayOf(S.now) + ' ' + pad(hourOf(S.now)) + ':00';
    $('lcdFed').textContent = p.lastFedAt == null ? 'never' : st === 'dead' ? 'too late' : dur(h) + ' ago';
    var next = E.hoursToNext(S);
    $('lcdNext').textContent = st === 'egg' ? 'Push a commit to hatch'
      : st === 'happy' ? 'Hungry in ' + dur(next)
      : st === 'hungry' ? 'Monster in ' + dur(next)
      : st === 'monster' ? 'Dies in ' + dur(next) : 'Hatch a new egg to go again';
    var needle = $('needle');
    needle.hidden = st === 'egg';
    needle.style.left = Math.min(100, (h / E.DEAD_AT) * 100) + '%';
    $('track').setAttribute('aria-label', st === 'egg' ? 'Not hatched yet' : Math.floor(h) + ' hours since last ship, ' + st);

    var dead = st === 'dead';
    $('kCommit').disabled = dead; $('kMerge').disabled = dead; $('kCI').disabled = dead || st === 'egg';
    $('kCI').textContent = p.ciFailing ? 'Fix CI' : 'Break CI';
    $('kCI').setAttribute('aria-pressed', p.ciFailing ? 'true' : 'false');
    $('kRun').disabled = dead;
    $('kRun').textContent = runTimer ? 'Pause clock' : 'Run clock';
    $('kRun').setAttribute('aria-pressed', runTimer ? 'true' : 'false');

    var log = $('log'); log.textContent = '';
    S.log.slice(0, 8).forEach(function (e) {
      var li = document.createElement('li'), tm = document.createElement('time'), sp = document.createElement('span');
      tm.textContent = stamp(e.t); sp.textContent = e.text; li.appendChild(tm); li.appendChild(sp); log.appendChild(li);
    });

    var graves = $('graves'); graves.textContent = '';
    $('noGraves').hidden = S.graveyard.length > 0;
    S.graveyard.forEach(function (g) {
      var li = document.createElement('li'); li.className = 'grave';
      var c = document.createElement('canvas'); c.width = 16; c.height = 16; SP.draw(c.getContext('2d'), { kind: 'tomb' });
      var box = document.createElement('div'), tx = document.createElement('p'), bt = document.createElement('button');
      var text = E.epitaph(g);
      tx.textContent = text + ' Best streak ' + g.bestStreak + (g.bestStreak === 1 ? ' day.' : ' days.');
      bt.type = 'button'; bt.className = 'copy'; bt.textContent = 'Copy epitaph';
      bt.addEventListener('click', function () {
        var done = function () { bt.textContent = 'Copied'; setTimeout(function () { bt.textContent = 'Copy epitaph'; }, 1600); };
        var fail = function () { toast('Copy is blocked here. Long-press the text to copy it.'); };
        try { navigator.clipboard.writeText(text).then(done, fail); } catch (e) { fail(); }
      });
      box.appendChild(tx); box.appendChild(bt); li.appendChild(c); li.appendChild(box); graves.appendChild(li);
    });

    drawPet();
    save();
  }

  /* ---------- boot ---------- */
  function start(data) {
    S = valid(data) ? data : load();
    if (!S) demo();
    buildApps();
    $('poke').addEventListener('click', poke);
    $('hatch').addEventListener('click', hatch);
    $('kCommit').addEventListener('click', function () { ship('commit'); });
    $('kMerge').addEventListener('click', function () { ship('merge'); });
    $('kCI').addEventListener('click', toggleCI);
    $('kH1').addEventListener('click', function () { advance(1); });
    $('kH6').addEventListener('click', function () { advance(6); });
    $('kD1').addEventListener('click', function () { advance(24); });
    $('kRun').addEventListener('click', toggleRun);
    $('kReset').addEventListener('click', reset);
    $('lockHome').addEventListener('click', function () { lockedApp = null; mercyArmed = false; render(); });
    $('lockMercy').addEventListener('click', mercy);
    render();
    setInterval(function () { frame++; if (!reduce || Date.now() < joyUntil + 600) drawPet(); }, 480);
    try { if (self.claude && self.claude.hot && self.claude.hot.snapshot) self.claude.hot.snapshot(function () { return S; }); } catch (e) {}
  }
  var hot = self.claude && self.claude.hot;
  if (hot && hot.ready) hot.ready(start); else start(hot && hot.data ? hot.data : null);
})();
