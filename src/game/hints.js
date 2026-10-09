// Übersetzt Löserschritte in verständliche Hinweise für den aktuellen Spielstand.
import {
  newState, placeCat, findNextStep, UNIT_ROW, UNIT_COL, UNIT_REGION,
} from '../logic/solver.js';
import { PALETTE } from '../logic/palette.js';
import { MARK_CAT, MARK_X, MARK_AUTO_X } from './marks.js';

/** Name einer Einheit, optional mit Artikel ('die' = Nom./Akk., 'der' = Gen./Dat.). */
export function unitName(geo, colors, u, article = '') {
  const unit = geo.units[u];
  const a = article ? `${article} ` : '';
  if (unit.type === UNIT_ROW) return `${a}Zeile ${unit.index + 1}`;
  if (unit.type === UNIT_COL) return `${a}Spalte ${unit.index + 1}`;
  return `${a}Farbe ${PALETTE[colors[unit.index]].name}`;
}

const listJoin = (arr) => (arr.length <= 1 ? arr.join('') : `${arr.slice(0, -1).join(', ')} und ${arr[arr.length - 1]}`);

function plural(type, k) {
  if (type === UNIT_ROW) return k === 1 ? 'Zeile' : 'Zeilen';
  if (type === UNIT_COL) return k === 1 ? 'Spalte' : 'Spalten';
  return k === 1 ? 'Farbe' : 'Farben';
}

function shortName(geo, colors, u) {
  const unit = geo.units[u];
  if (unit.type === UNIT_REGION) return PALETTE[colors[unit.index]].name;
  return String(unit.index + 1);
}

/**
 * Liefert einen Hinweis:
 * { text, focus: [Zellen], target?, eliminate?: [Zellen], fix?: [Zellen], action: {type, cells} }
 */
export function computeHint(geo, level, marks) {
  const { colors } = level;
  const n = geo.n;
  const solutionCells = new Set(level.solution.map((c, r) => r * n + c));

  // 1. Fehler im Spielstand
  const wrongCats = [];
  const wrongX = [];
  marks.forEach((m, i) => {
    if (m === MARK_CAT && !solutionCells.has(i)) wrongCats.push(i);
    if ((m === MARK_X || m === MARK_AUTO_X) && solutionCells.has(i)) wrongX.push(i);
  });
  if (wrongCats.length) {
    return {
      text: 'Diese Katze sitzt leider nicht richtig. Nimm sie wieder weg und schau dir die Regeln für dieses Feld noch einmal an.',
      focus: [wrongCats[0]],
      eliminate: [wrongCats[0]],
      action: { type: 'clear', cells: [wrongCats[0]] },
    };
  }
  if (wrongX.length) {
    return {
      text: 'Hier steht ein X, das nicht stimmt – auf diesem Feld ist eine Katze durchaus möglich.',
      focus: [wrongX[0]],
      action: { type: 'clear', cells: [wrongX[0]] },
    };
  }

  // 2. Solver-Zustand aus dem Spielstand ableiten
  const st = newState(geo);
  marks.forEach((m, i) => { if (m === MARK_CAT) placeCat(geo, st, i); });
  const blocked = [];
  for (let i = 0; i < geo.size; i++) {
    if (marks[i] === MARK_CAT) continue;
    if (!st.cand[i]) {
      if (marks[i] !== MARK_X && marks[i] !== MARK_AUTO_X) blocked.push(i);
    } else if (marks[i] === MARK_X || marks[i] === MARK_AUTO_X) {
      st.cand[i] = 0;
    }
  }

  const step = findNextStep(geo, st);
  if (!step) {
    return { text: 'Alles erledigt – hier gibt es nichts mehr zu tun.', focus: [], action: null };
  }

  // Einzelfeld hat Vorrang: Katze setzen
  if (step.tech === 1) {
    const u = step.unit;
    return {
      text: `In ${unitName(geo, colors, u, 'der')} ist nur noch ein Feld möglich – hier muss die Katze hin.`,
      focus: geo.units[u].cells,
      target: step.place,
      action: { type: 'cat', cells: [step.place] },
    };
  }

  // Noch nicht markierte, aber von Katzen blockierte Felder
  if (blocked.length) {
    return {
      text: 'Jede Katze blockiert ihre Zeile, ihre Spalte, ihre Farbe und alle acht Nachbarfelder. Markiere diese Felder mit einem X (oder lass die Maus das erledigen).',
      focus: blocked,
      eliminate: blocked,
      action: { type: 'x', cells: blocked },
    };
  }

  const elim = step.eliminate;
  let text = '';
  let focus = [];
  if (step.kind === 'confine') {
    const u = step.unit;
    const v = step.target;
    text = `Alle freien Felder ${unitName(geo, colors, u, 'der')} liegen in ${unitName(geo, colors, v, 'der')}. `
      + `Die Katze für ${unitName(geo, colors, v, 'die')} muss also genau dort sitzen – die übrigen Felder ${unitName(geo, colors, v, 'der')} fallen weg.`;
    focus = geo.units[u].cells.filter((c) => st.cand[c]);
  } else if (step.kind === 'blockade') {
    const u = step.unit;
    text = `Eine Katze auf dem markierten Feld würde alle freien Felder ${unitName(geo, colors, u, 'der')} blockieren. Also kann dort keine Katze sitzen.`;
    focus = geo.units[u].cells.filter((c) => st.cand[c]);
  } else if (step.kind === 'subset') {
    const ta = geo.units[step.units[0]].type;
    const tb = geo.units[step.targets[0]].type;
    const a = step.units.map((u) => shortName(geo, colors, u));
    const b = step.targets.map((u) => shortName(geo, colors, u)).sort((x, y) => (Number(x) || 0) - (Number(y) || 0));
    text = `Die ${plural(ta, step.k)} ${listJoin(a)} haben ihre freien Felder nur in den ${plural(tb, step.k)} ${listJoin(b)}. `
      + `Damit sind diese ${step.k} ${plural(tb, step.k)} für sie reserviert – alle anderen Felder darin fallen weg.`;
    focus = step.units.flatMap((u) => geo.units[u].cells.filter((c) => st.cand[c]));
  } else if (step.kind === 'contradiction') {
    text = `Angenommen, auf dem markierten Feld säße eine Katze: Dann bliebe für ${unitName(geo, colors, step.unit, 'die')} am Ende kein freies Feld mehr übrig. Also kann dort keine Katze sitzen.`;
    focus = geo.units[step.unit].cells;
  }
  return { text, focus, eliminate: elim, action: { type: 'x', cells: elim } };
}
