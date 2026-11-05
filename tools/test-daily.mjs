// Prüft die Tagesrätsel und das Tutorial-Rätsel:
// – jede Zeile der vorberechneten Tabelle: eindeutig, gespeicherte Lösung stimmt, ohne Raten lösbar
// – Wochentagsziele (Größe, Mindesttechnik, größte Farbfläche) ab Version 3
// – feste Fingerabdrücke: Ändert sich ein Rätsel (Tabelle oder Live-Generator), schlägt der Test fehl.
//   Das ist Absicht: Tagesrätsel müssen für alle gleich bleiben. Nur bei einer bewusst neuen
//   Version die Fingerabdrücke (und DAILY_CACHE_VERSION) anpassen.
// – Serien-Berechnung
import { createHash } from 'node:crypto';
import { DAILY_TABLE_START, DAILY_TABLE } from '../src/logic/dailies.js';
import { DAILY_TARGETS, MAX_REGION_SHARE, generateDailyV3, encodeDaily } from '../src/logic/dailyGen.js';
import { generateDaily, addDays, parseKey, planFor, streakInfo, bestStreak, tableDaily } from '../src/game/daily.js';
import { TUTORIAL_LEVEL } from '../src/game/tutorial.js';
import { makeGeometry, countSolutions, gradePuzzle } from '../src/logic/solver.js';

const V3_START = '2026-11-06';
let failed = 0;
const check = (cond, msg) => { if (!cond) { failed++; console.error(`✘ ${msg}`); } };
const fp = (level) => createHash('sha1').update(encodeDaily(level)).digest('hex').slice(0, 12);

function verify(level, name) {
  const regions = [...level.regions].map((c) => parseInt(c, 36));
  const geo = makeGeometry(level.n, regions);
  const { count, solutions } = countSolutions(geo, 2);
  check(count === 1, `${name}: ${count} Lösungen`);
  const expected = level.solution.map((c, r) => r * level.n + c).sort((a, b) => a - b).join(',');
  check(solutions[0]?.join(',') === expected, `${name}: gespeicherte Lösung stimmt nicht`);
  check(gradePuzzle(geo).solved, `${name}: nicht ohne Raten lösbar`);
  return regions;
}

const t0 = Date.now();
verify(TUTORIAL_LEVEL, 'Tutorial');

// 1. Ganze Tabelle
let tech = { sa: 0, so: 0, sat: 0, sun: 0 };
DAILY_TABLE.forEach((_, i) => {
  const key = addDays(DAILY_TABLE_START, i);
  const lv = tableDaily(key);
  const regions = verify(lv, `Tagesrätsel ${key}`);
  check(lv.colors.length === lv.n && new Set(lv.colors).size === lv.n, `${key}: Farben`);
  if (key >= V3_START) {
    const day = parseKey(key).getDay();
    const plan = DAILY_TARGETS[day];
    check(lv.n === plan.n, `${key}: Größe ${lv.n} statt ${plan.n}`);
    if (lv.n >= 7) {
      const sizes = new Array(lv.n).fill(0);
      for (const k of regions) sizes[k]++;
      check(Math.max(...sizes) <= MAX_REGION_SHARE * lv.n * lv.n, `${key}: Farbfläche zu groß`);
    }
    if (day === 6) { tech.sat++; if (lv.maxTech >= 4) tech.sa++; }
    if (day === 0) { tech.sun++; if (lv.maxTech >= 5) tech.so++; }
  }
});
check(tech.sa / tech.sat > 0.95 && tech.so / tech.sun > 0.95, `Wochenende mit schweren Techniken (Sa ${tech.sa}/${tech.sat}, So ${tech.so}/${tech.sun})`);

// 2. Fingerabdrücke: Tabelle (alter und neuer Teil) und Live-Generator außerhalb der Tabelle
const GOLDEN = {
  '2026-10-12': 'bd112124df6d',
  '2026-11-05': 'f9c8163c7f58',
  '2026-11-06': '668854610743',
  '2026-11-08': 'b99de01aa14f',
  '2026-12-24': '31f79336fa15',
  '2027-01-01': 'a2c965e0748b',
  '2027-03-28': '03d6c83b19a4',
  '2027-06-15': '28450c873c8c',
  '2027-10-31': '300628e16d76',
  '2028-02-29': '255296eb0b44',
  '2028-07-04': '44a8e815635d',
  '2028-11-01': 'e3e3f913d3b7',
};
const GOLDEN_LIVE = { '2030-01-07': '2c067f1bcca1', '2030-01-08': 'fdf7d4701f4d' };
const print = process.argv.includes('--print');
for (const [key, want] of Object.entries(GOLDEN)) {
  const got = fp(await generateDaily(key));
  if (print) console.log(`  '${key}': '${got}',`);
  else check(got === want, `${key}: Tagesrätsel hat sich verändert (${got} statt ${want})`);
}
for (const [key, want] of Object.entries(GOLDEN_LIVE)) {
  check(!tableDaily(key), `${key} sollte außerhalb der Tabelle liegen`);
  const lv = await generateDaily(key);
  const got = fp(lv);
  if (print) console.log(`  live '${key}': '${got}',`);
  else check(got === want, `${key}: Live-Generator liefert ein anderes Rätsel (${got} statt ${want})`);
  check(fp(await generateDailyV3(key)) === got, `${key}: nicht reproduzierbar`);
  check(lv.n === planFor(key).n, `${key}: falsche Größe`);
  verify(lv, `live ${key}`);
}

// 3. Serie
const solved = { '2026-10-07': {}, '2026-10-08': {}, '2026-10-09': {}, '2026-10-11': {} };
check(streakInfo(solved, '2026-10-11').current === 1, 'Serie nach Lücke');
check(streakInfo(solved, '2026-10-10').current === 3, 'Serie läuft bis gestern');
check(streakInfo(solved, '2026-10-12').current === 1, 'Serie: heute noch offen');
check(bestStreak(solved) === 3, 'Längste Serie');

if (failed) process.exit(1);
console.log(`Tutorial + ${DAILY_TABLE.length} Tagesrätsel der Tabelle + Live-Generator geprüft (${((Date.now() - t0) / 1000).toFixed(1)} s): eindeutig, ohne Raten lösbar, unverändert. ✔`);
