// Ollee Watch Forge - PULSE
// Phone music remote, YouTube search, volume and notification ticker.
// Needs the Pulse app on the phone. Buttons: tap = quick press, hold = 0.7 s.
//
// MUSIC (default screen)
//   A play / pause    L next    L L previous    hold L volume    hold A menu
//   Playing  equalizer bars dance, the colon flashes on the beat, PM is lit,
//            then "ARTIST - TITLE" scrolls by. Day digits = % of the song played.
//   Paused   the bars sink into a flat line, PAUSEd and the song name take turns.
//   Skip     a streak shoots across the digits (nEXt / PrEV).
//   LAP lit = unread notifications. SEArCH + bouncing dot = no phone.
//
// MENU (hold A)   L next item, A open, hold L back.   SEArCH / InbOX / battery
//   The third item is the watch battery voltage (e.g. 3.01 V). It is also sent
//   to the phone when the face opens and once an hour, so the app can show a trend.
//
// BATTERY SAVING  The 20 frames/s animation only runs while you use the face.
//   10 s after the last press or news the music screen drops to one frame a
//   second: the title creeps by, the colon ticks. Any press, notification, call
//   or song change wakes it. (A slow timer is kept on purpose: with no timer at
//   all the watch can sleep so deeply that it drops the Bluetooth link.)
//   Menus, inbox and search go back to music after 30 s untouched.
//
// SEARCH (YouTube)
//   Typing   A next letter, L jump to the next group (A F K P U 0 space),
//            stop pressing for 1 s to lock the letter in, hold L delete,
//            hold A see the suggestions. The phone fetches YouTube's own
//            suggestions after every letter; day digits = how many are waiting.
//   Results  each suggestion slides in and scrolls, day digits = its number.
//            A play it, L next one, hold L back to typing.
//   Then a segment orbits the digits while the phone looks the video up, the
//   title collapses into a dot that drops, and the bars burst on as it plays.
//   nO HIt shakes if nothing was found (or the phone's reason, e.g. ALLOW).
//
// VOLUME (hold L)  A up, L down, hold A mute, hold L back; closes after 3 s.
//   The digits are one bar filling half ([) then full (8). MAX at the top,
//   ------ / MUtEd when muted, SAFE? = the phone's hearing limit (confirm there).
//   The phone's own volume buttons pop the bar up for 2 s.
//
// INBOX (menu)  A next, L replay, hold L clear all, hold A back.
//   New notification: radar ping + beep, app tag, sender, a wipe, the message.
//   Another face on screen: a beep, saved as unread, played when you come back.
//
// CALLS  CALL shakes across the digits, then the caller scrolls. A silences it.
// LINK   [] LInK uP when the phone connects, brackets fly apart (LOSt) when it drops.
//
// Phone -> watch lines (\n ends each): M<play 0/1>|<pos s>|<len s>|<title>,
//   N<app>|<sender>|<text>, R<caller>, r, V<level>|<max>|<muted>|<limit hit>,
//   Y<count>|<suggestion>|..., Z1 (playing) or Z0|<why>
// Watch -> phone: P N B H + - m v X (face off screen) single letters, ?<typed>\n,
//   !<to play>\n, %<battery volts>\n

const subscribe = E_BUTTONS + E_BLE_RX + E_BLE_CONNECT + E_BLE_DISCONNECT;

var SOUND = 1;   // 0 = silent
var HOLD = 700;  // ms a button is held to count as a hold
var IDLE = 10000; // ms without presses or news before the music screen goes to battery saving

var TICK = 50, SCR = 4, MAXQ = 6, DBL = 7;
var EQ = ' _o8', SP = '      ';
var WH = '_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789&', STOPS = [1, 6, 11, 16, 21, 27];   // _ = space
var iv = -1, tk = 0, conn = false, rb = '', lastM = '', lastD = '', lastF = -1;
// mode: 0 music, 1 inbox, 2 notification popup, 3 call, 4 volume,
//       5 menu, 6 typing, 7 results, 8 waiting for the phone
var mode = 0, mt0 = 0;
var fx = 0, ft = 0;      // effect: 1 next, 2 prev, 3 link up, 4 link lost, 5 clear, 6 play, 7 shake
var tst = '', ttk = 0;   // toast
var lq = 0, bd = [0, 0], bh = [0, 0];
var la = 0, sc = 0, sl = 0, sp = 0;   // last activity (ms), slow-scroll step, slow mode on, switch pending
var pl = 0, pos = 0, dur = 0, trk = '', mt = 0, lv = [0, 0, 0, 0, 0, 0], ph = 0, pt = 0;
var q = [], un = 0, ni = 0, st = 0, sq = 0, who = '', ct = 0, cp = 0, cpt = 0;
var vl = 0, vmx = 15, vm = 0, vk = 0, dl = 0, vfx = 99, vdir = 0, vt = 0, vlim = 60, vsafe = 0;
var mi = 0, qt = '', qp = -1, qq = 0, sg = [], rl = [], si = 0, zm = '';

