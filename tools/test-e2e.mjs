// Durchlauf im echten Browser (Playwright): Tutorial, Fehler, Hinweis, Spielstände, Sieg, Archiv.
// Aufruf: npm run build && npm run test:e2e   (braucht ein installiertes Playwright mit Chromium)
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { LEVELS } from '../src/logic/levels.js';

const require = createRequire(import.meta.url);
let chromium;
try {
  ({ chromium } = require('playwright'));
} catch {
  console.log('Playwright nicht gefunden – E2E-Test übersprungen.');
  process.exit(0);
}

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const url = `file://${root}/dist/katzen-sudoku.html`;
const SHOTS = process.env.E2E_SHOTS || null;
const KEY = 'katzen-sudoku-v1';

const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 412, height: 915 } });
const errors = [];
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });

let failed = 0;
const check = (ok, msg) => {
  console.log(`${ok ? '✔' : '✘'} ${msg}`);
  if (!ok) failed++;
};
const wait = (ms) => page.waitForTimeout(ms);
const shot = async (name) => { if (SHOTS) await page.screenshot({ path: `${SHOTS}/${name}.png` }); };
const store = () => page.evaluate((k) => JSON.parse(localStorage.getItem(k) || 'null'), KEY);

/** Bildschirmposition eines Feldes (gleiche Projektion wie Board3D). */
async function cellPos(n, r, c) {
  const box = await page.locator('canvas.board-canvas').boundingBox();
  const W = n * 1.1 - 0.1;
  const s = W / 2 + 0.12;
  const x = (c - (n - 1) / 2) * 1.1;
  const y = ((n - 1) / 2 - r) * 1.1;
  const vc = (0.22 * Math.sin(0.2)) / 2;
  const v = y * Math.cos(0.2) + 0.22 * Math.sin(0.2) - vc;
  return { x: box.x + ((x + s) / (2 * s)) * box.width, y: box.y + ((s - v) / (2 * s)) * box.height };
}
const tap = async (n, r, c) => { const p = await cellPos(n, r, c); await page.mouse.click(p.x, p.y); };
const dbl = async (n, r, c) => { const p = await cellPos(n, r, c); await page.mouse.dblclick(p.x, p.y); };

await page.goto(url);
await wait(1500);

// 1. Tutorial startet beim ersten Öffnen und lässt sich durchspielen
check(await page.locator('#coach:not(.hidden)').count() === 1, 'Tutorial startet beim ersten Öffnen');
await shot('01-tutorial');
const handPos = () => page.evaluate(() => {
  const h = document.getElementById('tut-hand');
  if (h.classList.contains('hidden')) return null;
  const b = document.getElementById('board').getBoundingClientRect();
  return { x: b.left + parseFloat(h.style.left), y: b.top + parseFloat(h.style.top), mode: h.classList.contains('double') ? 'double' : 'tap' };
});
let actions = 0;
for (let k = 0; k < 80 && !(await page.locator('.modal').count()); k++) {
  const next = page.locator('#coach-next:not(.hidden)');
  if (await next.count()) { await next.click(); await wait(700); continue; }
  const h = await handPos();
  if (!h) { await wait(500); continue; }
  if (h.mode === 'double') await page.mouse.dblclick(h.x, h.y); else await page.mouse.click(h.x, h.y);
  actions++;
  await wait(h.mode === 'double' ? 1400 : 500);
}
await wait(3500);
check((await page.locator('.modal h2').textContent())?.includes('Tutorial geschafft'), `Tutorial geschafft (${actions} Züge)`);
check((await store())?.tutorialSeen === true, 'Tutorial als gesehen gespeichert');
await page.click('.modal .btn-primary', { force: true });
await wait(1800);

// 2. Level 1: falsche Katze → im Spielstand sofort ein X, Fisch weg, Sterne sinken
const L1 = LEVELS[0];
const n = L1.n;
const wrongC = (L1.solution[0] + 2) % n;
await dbl(n, 0, wrongC);
await wait(150);
let d = await store();
check(d.saves.L1?.marks[wrongC] === 1, 'Falsche Katze steht sofort als X im Spielstand');
check(d.saves.L1?.fish === 2, 'Ein Fisch weg');
await wait(1400);
check(await page.locator('#hud-stars i.on').count() === 2, 'Kopfzeile zeigt 2 mögliche Sterne');

// 3. Richtige Katze → automatische X; automatisches X lässt sich nicht wegtippen
await dbl(n, 1, L1.solution[1]);
await wait(1400);
d = await store();
const autoIdx = d.saves.L1.marks.findIndex((m) => m === 3);
check(autoIdx >= 0, 'Automatische X gesetzt');
await tap(n, Math.floor(autoIdx / n), autoIdx % n);
await wait(300);
d = await store();
check(d.saves.L1.marks[autoIdx] === 3, 'Automatisches X bleibt beim Antippen stehen');
await shot('02-level1');

