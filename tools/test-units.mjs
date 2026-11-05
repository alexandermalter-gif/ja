// Unit-Tests ohne Browser: Spielstand (mit localStorage-Ersatz), Sitzung, Wertung, Statistik,
// Katzen, Wochenansicht, Tutorial-Schrittfolge und Datumsrechnung (auch in anderen Zeitzonen,
// z. B. TZ=America/Santiago mit Zeitumstellung um Mitternacht).
import { LEVELS } from '../src/logic/levels.js';
import { makeGeometry } from '../src/logic/solver.js';
import { MARK_EMPTY, MARK_X, MARK_CAT, MARK_AUTO_X } from '../src/game/marks.js';

// --- localStorage-Ersatz -------------------------------------------------------------
const mem = new Map();
let failWrites = false;
globalThis.localStorage = {
  getItem: (k) => (mem.has(k) ? mem.get(k) : null),
  setItem: (k, v) => { if (failWrites) throw new Error('QuotaExceeded'); mem.set(k, String(v)); },
  removeItem: (k) => mem.delete(k),
};
if (typeof globalThis.btoa !== 'function') {
  globalThis.btoa = (s) => Buffer.from(s, 'binary').toString('base64');
  globalThis.atob = (s) => Buffer.from(s, 'base64').toString('binary');
}

const storage = await import('../src/game/storage.js');
const { Session, puzzleSig } = await import('../src/game/session.js');
const { starsFor, pointsFor, tierFor } = await import('../src/game/scoring.js');
const { formatTime, formatLong, averageBySize } = await import('../src/game/stats.js');
const { SKINS, isSkinUnlocked, nextMilestone, unlockProgress } = await import('../src/game/skins.js');
const { dateKey, addDays, weekView, streakInfo, bestStreak, parseKey } = await import('../src/game/daily.js');
const { TUTORIAL_LEVEL } = await import('../src/game/tutorial.js');
const { computeHint } = await import('../src/game/hints.js');
const { Scheduler } = await import('../src/game/timing.js');

let failed = 0;
let count = 0;
const check = (cond, msg) => { count++; if (!cond) { failed++; console.error(`✘ ${msg}`); } };
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);

// --- Spielstand ------------------------------------------------------------------------
const { KEY } = storage;
{
  const r = storage.loadData();
  check(!r.recovered && r.data.v === storage.SCHEMA_VERSION && r.data.hints === storage.START_HINTS, 'leerer Speicher → Standardwerte');

  mem.set(KEY, '{kaputt');
  const r2 = storage.loadData();
  check(r2.recovered && r2.data.unlocked === 1, 'kaputtes JSON → neu angelegt');
  check([...mem.keys()].some((k) => k.startsWith(`${KEY}-defekt-`)), 'kaputtes JSON wird gesichert');

  // v1 mit Spielstand in "current" und null-Feldern
  mem.set(KEY, JSON.stringify({ unlocked: 5, best: { 1: { stars: 3, points: 90, time: 30 }, 2: null }, current: { key: 'L4', marks: [0, 1, 2, 3, 9], fish: 2, mistakes: 1 }, daily: { solved: null }, skins: null, settings: { sound: 'ja', lives: false } }));
  const v1 = storage.loadData().data;
  check(v1.unlocked === 5 && v1.best[1].stars === 3 && !v1.best[2], 'v1: Bestwerte übernommen, null ignoriert');
  check(v1.saves.L4 && eq(v1.saves.L4.marks, [0, 1, 2, 3, 0]) && v1.lastLevel === 4, 'v1: current → saves.L4, ungültige Markierung bereinigt');
  check(v1.settings.sound === true && v1.settings.lives === false && v1.settings.autoX === true, 'v1: Einstellungen typgeprüft, neue mit Standard');
  check(eq(v1.daily.solved, {}) && v1.skins.selected === 'smoking', 'v1: kaputte Teile → Standard');

  // Speichern führt mit einem zweiten Tab zusammen
  mem.clear();
  const a = storage.defaults();
  a.best[1] = { stars: 2, points: 50, time: 80 };
  a.unlocked = 2;
  check(storage.saveData(a), 'saveData meldet Erfolg');
  const b = storage.defaults();
  b.best[1] = { stars: 3, points: 70, time: 95 };
  b.best[2] = { stars: 1, points: 20, time: 60 };
  b.daily.solved['2026-11-01'] = { time: 100, stars: 3, points: 200 };
  storage.saveData(b);
  const merged = storage.loadData().data;
  check(merged.best[1].stars === 3 && merged.best[1].time === 80 && merged.best[2] && merged.unlocked === 2, 'zweiter Tab: Sterne max, Zeit min, Level vereinigt');
  check(!!merged.daily.solved['2026-11-01'], 'zweiter Tab: Tagesrätsel bleiben');

  // gelöschter Spielstand bleibt gelöscht
  const c = storage.loadData().data;
  c.saves.L3 = { key: 'L3', marks: [0], notes: [0], updatedAt: 5 };
  storage.saveData(c);
  delete c.saves.L3;
  c.deletedSaves = ['L3'];
  storage.saveData(c);
  check(!storage.loadData().data.saves.L3, 'gelöschter Spielstand kommt nicht zurück');

  // Aufräumen der Spielstände
  const d = storage.defaults();
  for (let i = 1; i <= 8; i++) d.saves[`L${i}`] = { updatedAt: i };
  d.saves['D2026-11-01'] = { updatedAt: 1 };
  d.saves['D2026-11-05'] = { updatedAt: 1 };
  d.saves['A2026-10-01'] = { updatedAt: 1 };
  d.saves['A2026-10-02'] = { updatedAt: 2 };
  storage.pruneSaves(d, '2026-11-05');
  const keys = Object.keys(d.saves).sort();
  check(eq(keys, ['A2026-10-02', 'D2026-11-05', 'L4', 'L5', 'L6', 'L7', 'L8']), `pruneSaves behält 5 Level, heute, 1 Archiv (${keys})`);

  // Zurücksetzen behält Einstellungen
  const reset = storage.resetProgress({ ...storage.defaults().settings, sound: false });
  check(reset.settings.sound === false && reset.tutorialSeen && reset.unlocked === 1, 'Zurücksetzen behält Einstellungen');

  // Export/Import mit Umlauten
  const e = storage.defaults();
  e.best[7] = { stars: 3, points: 111, time: 42 };
  e.skins.selected = 'glueck';
  const code = storage.exportCode(e);
  const back = storage.importCode(code);
  check(code.startsWith('KS1:') && back.best[7].points === 111 && back.skins.selected === 'glueck', 'Export/Import');
  let threw = false;
  try { storage.importCode('Hallo'); } catch { threw = true; }
  check(threw, 'Import lehnt Unsinn ab');

  // Speichern scheitert → false
  failWrites = true;
  check(storage.saveData(e) === false && storage.storageWorks() === false, 'Speicherfehler wird gemeldet');
  failWrites = false;

  // Rätsel im Spielstand wird geprüft
  const p = storage.sanitize({ saves: { F: { marks: [0, 0], puzzle: { n: 5, regions: 'abc', colors: [], solution: [] } } } });
  check(p.saves.F.puzzle === null, 'ungültiges mitgespeichertes Rätsel verworfen');
}

