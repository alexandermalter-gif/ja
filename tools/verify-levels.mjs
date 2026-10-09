// Prüft alle Level: eindeutige Lösung, gespeicherte Lösung korrekt, rein logisch lösbar, Regionen zusammenhängend.
import { LEVELS } from '../src/logic/levels.js';
import { makeGeometry, countSolutions, gradePuzzle } from '../src/logic/solver.js';

let failed = 0;
const fail = (l, msg) => { failed++; console.error(`Level ${l.id}: ${msg}`); };

for (const l of LEVELS) {
  const n = l.n;
  const regions = [...l.regions].map((ch) => parseInt(ch, 36));
  if (regions.length !== n * n) { fail(l, 'falsche Feldanzahl'); continue; }
  if (l.colors.length !== n || new Set(l.colors).size !== n) fail(l, 'Farben ungültig');
  for (let k = 0; k < n; k++) {
    const cells = regions.map((r, i) => (r === k ? i : -1)).filter((i) => i >= 0);
    if (!cells.length) { fail(l, `Farbe ${k} fehlt`); continue; }
    const seen = new Set([cells[0]]);
    const stack = [cells[0]];
    while (stack.length) {
      const x = stack.pop();
      const r = Math.floor(x / n);
      const c = x % n;
      for (const [dr, dc] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
        const rr = r + dr;
        const cc = c + dc;
        const y = rr * n + cc;
        if (rr >= 0 && rr < n && cc >= 0 && cc < n && regions[y] === k && !seen.has(y)) { seen.add(y); stack.push(y); }
      }
    }
    if (seen.size !== cells.length) fail(l, `Farbe ${k} nicht zusammenhängend`);
  }
  const geo = makeGeometry(n, regions);
  const { count, solutions } = countSolutions(geo, 2);
  if (count !== 1) { fail(l, `${count} Lösungen`); continue; }
  const expected = l.solution.map((c, r) => r * n + c).sort((a, b) => a - b).join(',');
  if (solutions[0].join(',') !== expected) fail(l, 'gespeicherte Lösung stimmt nicht');
  const g = gradePuzzle(geo);
  if (!g.solved) fail(l, 'nicht rein logisch lösbar');
}

const sizes = {};
for (const l of LEVELS) sizes[l.n] = (sizes[l.n] || 0) + 1;
console.log(`${LEVELS.length} Level geprüft (${Object.entries(sizes).map(([n, c]) => `${c}× ${n}×${n}`).join(', ')}).`);
if (failed) { console.error(`${failed} Fehler`); process.exit(1); }
console.log('Alle Level sind eindeutig und ohne Raten lösbar. ✔');