// ---------- helpers ----------

function pad(s, n) {
  s = '' + s;
  while (s.length < n) s = ' ' + s;
  return s;
}

function fit6(s) {
  while (s.length < 6) s += ' ';
  return s.substring(0, 6);
}

function ctr(s) {
  s = s.substring(0, 6);
  return pad(s, 6 - ((6 - s.length) >> 1));
}

function rnd(k) {
  return Ollee.hwRand() % k;
}

function now() {
  return new Date().getTime();
}

function beep() {
  if (SOUND) Ollee.beep();
}

// flags: 1 colon, 2 LAP, 4 HR, 8 PM. Only redraws what changed.
function show(m, d, f) {
  if (!Ollee.active()) return;
  m = fit6(m);
  if (m !== lastM) { Ollee.drawMain(m); lastM = m; }
  if (d !== lastD) { Ollee.drawDay(d); lastD = d; }
  if (f !== lastF) {
    Ollee.drawColon((f & 1) > 0);
    Ollee.drawLap((f & 2) > 0);
    Ollee.drawHR((f & 4) > 0);
    Ollee.drawPM((f & 8) > 0);
    lastF = f;
  }
}

// 6-letter window of scrolling text, null once it has scrolled through
function mq(s, t) {
  var i = Math.floor(t / SCR);
  if (i > s.length + 6) return null;
  return (SP + s + SP).substring(i, i + 6);
}

function toast(s) {
  tst = s;
  ttk = 24;
}

function go(m) {
  mode = m;
  mt0 = tk;
}

function home() {
  go(0);
  ph = 0;
  pt = tk;
}

function setFx(n) {
  fx = n;
  ft = 0;
}

function noLink() {
  if (conn) return false;
  toast('PHOnE?');
  beep();
  return true;
}

// ---------- music ----------

function bars() {
  var s = '', i;
  for (i = 0; i < 6; i++) s += EQ.charAt(lv[i]);
  return s;
}

function cur() {
  return pl ? pos + (now() - mt) / 1000 : pos;
}

function drawMusic() {
  var s, d = '  ', f = un ? 2 : 0, i, p;
  if (!conn) {
    p = (tk >> 2) % 10;
    if (p > 5) p = 10 - p;
    s = (tk >> 5) & 1 ? 'SEArCH' : SP.substring(0, p) + 'o' + SP.substring(p + 1);
    show(s, '--', f);
    return;
  }
  if (dur > 0) d = pad(Math.min(99, Math.floor(cur() * 100 / dur)), 2);
  if (pl) {
    f |= 8 | (tk % 10 < 3 ? 1 : 0);              // PM on, colon flashes on the beat
    if ((tk & 1) === 0) {
      for (i = 0; i < 6; i++) {
        p = i === 0 || i === 5 ? rnd(3) : 1 + rnd(3);
        lv[i] += p > lv[i] ? 1 : p < lv[i] ? -1 : 0;
        if (tk % 10 === 0 && i > 0 && i < 5) lv[i] = 3;   // the beat kicks the middle bars up
      }
    }
    if (ph === 0) {
      s = bars();
      if (tk - pt > 80) { ph = 1; pt = tk; }
    } else {
      s = mq(trk || 'PLAYInG', tk - pt);
      if (s === null) { ph = 0; pt = tk; s = bars(); }
    }
  } else {
    if (tk % 3 === 0) for (i = 0; i < 6; i++) lv[i] = lv[i] > 1 ? lv[i] - 1 : 1;   // bars sink
    if (!trk) s = (tk >> 5) & 1 ? ' IdLE ' : bars();
    else if (ph === 0) {
      s = tk - pt < 24 ? 'PAUSEd' : bars();      // PAUSEd, the flat line, then the song name
      if (tk - pt > 44) { ph = 1; pt = tk; }
    } else {
      s = mq(trk, tk - pt);
      if (s === null) { ph = 0; pt = tk; s = 'PAUSEd'; }
    }
  }
  show(s, d, f);
}

// ---------- effects ----------

