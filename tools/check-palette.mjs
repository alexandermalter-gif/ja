// Prüft die Farbpalette und die Farbverteilung in allen Rätseln:
//   – alle Farbpaare gut unterscheidbar (normal ΔE ≥ 15, Farbschwächen ΔE ≥ 8)
//   – helle und dunkle X auf jeder Kachel sichtbar
//   – benachbarte Farbflächen in Leveln, Tagesrätseln und Tutorial weit auseinander
import { PALETTE, X_COLORS } from '../src/logic/palette.js';
import { deltaE } from '../src/logic/colorMath.js';
import { minAdjacentDistance } from '../src/logic/generator.js';
import { LEVELS } from '../src/logic/levels.js';
import { generateDaily, addDays } from '../src/game/daily.js';
import { TUTORIAL_LEVEL } from '../src/game/tutorial.js';

const NORMAL_MIN = 15;
const CVD_MIN = 8;
const X_MIN = 15;
const ADJ_MIN = 15; // angrenzende Flächen: mindestens die Paletten-Garantie

let failed = 0;
const fail = (msg) => { failed++; console.error(`✘ ${msg}`); };

const pairs = [];
for (let i = 0; i < PALETTE.length; i++) {
  for (let j = i + 1; j < PALETTE.length; j++) {
    const a = PALETTE[i].hex;
    const b = PALETTE[j].hex;
    const p = {
      name: `${PALETTE[i].name} ↔ ${PALETTE[j].name}`,
      normal: deltaE(a, b),
      cvd: Math.min(deltaE(a, b, 'protan'), deltaE(a, b, 'deutan'), deltaE(a, b, 'tritan')),
    };
    pairs.push(p);
    if (p.normal < NORMAL_MIN) fail(`${p.name}: normal ΔE ${p.normal.toFixed(1)} < ${NORMAL_MIN}`);
    if (p.cvd < CVD_MIN) fail(`${p.name}: Farbschwäche ΔE ${p.cvd.toFixed(1)} < ${CVD_MIN}`);
  }
}
for (const p of PALETTE) {
  for (const [kind, x] of Object.entries(X_COLORS)) {
    const d = deltaE(p.hex, x);
    if (d < X_MIN) fail(`${kind === 'own' ? 'Dunkles' : 'Helles'} X auf ${p.name}: ΔE ${d.toFixed(1)} < ${X_MIN}`);
  }
}

const parse = (l) => [...l.regions].map((c) => parseInt(c, 36));
const adj = [];
for (const l of LEVELS) adj.push({ name: `Level ${l.id}`, d: minAdjacentDistance(l.n, parse(l), l.colors) });
adj.push({ name: 'Tutorial', d: minAdjacentDistance(TUTORIAL_LEVEL.n, parse(TUTORIAL_LEVEL), TUTORIAL_LEVEL.colors) });
let key = '2026-10-12';
for (let i = 0; i < 28; i++) {
  const lv = await generateDaily(key);
  adj.push({ name: `Tagesrätsel ${key}`, d: minAdjacentDistance(lv.n, parse(lv), lv.colors) });
  key = addDays(key, 1);
}
for (const a of adj) if (a.d < ADJ_MIN) fail(`${a.name}: angrenzende Flächen nur ΔE ${a.d.toFixed(1)} auseinander`);

const worst = [...pairs].sort((a, b) => a.normal - b.normal)[0];
const worstCvd = [...pairs].sort((a, b) => a.cvd - b.cvd)[0];
const adjMin = Math.min(...adj.map((a) => a.d));
const adjAvg = adj.reduce((s, a) => s + a.d, 0) / adj.length;
if (failed) process.exit(1);
console.log(`Palette: alle 45 Paare unterscheidbar (schwächstes Paar normal ${worst.name} ${worst.normal.toFixed(1)}, `
  + `bei Farbschwäche ${worstCvd.name} ${worstCvd.cvd.toFixed(1)}); angrenzende Flächen in ${adj.length} Rätseln ≥ ${adjMin.toFixed(1)} (Ø ${adjAvg.toFixed(1)}). ✔`);