// --- Sitzung -------------------------------------------------------------------------------
{
  const l = LEVELS[0];
  const level = { ...l, regionArr: [...l.regions].map((c) => parseInt(c, 36)) };
  const geo = makeGeometry(l.n, level.regionArr);
  const s = new Session(level, geo);
  const n = l.n;
  const sol = l.solution.map((c, r) => r * n + c);
  check(s.isEmpty() && !s.restored, 'Sitzung startet leer');
  s.snapshot();
  s.marks[sol[0]] = MARK_CAT;
  const d = s.autoXTargets([sol[0]], 100, 45);
  check(d.size === geo.attacks[sol[0]].length && Math.min(...d.values()) === 100, 'Auto-X-Ziele mit Wellenverzögerung');
  for (const c of d.keys()) s.marks[c] = MARK_AUTO_X;
  check(s.mistakes().length === 0, 'Auto-X nach richtiger Katze sind keine Fehler');
  check(s.blockingCats(geo.attacks[sol[0]][0])[0] === sol[0], 'blockierende Katze gefunden');
  s.marks[sol[0]] = MARK_EMPTY;
  check(s.staleAutoX().length === d.size, 'Auto-X ohne Katze werden erkannt');
  s.undo();
  check(s.isEmpty(), 'Rückgängig');
  // eigenes X auf Lösungsfeld = Fehler; Joker meidet blockierte Felder
  s.marks[sol[1]] = MARK_X;
  check(s.mistakes().includes(sol[1]), 'falsches eigenes X ist ein Fehler');
  for (const c of sol) s.marks[c] = MARK_CAT;
  check(s.isSolved() && s.conflictCells().size === 0, 'Lösung erkannt');
  const saved = { marks: Array.from(s.marks), notes: Array.from(s.notes), sig: puzzleSig(level) };
  check(new Session(level, geo, saved).restored, 'Spielstand passt zum Rätsel');
  check(!new Session(level, geo, { ...saved, sig: 'anders' }).restored, 'fremder Spielstand wird verworfen');
  const two = new Session(level, geo);
  two.marks[0] = MARK_CAT;
  two.marks[1] = MARK_CAT;
  check(two.conflictCells().size === 2, 'Konflikt benachbarter Katzen');
  const j = new Session(level, geo);
  const wrong = geo.attacks[sol[2]].find((c) => !sol.includes(c));
  j.marks[wrong] = MARK_CAT;
  const jc = j.jokerCell();
  check(sol.includes(jc) && !j.attackedByCat(jc), 'Joker wählt ein nicht blockiertes Lösungsfeld');
}

