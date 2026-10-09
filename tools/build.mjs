// Baut das Spiel:
//   dist/game.js            – gebündeltes Skript für index.html
//   dist/katzen-sudoku.html – komplette Einzeldatei (CSS + JS eingebettet, läuft auch per Doppelklick)
import { build } from 'esbuild';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(root, 'dist');
mkdirSync(dist, { recursive: true });

await build({
  entryPoints: [join(root, 'src', 'main.js')],
  bundle: true,
  format: 'iife',
  minify: true,
  target: ['es2020'],
  outfile: join(dist, 'game.js'),
  legalComments: 'none',
  logLevel: 'info',
});

const html = readFileSync(join(root, 'index.html'), 'utf8');
const css = readFileSync(join(root, 'style.css'), 'utf8');
const js = readFileSync(join(dist, 'game.js'), 'utf8').replace(/<\/script/gi, '<\\/script');
const app = html.slice(html.indexOf('<!-- APP:START -->'), html.indexOf('<!-- APP:END -->') + '<!-- APP:END -->'.length);

const standalone = html
  .replace('<link rel="stylesheet" href="style.css">', () => `<style>\n${css}</style>`)
  .replace('<script src="dist/game.js"></script>', () => `<script>${js}</script>`);
writeFileSync(join(dist, 'katzen-sudoku.html'), standalone);

// Fragment ohne <html>/<head>/<body> – zum Einbetten bzw. Veröffentlichen als Artifact.
const fontLink = html.match(/<link rel="stylesheet" href="https:\/\/fonts\.googleapis\.com[^>]*>/)[0];
const fragment = `<title>Katzen-Sudoku</title>\n${fontLink}\n<style>\n${css}</style>\n${app}\n<script>${js}</script>\n`;
writeFileSync(join(dist, 'embed.html'), fragment);

console.log('Fertig: dist/game.js, dist/katzen-sudoku.html, dist/embed.html');