function drawFx() {
  var s = '', i, x, e, t = ft++;
  if (fx < 3) {
    // skip: a streak (head = tail -) shoots across, then the word
    if (t < 10) {
      x = fx === 1 ? t - 2 : 7 - t;
      for (i = 0; i < 6; i++) {
        e = fx === 1 ? x - i : i - x;
        s += e === 0 ? '=' : e > 0 && e < 3 ? '-' : ' ';
      }
    } else {
      s = fx === 1 ? ' nEXt ' : ' PrEV ';
    }
    if (t >= 22) { fx = 0; ph = 1; pt = tk; }
  } else if (fx < 5) {
    // link: brackets snap together (up) or fly apart (lost)
    i = fx === 3 ? (t / 3) | 0 : 2 - ((t / 3) | 0);
    if (t < 9) s = ['[    ]', ' [  ] ', '  []  '][i];
    else if (fx === 3) s = t < 20 ? ' LInK ' : '  uP  ';
    else s = (t >> 2) & 1 ? SP : ' LOSt ';
    if (t === (fx === 3 ? 6 : 0)) beep();
    if (t >= 32) fx = 0;
  } else if (fx === 5) {
    // clear: the inbox dissolves block by block
    for (i = 0; i < 6; i++) s += rnd(14) < t ? ' ' : '8';
    if (t >= 14) s = ' CLEAr';
    if (t >= 30) fx = 0;
  } else if (fx === 6) {
    // play: the title collapses into a dot that drops, then the bars burst on
    s = ['------', ' ---- ', '  --  ', '  o   ', '  _   ', SP][t >> 1];
    if (t === 6) beep();
    if (t >= 11) {
      fx = 0;
      home();
      pl = 1;
      lv = [3, 3, 3, 3, 3, 3];
    }
  } else {
    // nothing found: the message shakes left and right
    s = t & 2 ? ' ' + zm : zm;
    if (t >= 24) fx = 0;
  }
  show(s, '  ', 0);
}

// ---------- notifications ----------

function startMsg(ping) {
  st = ping ? 0 : 1;
  sq = 0;
}

function nextStage() {
  st++;
  sq = 0;
}

function drawMsg() {
  var m = q[ni], s = SP, t = sq++, d;
  if (!m) { home(); return; }
  d = pad(mode === 1 ? ni + 1 : q.length, 2);
  if (st === 0) {
    s = ['  --  ', ' -  - ', '-    -', SP][(t >> 1) & 3];   // radar ping twice
    if (t === 0) beep();
    if (t >= 15) nextStage();
  } else if (st === 1) {
    s = t < 16 && (t >> 2) & 1 ? SP : ctr(m.a);       // the app tag blinks in, then holds
    if (t >= 24) nextStage();
  } else if (st === 2 || st === 4) {
    s = (st === 2 ? m.w : m.t) ? mq(st === 2 ? m.w : m.t, t) : null;   // sender, message
    if (s === null) { s = SP; nextStage(); }
  } else if (st === 3) {
    // a line draws across, then wipes away
    s = t < 6 ? '------'.substring(0, t + 1) : SP.substring(0, t - 5) + '------'.substring(t - 5);
    if (t >= 11) nextStage();
  } else if (t >= 10) {
    if (mode === 2) home();      // a popup goes back to music, the inbox replays
    else startMsg(0);
  }
  show(s, d, 2 | ((t >> 3) & 1 ? 4 : 0));
}

// ---------- calls ----------

function drawCall() {
  var s, t = ct++, k = cpt++;
  if (t > 1200) { home(); return; }                // give up after 60 s
  if (t < 400 && t % 30 === 0) beep();             // a beep every 1.5 s for 20 s
  if (cp === 0) {
    s = ['CALL  ', ' CALL ', '  CALL', ' CALL '][(k >> 1) & 3];   // the word rings
    if (k >= 32) { cp = 1; cpt = 0; }
  } else {
    s = mq(who || 'UnKnWn', k);
    if (s === null) { cp = 0; cpt = 0; s = SP; }
  }
  show(s, '  ', 4 | (t & 4 ? 1 : 0));
}

// ---------- volume ----------

function volOpen(fromPhone) {
  go(4);
  vt = 0;
  vlim = fromPhone ? 40 : 60;
  if (!fromPhone) {
    dl = 0;                              // sweep up from empty
    vfx = 99;
    Ollee.send('v');
  }
}

function volTarget() {
  return vm ? 0 : Math.round(vl * 12 / (vmx || 15));   // bar length in half-digits
}

