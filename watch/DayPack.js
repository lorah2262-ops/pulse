// Ollee Watch Forge - DAY PACK
// Written by the Pulse app with your next calendar events baked in, installed into
// Forge A a little before they start and swapped back for your own face after.
// It needs no Bluetooth: the clock, the countdown and the reminders run on the watch.
//
// CLOCK   the time with seconds; the day digits show the date, or the minutes to the
//         next event (LAP lit) once it is under 100 minutes away, or the minutes until
//         you should leave for it (HR lit) when it has a place and a leave time.
//         A: the next event   L: how many are left today   hold A or L: nothing
// EVENT   "In 25" (or "In 2H"), then its time, then GO and the time to leave (if it has a
//         place), then its name scrolls by; day digits = its number.
//         L: the one after   A: back to the clock   30 s untouched: back to the clock
// REMIND  the watch beeps at the leave time ("GO nOW"), REMIND minutes before each event
//         and again as it starts,
//         even when another face is on screen; on this face the name flashes up.
//         Any button: shows that event.
//
// Pulse fills in EV and CFG below when it writes the face.

const subscribe = E_BUTTONS + E_CLOCK_HOUR;

var EV = [["__EV__"]];    // [year, month 0-11, day, hour, minute, "name", leave hour, leave minute], soonest first
var CFG = ["__RM__"];     // minutes before each event the watch beeps
var REMIND = CFG[0];

var SP = '      ';
var iv = -1, rate = 0, mode = 0, idx = 0, step = 0, stepAt = 0, la = 0;
var alertI = -1, alertAt = 0, alertGo = 0, gen = 0, note = '', noteTo = 0;
var lastM = '', lastD = '', lastF = -1, downAt = [0, 0];

function now() { return new Date().getTime(); }
function at(e) { return new Date(e[0], e[1], e[2], e[3], e[4]).getTime(); }
function go(e) { return e.length > 7 && e[6] >= 0 ? new Date(e[0], e[1], e[2], e[6], e[7]).getTime() : 0; }
function p2(n) { return n < 10 ? '0' + n : '' + n; }
function s2(n) { return n < 10 ? ' ' + n : '' + n; }

