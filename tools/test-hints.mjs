// Löst jedes Level nur mit Hinweisen (wie ein Spieler, der immer „Ausführen“ drückt).
import { LEVELS } from '../src/logic/levels.js';
import { makeGeometry } from '../src/logic/solver.js';
import { computeHint } from '../src/game/hints.js';
import { MARK_EMPTY, MARK_X, MARK_CAT } from '../src/game/marks.js';

const examples = new Map();
let failed = 0;
for (const l of LEVELS) {
  const level = { ...l, regionArr: [...l.regions].map((ch) => parseInt(ch, 36)) };
  const geo = makeGeometry(l.n, level.regionArr);
  const marks = new Uint8Array(l.n * l.n);
  // Ein absichtlicher Fehler zu Beginn: X auf ein Lösungsfeld.
  marks[l.solution[0]] = MARK_X;
  let steps = 0;
  for (; steps < 500; steps++) {
    const cats = marks.filter((m) => m === MARK_CAT).length;
    if (cats === l.n) break;
    const h = computeHint(geo, level, marks);
    if (!h.action) { failed++; console.error(`Level ${l.id}: kein Hinweis, obwohl ungelöst`); break; }
    const kind = h.text.split(' ').slice(0, 3).join(' ');
    if (!examples.has(kind)) examples.set(kind, `L${l.id}: ${h.text}`);
    const a = h.action;
    if (a.type === 'cat') marks[a.cells[0]] = MARK_CAT;
    if (a.type === 'x') a.cells.forEach((c) => { if (marks[c] === MARK_EMPTY) marks[c] = MARK_X; });
    if (a.type === 'clear') a.cells.forEach((c) => { marks[c] = MARK_EMPTY; });
  }
  const sol = new Set(l.solution.map((c, r) => r * l.n + c));
  const ok = [...marks.keys()].filter((i) => marks[i] === MARK_CAT).every((i) => sol.has(i));
  if (!ok || steps >= 500) { failed++; console.error(`Level ${l.id}: Hinweise führen nicht zur Lösung`); }
}
for (const t of examples.values()) console.log('•', t);
if (failed) process.exit(1);
console.log(`\nAlle ${LEVELS.length} Level lassen sich allein mit Hinweisen lösen. ✔`);
