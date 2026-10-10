// Farbpalette der Regionen – Name, Feldfarbe, Pastellton für die Katzenleiste.
//
// Die Farben sind so abgestimmt, dass jedes Paar gut unterscheidbar ist
// (geprüft mit tools/check-palette.mjs, Teil von `npm test`):
//   – normales Sehen:            OKLab ΔE ≥ 15 für alle 45 Paare
//   – Rot-Grün- und Blau-Gelb-Schwäche (simuliert): ΔE ≥ 8 für alle Paare
//   – helle und dunkle X bleiben auf jeder Farbe sichtbar
// Zusätzlich verteilt assignColors() die Farben so, dass die ähnlichsten Paare
// möglichst nie direkt nebeneinander liegen.
import { pairDistance, tint, oklab } from './colorMath.js';

const BASE = [
  { name: 'Lila', hex: '#ab87e2' },
  { name: 'Hellgrün', hex: '#81bf88' },
  { name: 'Braun', hex: '#8b5f2e' },
  { name: 'Orange', hex: '#ec933a' },
  { name: 'Gelb', hex: '#e7d35b' },
  { name: 'Dunkelblau', hex: '#546aaf' },
  { name: 'Rosa', hex: '#faa7c3' },
  { name: 'Rot', hex: '#d16269' },
  { name: 'Türkis', hex: '#17999a' },
  { name: 'Himmelblau', hex: '#6cc9fb' },
];

export const PALETTE = BASE.map((p) => ({ ...p, pastel: tint(p.hex, 0.55) }));

/**
 * Abstand je Farbpaar als eine Zahl: der schlechtere Wert aus normalem Sehen und
 * Farbschwäche (Farbschwäche-Werte auf dieselbe Skala gebracht: 8 ≙ 15).
 */
export const PAIR_DIST = PALETTE.map((a) => PALETTE.map((b) => {
  if (a === b) return 0;
  const d = pairDistance(a.hex, b.hex);
  return Math.min(d.normal, d.cvd * (15 / 8));
}));

/** Wie dunkel eine Farbe ist (0 = hell) – dunkle Farben sollen eher auf kleine Flächen. */
export const DARKNESS = PALETTE.map((p) => Math.max(0, 0.72 - oklab(p.hex)[0]));

// Farben der X-Markierungen (eigene X dunkelgrau, automatische X hellgrau)
export const X_COLORS = { own: '#2e2c31', auto: '#f1efec' };
