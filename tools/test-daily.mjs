// Prüft das Tägliche Rätsel (8 Wochen) und das Tutorial-Rätsel:
// eindeutig, ohne Raten lösbar, für dasselbe Datum immer gleich; dazu die Serien-Berechnung.
import { generateDaily, addDays, planFor, streakInfo, bestStreak } from '../src/game/daily.js';
import { TUTORIAL_LEVEL } from '../src/game/tutorial.js';
import { makeGeometry, countSolutions, gradePuzzle } from '../src/logic/solver.js';

let failed = 0;
const check = (cond, msg) => { if (!cond) { failed++; console.error(`✘ ${msg}`); } };

function verify(level, name) {
  const regions = [...level.regions].map((c) => parseInt(c, 36));
  const geo = makeGeometry(level.n, regions);
  const { count, solutions } = countSolutions(geo, 2);
  check(count === 1, `${name}: ${count} Lösungen`);
  const expected = level.solution.map((c, r) => r * level.n + c).sort((a, b) => a - b).join(',');
  check(solutions[0]?.join(',') === expected, `${name}: gespeicherte Lösung stimmt nicht`);
  check(gradePuzzle(geo).solved, `${name}: nicht ohne Raten lösbar`);
}

verify(TUTORIAL_LEVEL, 'Tutorial');

let key = '2026-10-12';
const t0 = Date.now();
for (let i = 0; i < 56; i++) {
  const lv = await generateDaily(key);
  check(lv.n === planFor(key).n, `${key}: falsche Größe`);
  verify(lv, `Tagesrätsel ${key}`);
  if (i < 7) {
    const again = await generateDaily(key);
    check(again.regions === lv.regions, `${key}: nicht deterministisch`);
  }
  key = addDays(key, 1);
}

const solved = { '2026-10-07': {}, '2026-10-08': {}, '2026-10-09': {}, '2026-10-11': {} };
check(streakInfo(solved, '2026-10-11').current === 1, 'Serie nach Lücke');
check(streakInfo(solved, '2026-10-10').current === 3, 'Serie läuft bis gestern');
check(streakInfo(solved, '2026-10-12').current === 1, 'Serie: heute noch offen');
check(bestStreak(solved) === 3, 'Längste Serie');

if (failed) process.exit(1);
console.log(`Tutorial + 56 Tagesrätsel geprüft (${((Date.now() - t0) / 1000).toFixed(1)} s): eindeutig, ohne Raten lösbar, reproduzierbar. ✔`);
