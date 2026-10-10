// Spielstand im Browser (localStorage). Schlägt der Zugriff fehl, läuft das Spiel ohne Speichern weiter.
const KEY = 'katzen-sudoku-v1';
export const START_HINTS = 5;

const DEFAULTS = {
  unlocked: 1,
  best: {},
  current: null,
  tutorialSeen: false,
  hints: START_HINTS,
  daily: { solved: {}, cache: null },
  history: [],
  skins: { selected: 'smoking', seen: ['smoking'] },
  settings: {
    sound: true,
    lives: true,
    conflicts: true,
    borders: false,
    patterns: false,
    unlockAll: false,
  },
};

const clone = (o) => JSON.parse(JSON.stringify(o));

export function loadData() {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) {
      const d = JSON.parse(raw);
      const data = {
        ...clone(DEFAULTS),
        ...d,
        best: d.best || {},
        daily: { solved: {}, cache: null, ...(d.daily || {}) },
        history: Array.isArray(d.history) ? d.history : [],
        skins: { ...clone(DEFAULTS.skins), ...(d.skins || {}) },
        settings: { ...DEFAULTS.settings, ...(d.settings || {}) },
      };
      if (typeof data.hints !== 'number') data.hints = START_HINTS;
      // ältere Spielstände: laufendes Level hatte nur eine Nummer
      if (data.current && !data.current.key && data.current.id) data.current.key = `L${data.current.id}`;
      return data;
    }
  } catch {
    // kein Speicher verfügbar
  }
  return clone(DEFAULTS);
}

export function saveData(data) {
  try {
    localStorage.setItem(KEY, JSON.stringify(data));
  } catch {
    // kein Speicher verfügbar
  }
}

export function resetData() {
  try {
    localStorage.removeItem(KEY);
  } catch {
    // kein Speicher verfügbar
  }
  return clone(DEFAULTS);
}