function drawVol() {
  var td = volTarget(), b = (vfx >> 1) & 1, n, s = '', i, k;
  if (dl < td) dl++;                     // the bar glides one half-digit per frame
  else if (dl > td) dl--;
  n = vfx < 8 ? Math.max(0, Math.min(12, vdir > 0 ? dl - b : dl + b)) : dl;   // edge flicker
  for (i = 0; i < 6; i++) {
    k = n - 2 * i;
    s += k >= 2 ? '8' : k === 1 ? '[' : '_';
  }
  if (vm) s = (vt >> 3) & 1 ? ' MUtEd' : '------';
  else if (vsafe > 0) { vsafe--; if ((vsafe >> 2) & 1) s = 'SAFE? '; }
  else if (vl >= vmx && vdir > 0 && vfx < 24 && (vfx >> 2) & 1) s = ' MAX  ';
  vfx++;
  if (++vt > vlim) home();
  show(s, vk ? pad(vl, 2) : '--', 0);
}

function volStep(d) {
  if (noLink()) return;
  Ollee.send(d > 0 ? '+' : '-');
  vl = Math.max(0, Math.min(vmx, vl + d));   // show it now, the phone confirms
  vm = 0;
  vdir = d;
  vfx = vt = 0;
  vlim = 60;
  if (SOUND) Ollee.beepShort();
}

// ---------- menu and search ----------

function drawMenu() {
  var it = ['SEArCH', 'InbOX', volts() + ' V'][mi];
  show((tk >> 3) & 1 || tk - mt0 > 12 ? ctr(it) : SP, mi === 1 && q.length ? pad(q.length, 2) : '  ', 0);
  if (tk - mt0 > 120) home();
}

function tail() {
  return qt.substring(Math.max(0, qt.length - 5)).split(' ').join('_');
}

// lock the letter on the wheel into the query, and send the query for suggestions
function commit() {
  if (qp < 0) return;
  if (qt.length < 30) qt += qp ? WH.charAt(qp) : ' ';
  qp = -1;
  send();
}

function send() {
  sg = [];
  Ollee.send('?' + qt + '\n');
}

function drawType() {
  var s, d = qt.length > 1 && sg.length ? pad(sg.length, 2) : '  ';
  if (qp >= 0 && ++qq >= 20) commit();
  if (qp >= 0) s = pad(tail() + WH.charAt(qp), 6);                    // the letter on the wheel
  else if (!qt) s = (tk >> 4) & 1 ? ' tYPE ' : '     _';
  else s = pad(tail() + ((tk >> 3) & 1 ? '_' : ' '), 6);             // blinking cursor
  show(s, d, qp >= 0 ? 2 : 0);
}

function results() {
  commit();
  if (!qt) return;
  rl = sg.length ? sg : [qt];
  si = 0;
  pt = tk;
  go(7);
}

function drawRes() {
  var s;
  if (!rl.length) { home(); return; }
  s = mq(rl[si], tk - pt);
  if (s === null) { pt = tk; s = SP; }
  show(s, pad(si + 1, 2), 0);
}

// ask the phone to find and play this
function play() {
  Ollee.send('!' + rl[si] + '\n');
  go(8);
}

function drawWait() {
  // a segment orbits the digits: along the bottom, back along the middle
  var p = (tk - mt0) % 12,
    s = p < 6 ? SP.substring(0, p) + '_' + SP.substring(p + 1) : SP.substring(0, 11 - p) + '-' + SP.substring(12 - p);
  show(s, '  ', 4);
  if (tk - mt0 > 400) fail('nO HIt');   // no answer in 20 s
}

function fail(why) {
  zm = why;
  go(7);
  pt = tk;
  setFx(7);
  beep();
}

// ---------- battery ----------

function volts() {
  var x = Math.round(Ollee.battery() * 100);
  return ((x / 100) | 0) + '.' + (x % 100 < 10 ? '0' : '') + (x % 100);
}

// tell the phone the battery voltage (it keeps a history)
function report() {
  if (Ollee.connected()) Ollee.send('%' + volts() + '\n');
}

// something happened: run the full-speed animation again
function wake() {
  la = now();
  if (Ollee.active() && (iv < 0 || sl)) {
    if (iv >= 0) Ollee.clearInterval(iv);
    sl = 0;
    iv = Ollee.setInterval(tick, TICK);
  }
}

