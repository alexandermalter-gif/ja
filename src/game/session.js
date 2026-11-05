// Spielsitzung ohne Grafik: Markierungen, Notizen, Rückgängig und Abfragen zu Konflikten,
// Fehlern, blockierten Feldern und Sieg. Wird von main.js genutzt und in tools/test-units.mjs geprüft.
import { MARK_EMPTY, MARK_X, MARK_CAT, MARK_AUTO_X } from './marks.js';

export const isX = (m) => m === MARK_X || m === MARK_AUTO_X;
const UNDO_LIMIT = 300;

/** Fingerabdruck eines Rätsels (Regionen + Lösung) – passt ein gespeicherter Stand noch zum Rätsel? */
export function puzzleSig(level) {
  const s = `${level.n}|${level.regions}|${level.solution.join(',')}`;
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(36);
}

export class Session {
  /** level: geparstes Level (n, regionArr, solution); geo: makeGeometry(...) */
  constructor(level, geo, saved = null) {
    const n = level.n;
    this.level = level;
    this.geo = geo;
    this.n = n;
    this.solution = new Set(level.solution.map((c, r) => r * n + c));
    const fits = saved && saved.marks?.length === n * n && (!saved.sig || saved.sig === puzzleSig(level));
    this.marks = fits ? Uint8Array.from(saved.marks) : new Uint8Array(n * n);
    this.notes = fits && saved.notes ? Uint8Array.from(saved.notes) : new Uint8Array(n * n);
    this.history = [];
    this.restored = !!fits;
  }

  snapshot() {
    this.history.push({ marks: this.marks.slice(), notes: this.notes.slice() });
    if (this.history.length > UNDO_LIMIT) this.history.shift();
  }

  /** Letzten Schnappschuss zurückholen; gibt den alten Zustand zurück (für die Darstellung). */
  undo() {
    const prev = this.history.pop();
    if (!prev) return null;
    const before = { marks: this.marks, notes: this.notes };
    this.marks = prev.marks;
    this.notes = prev.notes;
    return before;
  }

  /** Wird Feld i von einer Katze blockiert (Zeile, Spalte, Farbe, Nachbarn)? */
  attackedByCat(i, ignore = -1) {
    for (const a of this.geo.attacks[i]) if (a !== ignore && this.marks[a] === MARK_CAT) return true;
    return false;
  }

  /** Katzen, die Feld i blockieren. */
  blockingCats(i) {
    return this.geo.attacks[i].filter((a) => this.marks[a] === MARK_CAT);
  }

  dist(a, b) {
    const n = this.n;
    return Math.hypot(Math.floor(a / n) - Math.floor(b / n), (a % n) - (b % n));
  }

  /** Leere, von Katzen blockierte Felder → Verzögerung (ms) für die X-Welle. */
  autoXTargets(sources, base, stepMs) {
    const delays = new Map();
    for (const s of sources) {
      for (const a of this.geo.attacks[s]) {
        if (this.marks[a] !== MARK_EMPTY) continue;
        const d = base + (this.dist(s, a) - 1) * stepMs;
        delays.set(a, Math.min(delays.get(a) ?? Infinity, d));
      }
    }
    return delays;
  }

  catCells() {
    const out = [];
    this.marks.forEach((m, i) => { if (m === MARK_CAT) out.push(i); });
    return out;
  }

  /** Auto-X, die keine Katze mehr blockiert. */
  staleAutoX() {
    const out = [];
    this.marks.forEach((m, i) => { if (m === MARK_AUTO_X && !this.attackedByCat(i)) out.push(i); });
    return out;
  }

  conflictCells() {
    const out = new Set();
    this.marks.forEach((m, i) => {
      if (m !== MARK_CAT) return;
      for (const a of this.geo.attacks[i]) if (this.marks[a] === MARK_CAT) { out.add(i); out.add(a); }
    });
    return out;
  }

  /** Echte Fehler: eigene X auf Lösungsfeldern und falsche Katzen (Auto-X sind nur Folgen). */
  mistakes() {
    const out = [];
    this.marks.forEach((m, i) => {
      if (m === MARK_CAT && !this.solution.has(i)) out.push(i);
      if (m === MARK_X && this.solution.has(i)) out.push(i);
    });
    return out;
  }

  /** Lösungsfeld für den Joker: keine Katze, möglichst nicht blockiert, Farbe mit den meisten offenen Feldern. */
  jokerCell() {
    const missing = [...this.solution].filter((c) => this.marks[c] !== MARK_CAT);
    if (!missing.length) return -1;
    const open = (c) => this.geo.units[this.geo.cellUnits[c][2]].cells.filter((x) => this.marks[x] === MARK_EMPTY).length;
    missing.sort((a, b) => (this.attackedByCat(a) - this.attackedByCat(b)) || (open(b) - open(a)));
    return missing[0];
  }

  catCount() {
    return this.catCells().length;
  }

  foundRegions() {
    const found = new Set();
    this.marks.forEach((m, i) => { if (m === MARK_CAT) found.add(this.level.regionArr[i]); });
    return found;
  }

  isSolved() {
    if (this.catCount() !== this.n || this.conflictCells().size) return false;
    const units = new Set();
    this.marks.forEach((m, i) => { if (m === MARK_CAT) this.geo.cellUnits[i].forEach((u) => units.add(u)); });
    return units.size === 3 * this.n;
  }

  isEmpty() {
    return !this.marks.some((m) => m !== MARK_EMPTY) && !this.notes.some((v) => v);
  }
}
