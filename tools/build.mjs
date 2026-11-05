// Baut das Spiel:
//   dist/game.js            – gebündeltes Skript für index.html
//   dist/katzen-sudoku.html – komplette Einzeldatei (CSS, Schrift, JS eingebettet; läuft auch per Doppelklick)
//   dist/embed.html         – Fragment ohne <html>/<head>/<body> zum Einbetten (z. B. als Artifact)
// Jede Ersetzung wird geprüft: Ändert sich index.html so, dass ein Platzhalter fehlt, bricht der Build ab.
import { build } from 'esbuild';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(root, 'dist');
const outDir = process.env.BUILD_OUT || dist;
mkdirSync(outDir, { recursive: true });

function fail(msg) {
  console.error(`Build abgebrochen: ${msg}`);
  process.exit(1);
}

/** Ersetzt `needle` genau einmal – sonst Abbruch. */
function replaceOnce(text, needle, replacement, what) {
  const count = text.split(needle).length - 1;
  if (count !== 1) fail(`${what}: „${needle}“ kommt ${count}-mal vor (erwartet: genau einmal).`);
  return text.replace(needle, () => replacement);
}

// „inline-worker:pfad“ → der Worker wird eigens gebündelt und als Text eingebettet
const inlineWorker = {
  name: 'inline-worker',
  setup(b) {
    b.onResolve({ filter: /^inline-worker:/ }, (a) => ({ path: join(a.resolveDir, a.path.slice('inline-worker:'.length)), namespace: 'inline-worker' }));
    b.onLoad({ filter: /.*/, namespace: 'inline-worker' }, async (a) => {
      const r = await build({ entryPoints: [a.path], bundle: true, write: false, format: 'iife', minify: true, target: ['es2020'], legalComments: 'none' });
      return { contents: r.outputFiles[0].text, loader: 'text' };
    });
  },
};

await build({
  entryPoints: [join(root, 'src', 'main.js')],
  plugins: [inlineWorker],
  bundle: true,
  format: 'iife',
  minify: true,
  target: ['es2020'],
  outfile: join(outDir, 'game.js'),
  legalComments: 'none',
  logLevel: 'info',
});

const html = readFileSync(join(root, 'index.html'), 'utf8');
const cssRaw = readFileSync(join(root, 'style.css'), 'utf8');
const font = readFileSync(join(root, 'assets', 'nunito-latin.woff2')).toString('base64');
const js = readFileSync(join(outDir, 'game.js'), 'utf8').replace(/<\/script/gi, '<\\/script');

// Schrift als data:-URI einbetten (keine Anfrage an fremde Server, auch offline)
const css = replaceOnce(cssRaw, 'url(assets/nunito-latin.woff2)', `url(data:font/woff2;base64,${font})`, 'style.css');

const START = '<!-- APP:START -->';
const END = '<!-- APP:END -->';
if (html.split(START).length !== 2 || html.split(END).length !== 2) fail('Marker APP:START/APP:END fehlen oder sind doppelt.');
const app = html.slice(html.indexOf(START), html.indexOf(END) + END.length);

let standalone = replaceOnce(html, '<link rel="stylesheet" href="style.css">', `<style>\n${css}</style>`, 'index.html (CSS)');
standalone = replaceOnce(standalone, '<script src="dist/game.js"></script>', `<script>${js}</script>`, 'index.html (Skript)');

// Fragment: Zeichensatz und Viewport mitgeben, falls die Einbettung sie nicht setzt
const fragment = `<meta charset="utf-8">\n<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n<title>Katzen-Sudoku</title>\n<style>\n${css}</style>\n${app}\n<script>${js}</script>\n`;

// Abschlussprüfung: keine Verweise auf externe Dateien mehr, plausible Größe
for (const [name, text] of [['katzen-sudoku.html', standalone], ['embed.html', fragment]]) {
  for (const bad of ['src="dist/', 'href="style.css', 'fonts.googleapis.com', 'url(assets/']) {
    if (text.includes(bad)) fail(`${name} enthält noch „${bad}“.`);
  }
  if (text.length < 500_000) fail(`${name} ist mit ${text.length} Byte verdächtig klein.`);
}

writeFileSync(join(outDir, 'katzen-sudoku.html'), standalone);
writeFileSync(join(outDir, 'embed.html'), fragment);
console.log(`Fertig: ${outDir === dist ? 'dist' : outDir}/game.js, katzen-sudoku.html, embed.html`);
