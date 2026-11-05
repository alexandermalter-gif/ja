// Berechnet die Tagesrätsel im Voraus und schreibt src/logic/dailies.js.
//   node tools/generate-dailies.mjs [--days 730] [--legacy datei.json]
// Einträge vor V3_START (eingefrorene Version 2) bleiben unverändert erhalten.
// Läuft parallel auf allen Kernen (worker_threads).
import { Worker, isMainThread, parentPort, workerData } from 'node:worker_threads';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import os from 'node:os';

const here = fileURLToPath(import.meta.url);
const root = join(dirname(here), '..');
const OUT = join(root, 'src', 'logic', 'dailies.js');
export const V3_START = '2026-11-06';

const pad = (v) => String(v).padStart(2, '0');
const parseKey = (k) => { const [y, m, d] = k.split('-').map(Number); return new Date(y, m - 1, d, 12); };
const keyOf = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const addDays = (k, n) => { const d = parseKey(k); d.setDate(d.getDate() + n); return keyOf(d); };
const diffDays = (a, b) => Math.round((parseKey(b) - parseKey(a)) / 86400000);

if (!isMainThread) {
  const { generateDailyV3, encodeDaily } = await import('../src/logic/dailyGen.js');
  const out = {};
  for (const key of workerData.keys) out[key] = encodeDaily(await generateDailyV3(key));
  parentPort.postMessage(out);
} else {
  const args = process.argv.slice(2);
  const opt = (name, def) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : def; };
  const days = Number(opt('--days', 730));
  const legacyFile = opt('--legacy', null);

  // Eingefrorener Teil vor V3_START
  let start = null;
  const frozen = [];
  if (legacyFile) {
    const { encodeDaily } = await import('../src/logic/dailyGen.js');
    const legacy = JSON.parse(readFileSync(legacyFile, 'utf8'));
    const keys = Object.keys(legacy).filter((k) => k < V3_START).sort();
    start = keys[0];
    keys.forEach((k, i) => { if (addDays(start, i) !== k) throw new Error(`Lücke im alten Teil bei ${k}`); frozen.push(encodeDaily(legacy[k])); });
  } else if (existsSync(OUT)) {
    const mod = await import(`${OUT}?t=${Date.now()}`);
    start = mod.DAILY_TABLE_START;
    frozen.push(...mod.DAILY_TABLE.slice(0, Math.max(0, diffDays(start, V3_START))));
  } else {
    start = V3_START;
  }
  if (addDays(start, frozen.length) !== V3_START) throw new Error('Alter Teil endet nicht direkt vor V3_START');

  const keys = Array.from({ length: days }, (_, i) => addDays(V3_START, i));
  const threads = Math.max(1, os.cpus().length);
  const t0 = Date.now();
  const parts = Array.from({ length: threads }, (_, t) => keys.filter((_, i) => i % threads === t));
  const results = await Promise.all(parts.map((part) => new Promise((resolve, reject) => {
    const w = new Worker(here, { workerData: { keys: part } });
    w.on('message', resolve);
    w.on('error', reject);
  })));
  const all = Object.assign({}, ...results);
  const table = [...frozen, ...keys.map((k) => all[k])];
  const body = `// Automatisch erzeugt von tools/generate-dailies.mjs – nicht von Hand bearbeiten.
// Vorberechnete Tagesrätsel: gleich auf allen Geräten, ohne Wartezeit und unabhängig von
// späteren Änderungen an Löser oder Generator. Eintrag: „regions|colors|solution|score|maxTech“.
// Bis ${addDays(V3_START, -1)}: Version 2 (eingefroren), ab ${V3_START}: Version 3.
export const DAILY_TABLE_START = '${start}';
export const DAILY_TABLE = [
${table.map((e) => `  '${e}',`).join('\n')}
];
`;
  writeFileSync(OUT, body);
  console.log(`${table.length} Tagesrätsel (${start} bis ${addDays(start, table.length - 1)}) in ${((Date.now() - t0) / 1000).toFixed(0)} s → src/logic/dailies.js`);
}