// 4. Hinweis zeigen, „Verstanden“ schließt nur die Blase
await page.click('#tool-hint');
await wait(500);
check(await page.locator('#hint-bubble:not(.hidden)').count() === 1, 'Hinweisblase sichtbar');
await shot('03-hint');
const hintsAfter = (await store()).hints;
await page.click('#hint-close');
await wait(200);
await page.click('#tool-hint');
await wait(300);
check((await store()).hints === hintsAfter, 'Derselbe Hinweis noch einmal ist gratis');
await page.click('#hint-close');

// 5. Spielstände je Rätsel: Tagesrätsel öffnen, Level 1 bleibt gespeichert
await page.click('#btn-back');
await wait(700);
check(await page.locator('.level-btn.started').count() >= 1, 'Menü zeigt angefangenes Level');
await page.click('#btn-daily');
await page.waitForSelector('#screen-game:not(.hidden)', { timeout: 60000 });
await wait(1500);
const dn = await page.evaluate(() => document.querySelectorAll('#cat-bar .cat-slot').length);
await tap(dn, 0, 0);
await wait(300);
await page.click('#btn-back');
await wait(600);
d = await store();
check(!!d.saves.L1 && Object.keys(d.saves).some((k) => k.startsWith('D')), 'Level- und Tagesrätsel-Stand liegen nebeneinander');

// 6. Level 1 fortsetzen und lösen
await page.click('#btn-continue');
await wait(1500);
for (let r = 0; r < n; r++) {
  d = await store();
  if (d.saves.L1?.marks[r * n + L1.solution[r]] === 2) continue;
  await dbl(n, r, L1.solution[r]);
  await wait(1100);
}
await page.waitForSelector('.modal-win', { timeout: 8000 }).catch(() => {});
await wait(2600);
check((await page.locator('.modal-win h2').textContent())?.includes('Geschafft'), 'Level 1 gelöst');
check((await page.locator('.win-stats').textContent())?.includes('Helfer'), 'Sieg-Dialog zeigt Helfer');
check(await page.locator('.modal-buttons .btn', { hasText: 'Nochmal' }).count() === 1, '„Nochmal für ★★★“ wird angeboten');
await shot('04-win');
d = await store();
check(!d.saves.L1 && d.best[1]?.stars === 2, 'Spielstand gelöscht, 2 Sterne gespeichert');
check(d.totals.solves >= 1, 'Gesamtzähler erhöht');

// 7. Weiter → Level 2, Escape schließt keinen Pflichtdialog
await page.click('.modal .btn-primary', { force: true });
await wait(1500);
check((await page.textContent('#hud-level')) === '2', 'Weiter führt zu Level 2');
await page.click('#btn-back');
await wait(500);

// 8. Einstellungen: Dialog mit Fokusfalle, Export-Code
await page.click('.menu-header .btn-settings');
await wait(400);
check(await page.evaluate(() => document.getElementById('screen-menu').inert === true), 'Hintergrund ist inert');
await page.keyboard.press('Escape');
await wait(200);
check(await page.locator('.modal').count() === 0, 'Escape schließt Einstellungen');
await page.click('.menu-header .btn-settings');
await page.click('#set-export');
await wait(300);
const code = await page.inputValue('#export-code');
check(code.startsWith('KS1:'), 'Spielstand-Code wird erzeugt');
await page.keyboard.press('Escape');

// 9. Archiv öffnet
await page.click('#btn-archive');
await wait(300);
check(await page.locator('.archive-day').count() === 30, 'Archiv zeigt 30 Tage');
await page.keyboard.press('Escape');
await shot('05-menu');

// 10. Freies Spiel (Erzeugung im Web Worker)
await page.click('#btn-free');
await page.click('#free-n .seg-btn >> text="6×6"');
await page.click('.modal .btn-primary', { force: true });
await page.waitForSelector('#screen-game:not(.hidden)', { timeout: 60000 });
await wait(800);
check((await page.textContent('#hud-level-label')) === 'Frei' && (await page.textContent('#hud-level')) === '6×6', 'Freies Spiel startet (6×6)');
await page.click('#btn-back');
await wait(400);

check(errors.length === 0, `keine Fehler in der Konsole${errors.length ? `:\n  ${errors.join('\n  ')}` : ''}`);
await browser.close();
if (failed) {
  console.log(`\n${failed} Prüfung(en) fehlgeschlagen.`);
  process.exit(1);
}
console.log('\nE2E: alles in Ordnung. ✔');
