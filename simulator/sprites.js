/* Ship Monster sprites: 16x16 pixel grids drawn to a 16x16 canvas, scaled up in CSS. */
(function (root) {
  var PAL = {
    o: '#2a1f3d', y: '#ffd447', Y: '#f0a31f', p: '#ff8fa3', g: '#5fd07a', G: '#2f9550',
    f: '#ff7eb6', c: '#fff0a0', b: '#6ec3ff', w: '#fffaf0',
    e: '#fff3d6', E: '#ecd3a0',
    m: '#9a5cff', M: '#6a35d6', r: '#ff3b4e', h: '#ffe2a0', s: '#d7dbe3',
    t: '#b4bac6', T: '#7b8394'
  };
  var SICK = { y: '#cfe59a', Y: '#9cc266' };

  var SPROUT = [
    '................',
    '................',
    '........gG......',
    '.......gg.......',
    '.......G........',
    '....oooooooo....',
    '...oyyyyyyyyo...',
    '..oyyyyyyyyyyo..',
    '..oyyyyyyyyyyo..',
    '..oyyyyyyyyyyo..',
    '..oyyyyyyyyyyo..',
    '..oYyyyyyyyyYo..',
    '...oYYYYYYYYo...',
    '....oooooooo....',
    '....oo....oo....',
    '................'
  ];
  var LEAFLING = [
    '.....gG..Gg.....',
    '......gGGg......',
    '.......GG.......',
    '...oooooooooo...',
    '..oyyyyyyyyyyo..',
    '.oyyyyyyyyyyyyo.',
    '.oyyyyyyyyyyyyo.',
    '.oyyyyyyyyyyyyo.',
    '.oyyyyyyyyyyyyo.',
    'ooyyyyyyyyyyyyoo',
    '.oyyyyyyyyyyyyo.',
    '.oYyyyyyyyyyyYo.',
    '..oYYYYYYYYYYo..',
    '...oooooooooo...',
    '...ooo....ooo...',
    '................'
  ];
  var BLOOM = LEAFLING.slice();
  BLOOM[0] = '......ffff......';
  BLOOM[1] = '.....ffccff.....';
  BLOOM[2] = '......fGGf......';

  var EGG = [
    '................',
    '................',
    '......oooo......',
    '.....oeeeeo.....',
    '....oeeeeeeo....',
    '....oeeyeeeo....',
    '...oeeeeeeeeo...',
    '...oeeeeeyyeo...',
    '...oeyeeeyyeo...',
    '...oeeeeeeeeo...',
    '...oeeeeeeeeo...',
    '...oEeeeeeeEo...',
    '....oEEEEEEo....',
    '.....oooooo.....',
    '................',
    '................'
  ];
  var MONSTER = [
    '..h.......h.....',
    '..hh.....hh.....',
    '..ooooooooo.....',
    '.ommmmmmmmmo....',
    '.ommmmmmmmmo.s.s',
    '.omrrmmmrrmo.s.s',
    '.omrommmormo.sss',
    '.ommmmmmmmmo..s.',
    '.owowowowowo..s.',
    '.ooooooooooomms.',
    '.oowowowowoo..s.',
    '.ommmmmmmmmo..s.',
    '.oMMMMMMMMMo....',
    '..ooooooooo.....',
    '..ooo...ooo.....',
    '................'
  ];
  var TOMB = [
    '................',
    '...oooooooooo...',
    '..otttttttttto..',
    '.otttttttttttto.',
    '.ottTTTtTtTTTto.',
    '.ottTtTtTtTtTto.',
    '.ottTTttTtTTTto.',
    '.ottTtTtTtTttto.',
    '.ottTtTtTtTttto.',
    '.otttttttttttto.',
    '.otttttttttttto.',
    '.oTTTTTTTTTTTTo.',
    '.oooooooooooooo.',
    'gGggGgGGgGggGgGg',
    '................',
    '................'
  ];

  var BODY = { sprout: SPROUT, leafling: LEAFLING, bloom: BLOOM };
  var ALL = { sprout: SPROUT, leafling: LEAFLING, bloom: BLOOM, egg: EGG, monster: MONSTER, tomb: TOMB };

  function paint(ctx, grid, pal, dx, dy) {
    for (var y = 0; y < 16; y++) {
      var row = grid[y];
      for (var x = 0; x < 16; x++) {
        var ch = row.charAt(x);
        if (ch === '.') continue;
        ctx.fillStyle = pal[ch] || PAL[ch];
        ctx.fillRect(x + dx, y + dy, 1, 1);
      }
    }
  }

  function face(ctx, mood, dx, dy, frame) {
    var ex1 = 5, ex2 = 10, ey = 7, my = 10;
    function px(x, y, col) { ctx.fillStyle = col; ctx.fillRect(x + dx, y + dy, 1, 1); }
    var ink = PAL.o;
    if (mood !== 'sick' && mood !== 'hungry') { px(ex1 - 1, ey + 2, PAL.p); px(ex2 + 1, ey + 2, PAL.p); }
    if (mood === 'joy') {
      px(ex1 - 1, ey + 1, ink); px(ex1, ey, ink); px(ex1 + 1, ey + 1, ink);
      px(ex2 - 1, ey + 1, ink); px(ex2, ey, ink); px(ex2 + 1, ey + 1, ink);
      px(6, my, ink); px(7, my, ink); px(8, my, ink); px(9, my, ink);
      px(7, my + 1, PAL.p); px(8, my + 1, PAL.p);
      if (frame % 2 === 0) { px(1, 3 - dy, PAL.c); px(14, 5 - dy, PAL.c); px(13, 1 - dy, PAL.w); }
      else { px(2, 1 - dy, PAL.w); px(15, 3 - dy, PAL.c); px(0, 6 - dy, PAL.c); }
    } else if (mood === 'hungry') {
      px(ex1 + 1, ey, ink); px(ex1, ey + 1, ink); px(ex1 - 1, ey + 1, ink);
      px(ex2 - 1, ey, ink); px(ex2, ey + 1, ink); px(ex2 + 1, ey + 1, ink);
      px(7, my, ink); px(8, my, ink); px(6, my + 1, ink); px(9, my + 1, ink);
      px(14, 2, PAL.b); px(14, 3, PAL.b);
    } else if (mood === 'sick') {
      px(ex1 - 1, ey + 1, ink); px(ex1, ey + 1, ink); px(ex2, ey + 1, ink); px(ex2 + 1, ey + 1, ink);
      px(6, my, ink); px(7, my + 1, ink); px(8, my, ink); px(9, my + 1, ink);
    } else {
      if (mood === 'blink') {
        px(ex1 - 1, ey + 1, ink); px(ex1, ey + 1, ink); px(ex2, ey + 1, ink); px(ex2 + 1, ey + 1, ink);
      } else {
        px(ex1, ey, ink); px(ex1, ey + 1, ink); px(ex2, ey, ink); px(ex2, ey + 1, ink);
      }
      px(6, my, ink); px(7, my + 1, ink); px(8, my + 1, ink); px(9, my, ink);
    }
  }

  /* o: { kind: 'pet'|'egg'|'monster'|'tomb', form, mood, frame, still } */
  function draw(ctx, o) {
    var frame = o.still ? 0 : (o.frame || 0);
    ctx.clearRect(0, 0, 16, 16);
    if (o.kind === 'tomb') return paint(ctx, TOMB, PAL, 0, 0);
    if (o.kind === 'monster') return paint(ctx, MONSTER, PAL, 0, frame % 2);
    if (o.kind === 'egg') {
      var w = frame % 4;
      return paint(ctx, EGG, PAL, w === 1 ? 1 : w === 3 ? -1 : 0, 0);
    }
    var dy = frame % 2;
    var pal = o.mood === 'sick' ? Object.assign({}, PAL, SICK) : PAL;
    paint(ctx, BODY[o.form] || SPROUT, pal, 0, dy);
    face(ctx, o.mood || 'happy', 0, dy, frame);
  }

  var api = { draw: draw, ALL: ALL, PAL: PAL };
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ShipPetSprites = api;
})(typeof self !== 'undefined' ? self : this);
