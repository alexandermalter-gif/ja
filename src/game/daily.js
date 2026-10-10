// Tägliches Rätsel: für alle Spieler am selben Tag gleich (Datum = Zufallsstartwert),
// montags leicht, zum Wochenende hin schwerer. Dazu Serie (Tage in Folge) und Wochenansicht.
import { mulberry32, generateOne, assignColors } from '../logic/generator.js';

export const WEEKDAYS = ['Sonntag', 'Montag', 'Dienstag', 'Mittwoch', 'Donnerstag', 'Freitag', 'Samstag'];
const SHORT = ['So', 'Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa'];

// Index = Date.getDay() (0 = Sonntag)
// score = Bewertung des logischen Lösers (siehe solver.js), Zielbereich je Wochentag
export const DAILY_PLAN = [
  { n: 9, minSize: 3, tier: 3, score: [90, 400] },
  { n: 6, minSize: 1, tier: 0, score: [0, 25] },
  { n: 6, minSize: 2, tier: 1, score: [15, 70] },
  { n: 7, minSize: 2, tier: 1, score: [35, 90] },
  { n: 7, minSize: 2, tier: 2, score: [60, 160] },
  { n: 8, minSize: 2, tier: 2, score: [60, 160] },
  { n: 8, minSize: 2, tier: 3, score: [90, 400] },
];

const pad = (v) => String(v).padStart(2, '0');

/** Lokales Datum als Schlüssel JJJJ-MM-TT. */
export function dateKey(d = new Date()) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function parseKey(key) {
  const [y, m, d] = key.split('-').map(Number);
  return new Date(y, m - 1, d, 12);
}

export function addDays(key, days) {
  const d = parseKey(key);
  d.setDate(d.getDate() + days);
  return dateKey(d);
}

export function planFor(key) {
  const day = parseKey(key).getDay();
  return { ...DAILY_PLAN[day], weekday: WEEKDAYS[day] };
}

export function formatDate(key) {
  return parseKey(key).toLocaleDateString('de-DE', { weekday: 'long', day: 'numeric', month: 'long' });
}

function hashString(s) {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

/**
 * Erzeugt das Rätsel eines Tages. `pause` wird zwischen den Versuchen aufgerufen,
 * damit der Browser zwischendurch zeichnen kann.
 */
export async function generateDaily(key, pause = null) {
  const plan = planFor(key);
  const rng = mulberry32(hashString(`katzen-sudoku-${key}`));
  const [lo, hi] = plan.score;
  const maxCands = plan.n >= 9 ? 4 : 12;
  const cands = [];
  let p = null;
  for (let tries = 0; !p && cands.length < maxCands && tries < 400; tries++) {
    const c = generateOne(plan.n, rng, plan.minSize);
    if (c) {
      cands.push(c);
      if (c.grade.score >= lo && c.grade.score <= hi) p = c;
    }
    if (pause) await pause();
  }
  if (!p) {
    // keiner im Zielbereich: den nächstgelegenen nehmen
    const dist = (c) => (c.grade.score < lo ? lo - c.grade.score : c.grade.score - hi);
    p = cands.sort((a, b) => dist(a) - dist(b))[0];
  }
  return {
    id: key,
    n: p.n,
    regions: p.regions.map((k) => k.toString(36)).join(''),
    colors: assignColors(p.n, p.regions, rng),
    solution: p.solution,
    score: p.grade.score,
    maxTech: p.grade.maxTech,
    tier: plan.tier,
  };
}

/** Aktuelle Serie: Tage in Folge bis heute (oder bis gestern, wenn heute noch offen ist). */
export function streakInfo(solved, today) {
  let current = 0;
  let k = solved[today] ? today : addDays(today, -1);
  while (solved[k]) {
    current++;
    k = addDays(k, -1);
  }
  return { current, todayDone: !!solved[today] };
}

/** Längste Serie überhaupt. */
export function bestStreak(solved) {
  const keys = Object.keys(solved).sort();
  let best = 0;
  let run = 0;
  let prev = null;
  for (const k of keys) {
    run = prev && addDays(prev, 1) === k ? run + 1 : 1;
    best = Math.max(best, run);
    prev = k;
  }
  return best;
}

/** Montag bis Sonntag der aktuellen Woche mit Status je Tag. */
export function weekView(solved, today) {
  const day = parseKey(today).getDay();
  const monday = addDays(today, -((day + 6) % 7));
  return Array.from({ length: 7 }, (_, i) => {
    const key = addDays(monday, i);
    const d = parseKey(key).getDay();
    let state = 'future';
    if (solved[key]) state = 'done';
    else if (key === today) state = 'today';
    else if (key < today) state = 'missed';
    return { key, label: SHORT[d], state };
  });
}
