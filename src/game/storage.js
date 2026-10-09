// Spielstand im Browser (localStorage). Schlägt der Zugriff fehl, läuft das Spiel ohne Speichern weiter.
const KEY = 'katzen-sudoku-v1';

const DEFAULTS = {
  unlocked: 1,
  best: {},
  current: null,
  tutorialSeen: false,
  settings: {
    sound: true,
    lives: true,
    conflicts: true,
    borders: false,
    unlockAll: false,
  },
};

export function loadData() {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) {
      const d = JSON.parse(raw);
      return {
        ...DEFAULTS,
        ...d,
        best: d.best || {},
        settings: { ...DEFAULTS.settings, ...(d.settings || {}) },
      };
    }
  } catch {
    // kein Speicher verfügbar
  }
  return JSON.parse(JSON.stringify(DEFAULTS));
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
  return JSON.parse(JSON.stringify(DEFAULTS));
}