// switch to one frame a second (run from its own timeout, not inside tick's timer)
function goSlow() {
  sp = 0;
  if (sl || iv < 0 || now() - la <= IDLE) return;
  Ollee.clearInterval(iv);
  sl = 1;
  iv = Ollee.setInterval(tick, 1000);
}

// battery saving: one cheap frame a second
function slow() {
  var t = trk + '   ', i = sc++ % t.length, s, d = '  ';
  if (!conn) s = 'SEArCH';
  else if (!trk) s = ' IdLE ';
  else if (!pl) s = 'PAUSEd';
  else s = (t + t).substring(i, i + 6);  // the title creeps by, one letter a second
  if (conn && dur > 0) d = pad(Math.min(99, Math.floor(cur() * 100 / dur)), 2);
  show(s, d, (un ? 2 : 0) | (pl ? 8 | (sc & 1) : 0));
}

// ---------- main loop ----------

function tick() {
  var b, q0 = now() - la;
  if (sl) { slow(); return; }            // battery saving: just the slow frame
  tk++;
  if (mode === 0 && !fx && !ttk && !lq && !bd[0] && !bd[1] && q0 > IDLE) {
    if (!sp && Ollee.setTimeout(goSlow, 10) >= 0) sp = 1;   // nothing going on: slow down
    slow();
    return;
  }
  if ((mode === 1 || mode > 4) && mode !== 8 && q0 > 30000) home();   // left in a menu
  for (b = 0; b < 2; b++) {              // a button held long enough: hold action, right away
    if (bd[b] && !bh[b] && now() - bd[b] >= HOLD) {
      bh[b] = 1;
      press(b, 1);
    }
  }
  if (lq && tk - lq >= DBL) {            // single L: next track
    lq = 0;
    if (mode === 0 && !noLink()) { Ollee.send('N'); setFx(1); }
  }
  if (ttk > 0) {
    ttk--;
    show(ctr(tst), '  ', 0);
  } else if (fx) drawFx();
  else if (mode === 0) drawMusic();
  else if (mode < 3) drawMsg();
  else if (mode === 3) drawCall();
  else if (mode === 4) drawVol();
  else if (mode === 5) drawMenu();
  else if (mode === 6) drawType();
  else if (mode === 7) drawRes();
  else drawWait();
}

// ---------- phone messages ----------

function line(l) {
  var c, a, t, k = Math.max(l.lastIndexOf('M0|'), l.lastIndexOf('M1|'));
  if (k > 0) l = l.substring(k);         // a music line glued to leftovers from a dropped link
  c = l.charAt(0);
  a = l.substring(1).split('|');
  if (c === 'M') {
    pl = a[0] === '1' ? 1 : 0;
    pos = +a[1] || 0;
    dur = +a[2] || 0;
    mt = now();
    t = a[3] || '';
    if (t !== trk) { trk = t; ph = 1; pt = tk; wake(); }   // new song: title scrolls in
  } else if (c === 'N') {
    q.unshift({ a: a[0] || '', w: a[1] || '', t: a[2] || '' });
    if (q.length > MAXQ) q.pop();
    if (Ollee.active() && mode < 3) {
      wake();
      go(2);
      ni = 0;
      startMsg(1);
    } else {
      un++;
      beep();
    }
  } else if (c === 'R') {
    who = a[0] || '';
    wake();
    go(3);
    ct = cp = cpt = 0;
    if (!Ollee.active()) beep();
  } else if (c === 'r' && mode === 3) {
    home();
  } else if (c === 'V') {
    t = +a[0] || 0;
    if (vk && t !== vl) {
      vdir = t > vl ? 1 : -1;
      if (mode === 0 && Ollee.active() && !fx) {   // phone buttons: pop the bar up
        wake();
        dl = volTarget();
        volOpen(1);
      }
      vfx = 0;
    }
    vl = t;
    vmx = +a[1] || 15;
    vm = a[2] === '1' ? 1 : 0;
    vk = 1;
    if (a[3] === '1') vsafe = 40;
    if (mode === 4) vt = 0;
  } else if (c === 'Y') {
    sg = [];
    for (k = 1; k <= (+a[0] || 0) && k < a.length; k++) sg.push(a[k]);
  } else if (c === 'Z' && mode === 8) {
    if (a[0] === '1') setFx(6);
    else fail(a[1] || 'nO HIt');
  }
}

function ble_rx(d) {
  var i;
  conn = true;
  rb += d;
  while ((i = rb.indexOf('\n')) >= 0) {
    line(rb.substring(0, i));
    rb = rb.substring(i + 1);
  }
  if (rb.length > 400) rb = '';
}

