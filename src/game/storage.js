// Spielstand im Browser (localStorage).
// – Versionsnummer mit schrittweiser Migration, Typprüfung je Feld
// – beschädigte Daten werden gesichert statt überschrieben
// – beim Speichern werden Felder, die nur wachsen, mit dem gespeicherten Stand zusammengeführt
//   (zweiter Tab überschreibt so keinen Fortschritt)
// Schlägt der Zugriff fehl, läuft das Spiel ohne Speichern weiter.
export const KEY = 'katzen-sudoku-v1';
export const SCHEMA_VERSION = 2;
export const START_HINTS = 5;
export const MAX_LEVEL_SAVES = 5;

const DEFAULT_SETTINGS = {
  sound: true,
  vibration: true,
  lives: true,
  autoX: true,
  conflicts: true,
  borders: false,
  patterns: false,
  calm: false,
  unlockAll: false,
};

export function defaults() {
  return {
    v: SCHEMA_VERSION,
    unlocked: 1,
    best: {},
    saves: {},
    lastLevel: null,
    tutorialSeen: false,
    hints: START_HINTS,
    daily: { solved: {}, late: {}, cache: null },
    history: [],
    totals: { time: 0, solves: 0 },
    skins: { selected: 'smoking', seen: ['smoking'] },
    techniquesSeen: [],
    settings: { ...DEFAULT_SETTINGS },
  };
}

const isObj = (v) => v && typeof v === 'object' && !Array.isArray(v);
const num = (v, d, min = -Infinity, max = Infinity) => (typeof v === 'number' && Number.isFinite(v) ? Math.min(max, Math.max(min, v)) : d);
const bool = (v, d) => (typeof v === 'boolean' ? v : d);

/** Mitgespeichertes Rätsel (Archiv, Freies Spiel) prüfen. */
function cleanPuzzle(p) {
  if (!isObj(p) || !Number.isInteger(p.n) || p.n < 4 || p.n > 12) return null;
  const n = p.n;
  if (typeof p.regions !== 'string' || p.regions.length !== n * n) return null;
  if (!Array.isArray(p.solution) || p.solution.length !== n || !p.solution.every((c) => Number.isInteger(c) && c >= 0 && c < n)) return null;
  if (!Array.isArray(p.colors) || p.colors.length !== n || !p.colors.every((c) => Number.isInteger(c) && c >= 0)) return null;
  return {
    id: typeof p.id === 'string' || typeof p.id === 'number' ? p.id : null,
    n, regions: p.regions, colors: p.colors, solution: p.solution,
    score: num(p.score, 0, 0), maxTech: num(p.maxTech, 1, 0, 9), tier: num(p.tier, 0, 0, 4),
  };
}

function cleanSave(s) {
  if (!isObj(s) || !Array.isArray(s.marks)) return null;
  const marks = s.marks.map((m) => (m === 1 || m === 2 || m === 3 ? m : 0));
  const notes = Array.isArray(s.notes) && s.notes.length === marks.length ? s.notes.map((v) => (v ? 1 : 0)) : marks.map(() => 0);
  return {
    key: typeof s.key === 'string' ? s.key : null,
    sig: typeof s.sig === 'string' ? s.sig : null,
    marks,
    notes,
    fish: num(s.fish, 3, 0, 3),
    mistakes: num(s.mistakes, 0, 0),
    jokersLeft: num(s.jokersLeft, 1, 0, 1),
    helpersUsed: num(s.helpersUsed, 0, 0),
    capStars: num(s.capStars, 3, 1, 3),
    elapsed: num(s.elapsed, 0, 0),
    updatedAt: num(s.updatedAt, 0, 0),
    puzzle: cleanPuzzle(s.puzzle),
  };
}