// flags: 1 colon, 2 LAP, 4 HR, 8 PM. Only redraws what changed.
function show(m, d, f) {
  if (!Ollee.active()) return;
  m = (m + SP).substring(0, 6);
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

// the first event that hasn't started more than 5 minutes ago; -1 when the day is done
function upcoming() {
  var n = now();
  for (var i = 0; i < EV.length; i++) if (at(EV[i]) > n - 300000) return i;
  return -1;
}

function left() {
  var u = upcoming();
  return u < 0 ? 0 : EV.length - u;
}

function until(i) {
  var m = Math.round((at(EV[i]) - now()) / 60000);
  if (m <= 0) return 'nOW';
  if (m < 100) return 'In ' + m;
  return 'In ' + Math.round(m / 60) + 'H';
}

// 6-letter window of scrolling text, null once it has gone by
function scroll(s, t) {
  var i = Math.floor(t / 250);
  if (i > s.length + 6) return null;
  return (SP + s + SP).substring(i, i + 6);
}

// ---------- the screens ----------

function drawClock() {
  var d = new Date(), h = d.getHours(), f = 1 | (h > 11 ? 8 : 0);
  var day = s2(d.getDate()), u = upcoming();
  if (u >= 0) {
    var g = go(EV[u]), m = Math.round((at(EV[u]) - now()) / 60000), gm = Math.round((g - now()) / 60000);
    if (g && gm >= 0 && gm < 100) { day = s2(gm); f |= 4; }        // minutes until you should leave
    else if (m >= 0 && m < 100) { day = s2(m); f |= 2; }
  }
  if (note && now() < noteTo) { show(note, day, f & 6); return; }
  note = '';
  show(s2(h % 12 || 12) + p2(d.getMinutes()) + p2(d.getSeconds()), day, f);
}

function drawEvent() {
  var e = EV[idx], n = now(), t = n - stepAt, num = s2(idx + 1);
  if (step === 0) {
    show(until(idx), num, 0);
    if (t > 1500) { step = 1; stepAt = n; }
  } else if (step === 1) {
    var h = e[3];
    show(s2(h % 12 || 12) + p2(e[4]) + '  ', num, 1 | (h > 11 ? 8 : 0));
    if (t > 2000) { step = go(e) ? 3 : 2; stepAt = n; }
  } else if (step === 3) {
    var gh = e[6];
    show(s2(gh % 12 || 12) + p2(e[7]) + '  ', 'GO', 1 | (gh > 11 ? 8 : 0));   // leave at
    if (t > 2000) { step = 2; stepAt = n; }
  } else {
    var w = scroll(e[5], t);
    if (w === null) { step = 0; stepAt = n; w = SP; }
    show(w, num, 0);
  }
  if (n - la > 30000) home();
}

function drawAlert() {
  var e = EV[alertI], t = now() - alertAt, r = e[5] + '   ', i = Math.floor(t / 250) % r.length;
  var w = e[5].length <= 6 ? e[5] : (r + r).substring(i, i + 6);   // the name goes round with no blank gap
  if (Math.floor(t / 600) % 4 === 0) w = alertGo ? 'GO nOW' : until(alertI);
  show(w, '  ', Math.floor(t / 300) % 2 ? 2 : 0);
  if (t > 30000) home();
}

function tick() {
  if (mode === 1) drawEvent();
  else if (mode === 2) drawAlert();
  else drawClock();
}

// fast while something moves, once a second on the plain clock
function speed(ms) {
  if (!Ollee.active()) return;
  if (iv >= 0 && rate === ms) return;
  if (iv >= 0) Ollee.clearInterval(iv);
  rate = ms;
  iv = Ollee.setInterval(tick, ms);
}

function home() {
  mode = 0;
  speed(1000);
  tick();
}

function openEvent(i) {
  if (i < 0 || i >= EV.length) { note = 'nO EVt'; noteTo = now() + 2000; home(); return; }
  mode = 1; idx = i; step = 0; stepAt = la = now();
  speed(100);
  tick();
}

// ---------- reminders: beeps that work with any face on screen ----------

function beeps(n) {
  Ollee.beep();
  for (var k = 1; k < n; k++) Ollee.setTimeout(function () { Ollee.beep(); }, k * 350);
}

function fire(i, isGo) {
  alertI = i;
  alertAt = now();
  alertGo = isGo ? 1 : 0;
  beeps(isGo ? 4 : at(EV[i]) - now() > 60000 ? 2 : 3);
  if (Ollee.active()) { mode = 2; speed(100); tick(); }
}

// sets one timer for the next reminder if it falls within the hour; the hourly event re-arms it
function arm() {
  var my = ++gen, n = now(), best = -1, bi = -1, bg = 0;
  for (var i = 0; i < EV.length; i++) {
    var s = at(EV[i]), r = s - REMIND * 60000, g = go(EV[i]);
    if (r > n + 500 && (best < 0 || r < best)) { best = r; bi = i; bg = 0; }
    if (s > n + 500 && (best < 0 || s < best)) { best = s; bi = i; bg = 0; }
    if (g > n + 500 && (best < 0 || g < best)) { best = g; bi = i; bg = 1; }
  }
  if (bi < 0 || best - n > 3700000) return;
  Ollee.setTimeout(function () { if (my === gen) { fire(bi, bg); arm(); } }, best - n);
}

// ---------- buttons: a tap counts on release; held over 0.7 s is a hold ----------

function button(b, e) {
  if (b !== B_A && b !== B_L) return;
  var k = b === B_L ? 1 : 0;
  if (e === E_DOWN) { downAt[k] = now(); return; }
  if (!downAt[k]) return;
  var hold = now() - downAt[k] > 700;
  downAt[k] = 0;
  la = now();
  if (mode === 2) { var a = alertI; alertI = -1; openEvent(a); return; }
  if (mode === 1) {
    if (hold || k === 0) home();
    else if (idx + 1 < EV.length) openEvent(idx + 1);
    else home();
    return;
  }
  if (hold) return;
  if (k === 0) openEvent(upcoming());
  else { var l = left(); note = l ? l + ' LEFt' : 'dOnE'; noteTo = now() + 2000; tick(); }
}

// ---------- lifecycle ----------

function init() {
  Ollee.subscribe(E_CLOCK_HOUR);
  arm();
}

function clock_hour() {
  arm();
}

function enter() {
  Ollee.clear();
  lastM = lastD = '';
  lastF = -1;
  iv = -1;
  rate = 0;
  downAt = [0, 0];
  if (alertI >= 0 && now() - alertAt < 30000) { mode = 2; speed(100); }
  else { alertI = -1; mode = 0; speed(1000); }
  arm();
  tick();
}

function exit() {
  if (iv >= 0) Ollee.clearInterval(iv);
  iv = -1;
  rate = 0;
  if (mode === 1) mode = 0;
}
