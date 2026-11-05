// Vergibt Farben und Stufenangabe der 100 Level neu (Rätsel und Lösungen bleiben unverändert).
//   node tools/recolor-levels.mjs
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { mulberry32, assignColors } from '../src/logic/generator.js';
import { LEVELS } from '../src/logic/levels.js';
import { tierFor } from '../src/game/scoring.js';

const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'logic', 'levels.js');
const levels = LEVELS.map((l) => ({ ...l }));
const colorRng = mulberry32(20261010); // eigener Zufallsstrom nur für Farben
let changed = 0;
for (const l of levels) {
  const colors = assignColors(l.n, [...l.regions].map((ch) => parseInt(ch, 36)), colorRng);
  if (colors.join() !== l.colors.join()) changed++;
  l.colors = colors;
  l.tier = tierFor(l);
}
const header = '// Automatisch erzeugt von tools/generate-levels.mjs – nicht von Hand bearbeiten.\n';
writeFileSync(OUT, `${header}export const LEVELS = [\n${levels.map((l) => `  ${JSON.stringify(l)},`).join('\n')}\n];\n`);
console.log(`Farben neu vergeben: ${changed} von ${levels.length} Leveln geändert.`);
