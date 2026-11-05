// Tagesrätsel: für alle Spieler am selben Tag gleich, montags leicht, zum Wochenende hin
// schwerer. Die Rätsel kommen aus der vorberechneten Tabelle (dailies.js); nur Tage außerhalb
// davon werden live erzeugt. Dazu Serie (Tage in Folge) und Wochenansicht.
import { DAILY_TABLE_START, DAILY_TABLE } from '../logic/dailies.js';
import { DAILY_TARGETS, generateDailyV3, decodeDaily } from '../logic/dailyGen.js';
import { tierFor } from './scoring.js';

/** Version der live erzeugten Tagesrätsel (für den Zwischenspeicher). */
export const DAILY_CACHE_VERSION = 3;

export const WEEKDAYS = ['Sonntag', 'Montag', 'Dienstag', 'Mittwoch', 'Donnerstag', 'Freitag', 'Samstag'];
const SHORT = ['So', 'Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa'];

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

const daysBetween = (a, b) => Math.round((parseKey(b) - parseKey(a)) / 86400000);

/** Rätsel aus der Tabelle oder null. */
export function tableDaily(key) {
  const i = daysBetween(DAILY_TABLE_START, key);
  if (i < 0 || i >= DAILY_TABLE.length) return null;
  const level = decodeDaily(key, DAILY_TABLE[i]);
  level.tier = tierFor(level);
  return level;
}

/** Größe, Stufe und Wochentag eines Tages (für die Tageskarte). */
export function planFor(key) {
  const day = parseKey(key).getDay();
  const t = tableDaily(key);
  const target = DAILY_TARGETS[day];
  const n = t ? t.n : target.n;
  return { n, tier: t ? t.tier : tierFor({ n, score: target.target, maxTech: target.minTech }), weekday: WEEKDAYS[day] };
}

export function formatDate(key) {
  return parseKey(key).toLocaleDateString('de-DE', { weekday: 'long', day: 'numeric', month: 'long' });
}

/**
 * Rätsel eines Tages: aus der Tabelle, sonst live erzeugt. `live` ersetzt den Generator
 * (im Browser: Web Worker); `pause` lässt den Browser zwischendurch zeichnen.
 */
export async function generateDaily(key, pause = null, live = null) {
  const t = tableDaily(key);
  if (t) return t;
  const level = live ? await live(key) : await generateDailyV3(key, pause);
  level.tier = tierFor(level);
  return level;
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