// --- Wertung --------------------------------------------------------------------------------
check(starsFor(0, 0) === 3 && starsFor(1, 1) === 2 && starsFor(2, 1) === 1 && starsFor(0, 0, 1) === 1, 'Sterne');
check(pointsFor('level', LEVELS[0], 3) > pointsFor('level', LEVELS[0], 1), 'mehr Sterne, mehr Punkte');
{
  const tiers = [0, 0, 0, 0, 0];
  for (const l of LEVELS) tiers[tierFor(l)]++;
  check(tiers.every((t) => t > 0), `alle fünf Stufen kommen vor (${tiers})`);
  for (const n of [5, 6, 7, 8, 9, 10]) {
    const ts = LEVELS.filter((l) => l.n === n).map(tierFor);
    check(ts[0] <= ts[ts.length - 1], `Stufen steigen innerhalb von ${n}×${n}`);
  }
}

// --- Statistik, Katzen ---------------------------------------------------------------------
check(formatTime(75) === '01:15' && formatLong(3600 + 180) === '1 h 03 min' && formatLong(59) === '00:59', 'Zeitformat');
{
  const avg = averageBySize(LEVELS, { 1: { time: 10 }, 2: { time: 20 } });
  check(avg[0].avg === 15 && avg[0].solved === 2, 'Durchschnitt je Größe');
  check(SKINS.length >= 15 && new Set(SKINS.map((s) => s.id)).size === SKINS.length, 'Katzen eindeutig');
  check(isSkinUnlocked(SKINS[0], { stars: 0, streak: 0, daily: 0 }), 'erste Katze immer frei');
  const ms = nextMilestone({ stars: 3, streak: 0, daily: 0 });
  check(ms && ms.skin.id === 'tiger' && ms.text === 'Noch 7★ bis Tiger', `nächster Meilenstein (${ms?.text})`);
  check(SKINS.every((s) => unlockProgress(s, { stars: 999, streak: 99, daily: 999 }) === 1), 'alles freischaltbar');
}

// --- Datum, Serie, Woche ---------------------------------------------------------------------
{
  // Zeitumstellungen: Santiago stellt um Mitternacht um, Berlin um 2/3 Uhr
  check(addDays('2026-09-05', 1) === '2026-09-06' && addDays('2026-04-04', 1) === '2026-04-05', 'addDays über Zeitumstellung (Santiago)');
  check(addDays('2026-03-28', 1) === '2026-03-29' && addDays('2026-10-25', -1) === '2026-10-24', 'addDays über Zeitumstellung (Europa)');
  let k = '2026-01-01';
  for (let i = 0; i < 400; i++) k = addDays(k, 1);
  check(k === '2027-02-05', `400 Tage weiter (${k})`);
  check(dateKey(parseKey('2026-09-06')) === '2026-09-06', 'parseKey/dateKey');
  const w = weekView({ '2026-11-02': {}, '2026-11-04': {} }, '2026-11-05');
  check(eq(w.map((d) => d.label), ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So']), 'Woche beginnt montags');
  check(eq(w.map((d) => d.state), ['done', 'missed', 'done', 'today', 'future', 'future', 'future']), 'Wochenstatus');
  check(streakInfo({ '2026-11-04': {} }, '2026-11-05').current === 1 && bestStreak({ a: 0 }) === 1, 'Serie');
}

// --- Tutorial-Schrittfolge ---------------------------------------------------------------------
{
  const level = { ...TUTORIAL_LEVEL, regionArr: [...TUTORIAL_LEVEL.regions].map((c) => parseInt(c, 36)) };
  const geo = makeGeometry(level.n, level.regionArr);
  const marks = new Uint8Array(level.n * level.n);
  const kinds = [];
  for (let i = 0; i < 40; i++) {
    const h = computeHint(geo, level, marks);
    if (!h.action) break;
    kinds.push(h.kind);
    if (h.action.type === 'cat') {
      marks[h.action.cells[0]] = MARK_CAT;
      for (const x of geo.attacks[h.action.cells[0]]) if (marks[x] === MARK_EMPTY) marks[x] = MARK_AUTO_X;
    } else h.action.cells.forEach((c) => { marks[c] = MARK_X; });
  }
  const cats = [...marks].filter((m) => m === MARK_CAT).length;
  check(cats === level.n, 'Tutorial lässt sich mit den Schritten lösen');
  check(kinds[0] === 'single' && kinds.includes('confine') && kinds.includes('blockade'), `Tutorial zeigt Einzelfeld, Eingesperrt, Blockade (${kinds.join(', ')})`);
  check(!kinds.includes('blocked') && !kinds.includes('contradiction'), 'Tutorial ohne Widerspruch und ohne fehlende X');
}

// --- Planer ---------------------------------------------------------------------------------------
{
  const sch = new Scheduler();
  let ran = 0;
  sch.after(5, () => { ran++; });
  sch.after(5, () => { ran++; });
  sch.clear();
  await new Promise((r) => setTimeout(r, 20));
  check(ran === 0 && sch.ids.size === 0, 'Planer räumt Zeitgeber ab');
}

const tz = process.env.TZ || Intl.DateTimeFormat().resolvedOptions().timeZone;
if (failed) {
  console.error(`\n${failed} von ${count} Prüfungen fehlgeschlagen (Zeitzone ${tz}).`);
  process.exit(1);
}
console.log(`Unit-Tests: ${count} Prüfungen bestanden (Zeitzone ${tz}). ✔`);