// ask the phone for the state (not right at connect: it needs a moment)
function hello() {
  if (Ollee.connected()) Ollee.send('H');
}

// ---------- buttons ----------

// b: 0 = A, 1 = L.  h: 1 = hold
function press(b, h) {
  var m = mode, i;
  if (m === 4) {
    if (!h) volStep(b ? -1 : 1);
    else if (b) home();
    else if (!noLink()) { Ollee.send('m'); vm = 1 - vm; vt = 0; vfx = 99; }
  } else if (m === 5) {
    if (h) home();
    else if (b) mi = (mi + 1) % 3;
    else if (mi > 1) { report(); home(); }
    else if (mi) {
      if (q.length) { go(1); ni = un = 0; startMsg(0); }
      else { toast('nO MSG'); home(); }
    } else if (!noLink()) { qt = ''; qp = -1; sg = []; go(6); }
  } else if (m === 6) {
    qq = 0;
    if (!h && !b) qp = qp < 0 ? 1 : (qp + 1) % WH.length;   // A: next letter
    else if (!h) {                                     // L: jump A F K P U 0 space
      for (i = 0; i < STOPS.length && STOPS[i] <= qp; i++);
      qp = i < STOPS.length ? STOPS[i] : 0;
    } else if (!b) results();                          // hold A: suggestions
    else if (qp >= 0) qp = -1;                         // hold L: drop the wheel letter,
    else if (qt) { qt = qt.substring(0, qt.length - 1); send(); }   // delete,
    else home();                                       // or leave
  } else if (m === 7) {
    if (h) { if (b) go(6); else play(); }
    else if (b) { si = (si + 1) % rl.length; pt = tk; }
    else play();
  } else if (m === 8) {
    if (h && b) go(7);
  } else if (m === 3 || m === 2) {
    if (h || !b) home();
    else if (m === 2) startMsg(0);
  } else if (m === 1) {
    if (!h) {
      if (b) startMsg(0);
      else if (++ni >= q.length) home();
      else startMsg(0);
    } else if (b) { q = []; un = 0; home(); setFx(5); }
    else home();
  } else if (h) {                                      // music
    if (b) { if (!noLink()) { lq = 0; volOpen(0); } }
    else { mi = 0; go(5); }
  } else if (b) {
    if (lq) {                                          // second press in time: previous
      lq = 0;
      if (!noLink()) { Ollee.send('B'); setFx(2); }
    } else lq = tk || 1;                               // wait to see if a second press comes
  } else if (!noLink()) {
    Ollee.send('P');
    pos = cur();
    pl = 1 - pl;
    mt = now();
    if (pl) lv = [3, 3, 3, 3, 3, 3];                   // bars burst up
    ph = 0;
    pt = tk;
  }
  tick();
}

// raw buttons: a press counts when released, unless it already became a hold
function button(b, e) {
  if (b !== B_A && b !== B_L) return;
  wake();
  b = b === B_L ? 1 : 0;
  if (e === E_DOWN) {
    bd[b] = now();
    bh[b] = 0;
  } else if (bd[b]) {
    bd[b] = 0;
    if (!bh[b]) press(b, 0);
  }
}

// ---------- lifecycle ----------

function enter() {
  Ollee.clear();
  lastM = lastD = '';
  lastF = -1;
  fx = ttk = 0;
  conn = Ollee.connected();
  rb = '';
  hello();
  report();
  la = now();
  sl = sp = 0;
  if (mode === 2 || mode === 4 || mode === 5 || mode > 7) mode = 0;
  if (mode === 0 && un > 0 && q.length) { go(1); ni = un = 0; startMsg(0); }
  if (iv < 0) iv = Ollee.setInterval(tick, TICK);
  tick();
}

function exit() {
  iv = -1;
  sl = sp = 0;
  if (Ollee.connected()) Ollee.send('X');   // tells the phone the face is off screen
  fx = 0;
  bd = [0, 0];
  lq = 0;
  if (mode === 2 || mode === 4 || mode === 5) mode = 0;
}

function init() {
  Ollee.subscribe(E_BLE_RX + E_CLOCK_HOUR);
}

// once an hour, even off screen: battery report for the phone's history
function clock_hour() {
  report();
}

function ble_connect() {
  conn = true;
  rb = '';                 // drop any half message from before the drop
  setFx(3);
  Ollee.setTimeout(hello, 1500);
}

function ble_disconnect() {
  conn = false;
  rb = '';
  pl = 0;
  setFx(4);
  if (mode === 8) go(7);
}