/** Typprüfung und Migration eines geladenen Objekts. */
export function sanitize(raw) {
  const d = defaults();
  if (!isObj(raw)) return d;
  d.unlocked = num(raw.unlocked, 1, 1, 100);
  if (isObj(raw.best)) {
    for (const [id, b] of Object.entries(raw.best)) {
      if (!isObj(b)) continue;
      d.best[id] = { stars: num(b.stars, 1, 1, 3), points: num(b.points, 0, 0), time: b.time == null ? null : num(b.time, null, 0) };
    }
  }
  // v1: ein einzelner Spielstand in "current"
  if (isObj(raw.current)) {
    const c = cleanSave({ ...raw.current, key: raw.current.key || (raw.current.id ? `L${raw.current.id}` : null) });
    if (c && c.key) d.saves[c.key] = c;
    if (c && c.key && c.key.startsWith('L')) d.lastLevel = Number(c.key.slice(1));
  }
  if (isObj(raw.saves)) {
    for (const [k, s] of Object.entries(raw.saves)) {
      const c = cleanSave({ ...s, key: k });
      if (c) d.saves[k] = c;
    }
  }
  if (typeof raw.lastLevel === 'number') d.lastLevel = num(raw.lastLevel, null, 1, 100);
  d.tutorialSeen = bool(raw.tutorialSeen, false);
  d.hints = num(raw.hints, START_HINTS, 0, 999);
  if (isObj(raw.daily)) {
    for (const part of ['solved', 'late']) {
      if (!isObj(raw.daily[part])) continue;
      for (const [k, v] of Object.entries(raw.daily[part])) {
        if (/^\d{4}-\d{2}-\d{2}$/.test(k)) d.daily[part][k] = isObj(v) ? { time: num(v.time, 0, 0), stars: num(v.stars, 1, 1, 3), points: num(v.points, 0, 0) } : { time: 0, stars: 1, points: 0 };
      }
    }
    if (isObj(raw.daily.cache)) d.daily.cache = raw.daily.cache;
  }
  if (Array.isArray(raw.history)) d.history = raw.history.filter((h) => isObj(h) && typeof h.key === 'string' && Number.isFinite(h.time)).slice(-60);
  if (isObj(raw.totals)) d.totals = { time: num(raw.totals.time, 0, 0), solves: num(raw.totals.solves, 0, 0) };
  else d.totals = { time: d.history.reduce((a, h) => a + h.time, 0), solves: d.history.length };
  if (isObj(raw.skins)) {
    d.skins.selected = typeof raw.skins.selected === 'string' ? raw.skins.selected : 'smoking';
    d.skins.seen = Array.isArray(raw.skins.seen) ? [...new Set(['smoking', ...raw.skins.seen.filter((s) => typeof s === 'string')])] : ['smoking'];
  }
  if (Array.isArray(raw.techniquesSeen)) d.techniquesSeen = raw.techniquesSeen.filter((t) => typeof t === 'number');
  if (isObj(raw.settings)) {
    for (const k of Object.keys(DEFAULT_SETTINGS)) d.settings[k] = bool(raw.settings[k], DEFAULT_SETTINGS[k]);
  }
  d.v = SCHEMA_VERSION;
  return d;
}

