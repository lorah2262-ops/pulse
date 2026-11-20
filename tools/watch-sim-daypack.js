// Simulated Ollee watch: fake clock, timers, screen log. node sim.js face.js
const vm = require('vm'), fs = require('fs');
let src = fs.readFileSync(process.argv[2], "utf8");
// two events today: 9:30 and 10:15 (local), remind 10 min; clock starts 9:05
const base = new Date(2026, 10, 20, 9, 5, 0).getTime();
src = src.replace('[["__EV__"]]', '[[2026,10,20,9,30,"MAtH CLASS"],[2026,10,20,10,15,"LAb"]]').replace('["__RM__"]', '[10]');
let T = base, timers = [], tid = 0, active = true, screen = { m: '', d: '', colon: 0, lap: 0, pm: 0 }, beeps = [];
const RealDate = Date;
function FakeDate(...a) { return a.length ? new RealDate(...a) : new RealDate(T); }
FakeDate.prototype = RealDate.prototype; FakeDate.now = () => T;
const Ollee = {
  active: () => active, clear() {}, drawMain(s) { if (s.length !== 6) throw 'main len ' + s; screen.m = s; }, drawDay(s) { if (s.length !== 2) throw 'day len ' + JSON.stringify(s); screen.d = s; },
  drawColon(b) { screen.colon = b; }, drawLap(b) { screen.lap = b; }, drawHR() {}, drawPM(b) { screen.pm = b; },
  beep() { beeps.push(new RealDate(T).toTimeString().slice(0, 8)); }, beepShort() {}, subscribe() {},
  setInterval(f, ms) { timers.push({ id: ++tid, f, ms, at: T + ms, rep: 1 }); return tid; },
  setTimeout(f, ms) { timers.push({ id: ++tid, f, ms, at: T + ms, rep: 0 }); return tid; },
  clearInterval(id) { timers = timers.filter(t => t.id !== id); },
};
const ctx = { Ollee, Date: FakeDate, Math, E_BUTTONS: 1, E_CLOCK_HOUR: 2, E_DOWN: 1, E_UP: 2, B_A: 10, B_L: 11, console };
vm.createContext(ctx); vm.runInContext(src, ctx);
function run(ms) {
  const end = T + ms;
  while (true) {
    timers.sort((a, b) => a.at - b.at);
    const t = timers[0];
    if (!t || t.at > end) break;
    T = t.at;
    if (t.rep) t.at += t.ms; else timers.shift();
    t.f();
    // the hourly event
  }
  T = end;
}
function hourTick(ms) { const end = T + ms; while (T < end) { const nextHour = Math.ceil((T + 1) / 3600000) * 3600000; if (nextHour > end) { run(end - T); break; } run(nextHour - T); ctx.clock_hour(); } }
function press(b, hold) { ctx.button(b, 1); run(hold ? 900 : 120); ctx.button(b, 2); run(30); }
const snap = (label) => console.log(label.padEnd(26), JSON.stringify(screen.m), JSON.stringify(screen.d), screen.colon ? ':' : ' ', screen.lap ? 'LAP' : '   ', screen.pm ? 'PM' : '  ');
ctx.init(); ctx.enter(); run(10);
snap('9:05 clock');
press(10); run(300); snap('A -> event 1 (countdown)');
run(1700); snap('event 1 time');
run(2500); snap('event 1 name scrolling');
press(11); run(200); snap('L -> event 2');
press(10); snap('A -> back to clock');
press(11); snap('L -> how many left');
run(2500); snap('note gone');
// leave the face (another face on screen), run to the reminder
ctx.exit(); active = false; hourTick(15 * 60000 - 5000);
console.log('beeps by 9:20 (face off):', beeps);
active = true; ctx.enter(); run(500); snap('back on face after 9:20 beep');
press(10); run(300); snap('button on alert -> event');
hourTick(55 * 60000); console.log('all beeps:', beeps);
run(2000); snap('10:22 clock (LAP?)');
hourTick(60 * 60000); press(10); run(300); snap('after all events, A');
// random fuzz: no exceptions
for (let i = 0; i < 3000; i++) { const r = Math.random(); if (r < .3) press(Math.random() < .5 ? 10 : 11, Math.random() < .2); else if (r < .35) { active = !active; active ? ctx.enter() : ctx.exit(); } else run(Math.random() * 20000); }
console.log('fuzz ok, timers alive:', timers.length);
