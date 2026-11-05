// Wertung: Sterne, Punkte und Schwierigkeitsstufe.

export const TIERS = [
  { name: 'Leicht', color: '#6fae5f' },
  { name: 'Mittel', color: '#d99a2b' },
  { name: 'Schwer', color: '#c0605a' },
  { name: 'Sehr schwer', color: '#a7405d' },
  { name: 'Experte', color: '#7a3d93' },
];

const STAR_MULT = [0, 1, 1.25, 1.6];

/** 3 Sterne ohne Fehler und Helfer, 2 bei höchstens zwei Patzern, sonst 1; capStars begrenzt (z. B. nach „Weiterspielen“). */
export function starsFor(mistakes, helpers, capStars = 3) {
  const slips = mistakes + helpers;
  const s = slips === 0 ? 3 : slips <= 2 ? 2 : 1;
  return Math.min(s, capStars);
}

export function pointsFor(kind, level, stars) {
  const base = kind === 'daily' ? 120 + Math.round(level.score * 1.5) : 60 + (level.id || 0) * 6 + Math.round(level.score * 1.5);
  return Math.round(base * STAR_MULT[stars]);
}

// Score-Grenzen je Brettgröße (Bewertung des logischen Lösers; größere Bretter brauchen mehr Schritte).
export const BOUNDS = {
  5: [12, 30, 60, 100],
  6: [15, 35, 70, 120],
  7: [35, 60, 100, 160],
  8: [45, 75, 120, 200],
  9: [70, 105, 160, 260],
  10: [90, 125, 180, 300],
};

/** Stufe 0–4 aus Score, Brettgröße und schwerster Technik (statt aus der Levelnummer). */
export function tierFor(level) {
  const b = BOUNDS[level.n] || BOUNDS[10];
  let t = 0;
  while (t < 4 && level.score >= b[t]) t++;
  if (level.maxTech >= 5 && level.score >= b[2]) t = Math.max(t, 3);
  if (level.maxTech >= 6) t = 4;
  return t;
}
