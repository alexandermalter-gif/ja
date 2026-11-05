// Prüft, ob die eingecheckten Dateien in dist/ zum Quellcode passen (baut in einen Temp-Ordner und vergleicht).
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const tmp = mkdtempSync(join(tmpdir(), 'katzen-dist-'));
try {
  execFileSync(process.execPath, [join(root, 'tools', 'build.mjs')], { env: { ...process.env, BUILD_OUT: tmp }, stdio: 'pipe' });
  const stale = ['game.js', 'katzen-sudoku.html'].filter((f) => readFileSync(join(tmp, f), 'utf8') !== readFileSync(join(root, 'dist', f), 'utf8'));
  if (stale.length) {
    console.error(`dist ist veraltet: ${stale.join(', ')} – bitte „npm run build“ ausführen und einchecken.`);
    process.exit(1);
  }
  console.log('dist ist aktuell. ✔');
} finally {
  rmSync(tmp, { recursive: true, force: true });
}
