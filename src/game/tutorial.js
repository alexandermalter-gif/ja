// Interaktives Tutorial: ein festes 5×5-Rätsel, das die wichtigsten Techniken in sinnvoller
// Reihenfolge braucht (Einzelfeld → Eingesperrt → Blockade). Die Schritte selbst liefert die
// Hinweis-Engine, das Tutorial ergänzt nur Überschrift und Bedienhinweis.

export const TUTORIAL_LEVEL = {
  id: 'tutorial',
  n: 5,
  regions: '0001101111212312223322433',
  colors: [7, 4, 9, 5, 3],
  solution: [1, 3, 0, 4, 2],
  score: 0,
  tier: 0,
};

export const TUTORIAL_INTRO = {
  title: 'Willkommen bei Katzen-Sudoku!',
  text: 'Setze genau eine Katze in jede Zeile, jede Spalte und jede Farbe. Katzen dürfen sich nicht berühren – auch nicht schräg. Ich zeige dir in ein paar Schritten, wie man dabei vorgeht.',
};

export const TUTORIAL_AUTO_X = {
  title: 'Automatische X',
  text: 'Siehst du die hellgrauen X? Eine Katze blockiert ihre Zeile, ihre Spalte, ihre Farbe und alle acht Nachbarfelder. Diese X setzt das Spiel für dich.',
};

export function tutorialTitle(hint) {
  switch (hint.kind) {
    case 'single': return hint.unitType === 2 ? 'Nur ein Feld in dieser Farbe' : hint.unitType === 0 ? 'Nur ein Feld in dieser Zeile' : 'Nur ein Feld in dieser Spalte';
    case 'confine': return 'Eingesperrt';
    case 'blockade': return 'Blockade';
    case 'subset': return 'Gruppen';
    case 'contradiction': return 'Widerspruch';
    default: return 'Nächster Schritt';
  }
}

export function tutorialAction(hint) {
  if (hint.action?.type === 'cat') return 'Doppeltippe auf das leuchtende Feld, um die Katze zu setzen.';
  const k = hint.action?.cells?.length || 0;
  return k > 1
    ? 'Tippe die rot markierten Felder einmal an – so setzt du dort ein X.'
    : 'Tippe das rot markierte Feld einmal an – so setzt du dort ein X.';
}