function readRaw() {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

/** Prüft, ob überhaupt gespeichert werden kann. */
export function storageWorks() {
  try {
    const k = `${KEY}-probe`;
    localStorage.setItem(k, '1');
    localStorage.removeItem(k);
    return true;
  } catch {
    return false;
  }
}

/** Lädt den Spielstand. recovered = true, wenn beschädigte Daten gesichert wurden. */
export function loadData() {
  const raw = readRaw();
  if (!raw) return { data: defaults(), recovered: false };
  try {
    return { data: sanitize(JSON.parse(raw)), recovered: false };
  } catch {
    try { localStorage.setItem(`${KEY}-defekt-${Date.now()}`, raw); } catch { /* egal */ }
    return { data: defaults(), recovered: true };
  }
}

/** Fügt den gespeicherten Stand eines anderen Tabs mit dem eigenen zusammen (nur wachsende Felder). */
export function mergeData(mine, stored) {
  const out = { ...mine };
  out.unlocked = Math.max(mine.unlocked, stored.unlocked);
  out.best = { ...stored.best };
  for (const [id, b] of Object.entries(mine.best)) {
    const o = out.best[id];
    out.best[id] = o
      ? { stars: Math.max(o.stars, b.stars), points: Math.max(o.points, b.points), time: [o.time, b.time].filter((t) => t != null).reduce((a, t) => Math.min(a, t), Infinity) }
      : b;
    if (out.best[id].time === Infinity) out.best[id].time = null;
  }
  out.daily = { ...mine.daily, solved: { ...stored.daily.solved, ...mine.daily.solved }, late: { ...stored.daily.late, ...mine.daily.late } };
  out.skins = { ...mine.skins, seen: [...new Set([...stored.skins.seen, ...mine.skins.seen])] };
  out.techniquesSeen = [...new Set([...stored.techniquesSeen, ...mine.techniquesSeen])];
  const hk = (h) => `${h.key}|${Math.round(h.time * 10)}|${h.day}`;
  const hist = new Map();
  for (const h of [...stored.history, ...mine.history]) hist.set(hk(h), h);
  out.history = [...hist.values()].slice(-60);
  out.totals = { time: Math.max(mine.totals.time, stored.totals.time), solves: Math.max(mine.totals.solves, stored.totals.solves) };
  out.saves = { ...stored.saves };
  for (const [k, s] of Object.entries(mine.saves)) {
    if (!out.saves[k] || (s.updatedAt || 0) >= (out.saves[k].updatedAt || 0)) out.saves[k] = s;
  }
  for (const k of mine.deletedSaves || []) delete out.saves[k];
  out.tutorialSeen = mine.tutorialSeen || stored.tutorialSeen;
  return out;
}

/** Hält die Zahl der gespeicherten Rätsel klein: höchstens 5 Level, das heutige Tagesrätsel und ein Archivrätsel. */
export function pruneSaves(data, todayKey) {
  const levelKeys = Object.keys(data.saves).filter((k) => k.startsWith('L'))
    .sort((a, b) => (data.saves[b].updatedAt || 0) - (data.saves[a].updatedAt || 0));
  for (const k of levelKeys.slice(MAX_LEVEL_SAVES)) delete data.saves[k];
  // Tagesrätsel: das heutige und höchstens ein nachgeholtes aus dem Archiv
  const late = Object.keys(data.saves).filter((k) => k.startsWith('A'))
    .sort((a, b) => (data.saves[b].updatedAt || 0) - (data.saves[a].updatedAt || 0));
  for (const k of late.slice(1)) delete data.saves[k];
  for (const k of Object.keys(data.saves)) if (k.startsWith('D') && k !== `D${todayKey}`) delete data.saves[k];
}

/** Speichert; gibt false zurück, wenn es nicht geklappt hat. */
export function saveData(data) {
  try {
    const raw = readRaw();
    let toWrite = data;
    if (raw) {
      try {
        toWrite = mergeData(data, sanitize(JSON.parse(raw)));
      } catch {
        toWrite = data;
      }
    }
    const { deletedSaves, ...clean } = toWrite;
    localStorage.setItem(KEY, JSON.stringify(clean));
    data.deletedSaves = [];
    return true;
  } catch {
    return false;
  }
}

/** Fortschritt löschen – Einstellungen bleiben erhalten. */
export function resetProgress(settings) {
  const d = defaults();
  d.settings = { ...d.settings, ...settings };
  d.tutorialSeen = true;
  try {
    localStorage.setItem(KEY, JSON.stringify(d));
  } catch {
    // kein Speicher verfügbar
  }
  return d;
}

/** Spielstand als kopierbarer Code (Base64, UTF-8-sicher). */
export function exportCode(data) {
  const { daily, deletedSaves, ...rest } = data;
  const json = JSON.stringify({ ...rest, daily: { solved: daily.solved, late: daily.late } });
  const bytes = new TextEncoder().encode(json);
  let bin = '';
  bytes.forEach((b) => { bin += String.fromCharCode(b); });
  return `KS1:${btoa(bin)}`;
}

/** Liest einen Code; wirft bei ungültigem Inhalt. */
export function importCode(code) {
  const m = String(code).trim().match(/^KS1:([A-Za-z0-9+/=\s]+)$/);
  if (!m) throw new Error('Das ist kein Katzen-Sudoku-Code.');
  const bin = atob(m[1].replace(/\s+/g, ''));
  const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
  const obj = JSON.parse(new TextDecoder().decode(bytes));
  return sanitize(obj);
}
