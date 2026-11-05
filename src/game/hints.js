// Übersetzt Löserschritte in verständliche Hinweise für den aktuellen Spielstand.
import {
  newState, placeCat, findNextStep, UNIT_ROW, UNIT_COL, UNIT_REGION,
} from '../logic/solver.js';
import { PALETTE } from '../logic/palette.js';
import { MARK_CAT, MARK_X, MARK_AUTO_X } from './marks.js';

/** Farbe als Adjektiv („die himmelblaue Katze“). */
const ADJ = {
  Lila: 'lilafarbene',
  Hellgrün: 'hellgrüne',
  Braun: 'braune',
  Orange: 'orangefarbene',
  Gelb: 'gelbe',
  Dunkelblau: 'dunkelblaue',
  Rosa: 'rosafarbene',
  Rot: 'rote',
  Türkis: 'türkisfarbene',
  Himmelblau: 'himmelblaue',
};

const NUM = ['null', 'eine', 'zwei', 'drei', 'vier', 'fünf', 'sechs', 'sieben', 'acht', 'neun', 'zehn', 'elf', 'zwölf'];
/** Zahlen bis zwölf als Wort. */
export const numWord = (k) => NUM[k] || String(k);

const listJoin = (arr) => (arr.length <= 1 ? arr.join('') : `${arr.slice(0, -1).join(', ')} und ${arr[arr.length - 1]}`);

const colorName = (colors, idx) => PALETTE[colors[idx]].name;

/** Name einer Einheit ohne Artikel: „Zeile 3“, „Spalte 1“, „Himmelblau“. */
export function unitName(geo, colors, u) {
  const unit = geo.units[u];
  if (unit.type === UNIT_ROW) return `Zeile ${unit.index + 1}`;
  if (unit.type === UNIT_COL) return `Spalte ${unit.index + 1}`;
  return colorName(colors, unit.index);
}

/** Mehrere Einheiten gleicher Art: „Zeilen 2 und 5“, „Rot und Rosa“. */
function unitList(geo, colors, list) {
  const type = geo.units[list[0]].type;
  const sorted = [...list].sort((a, b) => a - b);
  if (type === UNIT_REGION) return listJoin(sorted.map((u) => colorName(colors, geo.units[u].index)));
  const word = type === UNIT_ROW ? (list.length === 1 ? 'Zeile' : 'Zeilen') : (list.length === 1 ? 'Spalte' : 'Spalten');
  return `${word} ${listJoin(sorted.map((u) => String(geo.units[u].index + 1)))}`;
}

function typeWord(type, k) {
  if (type === UNIT_ROW) return k === 1 ? 'Zeile' : 'Zeilen';
  if (type === UNIT_COL) return k === 1 ? 'Spalte' : 'Spalten';
  return k === 1 ? 'Farbe' : 'Farben';
}

/**
 * Liefert einen Hinweis:
 * { kind, text, focus: [Zellen], target?, eliminate?: [Zellen], ghost?: [Zellen], action: {type, cells} }
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
      kind: 'wrongCat',
      text: 'Diese Katze gehört nicht hierher – „Ausführen“ nimmt sie weg.',
      focus: [wrongCats[0]],
      eliminate: [wrongCats[0]],
      action: { type: 'clear', cells: [wrongCats[0]] },
    };
  }
  if (wrongX.length) {
    return {
      kind: 'wrongX',
      text: 'Dieses X ist zu voreilig – hier kann noch eine Katze sitzen.',
      focus: [wrongX[0]],
      action: { type: 'clear', cells: [wrongX[0]] },
    };
  }

  // 2. Löser-Zustand aus dem Spielstand ableiten
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
    return { kind: 'done', text: 'Alles erledigt – hier gibt es nichts mehr zu tun.', focus: [], action: null };
  }

  // Einzelfeld hat Vorrang: Katze setzen
  if (step.tech === 1) {
    const u = step.unit;
    const unit = geo.units[u];
    let text;
    if (unit.type === UNIT_REGION && unit.cells.length === 1) text = `Die Farbe ${unitName(geo, colors, u)} hat nur ein einziges Feld – hier muss die Katze hin.`;
    else text = `In ${unitName(geo, colors, u)} ist nur noch ein Feld frei – hier muss die Katze hin.`;
    return {
      kind: 'single',
      unitType: unit.type,
      text,
      focus: unit.cells,
      target: step.place,
      action: { type: 'cat', cells: [step.place] },
    };
  }

  // Noch nicht markierte, aber von Katzen blockierte Felder
  if (blocked.length) {
    return {
      kind: 'blocked',
      text: 'Jede Katze blockiert ihre Zeile, ihre Spalte, ihre Farbe und alle acht Nachbarfelder. Markiere diese Felder mit einem X.',
      focus: blocked,
      eliminate: blocked,
      action: { type: 'x', cells: blocked },
    };
  }

  let elim = step.eliminate;
  let text = '';
  let focus = [];
  let ghost = [];
  const free = (u) => geo.units[u].cells.filter((c) => st.cand[c]);

  if (step.kind === 'confine') {
    const u = step.unit;
    const v = step.target;
    const U = unitName(geo, colors, u);
    const V = unitName(geo, colors, v);
    if (geo.units[u].type === UNIT_REGION) {
      // Farbe liegt ganz in einer Zeile/Spalte
      text = `Alle freien Felder von ${U} liegen in ${V}. Die Katze von ${V} muss also die ${ADJ[U] || U} sein – die anderen Felder von ${V} fallen weg.`;
    } else {
      // Zeile/Spalte hat nur noch Felder einer Farbe
      text = `${U} hat nur noch freie Felder in ${V}. Die ${ADJ[V] || V} Katze sitzt also in ${U} – die übrigen Felder von ${V} fallen weg.`;
    }
    focus = free(u);
  } else if (step.kind === 'blockade') {
    // Alle Felder sammeln, die dieselbe Einheit komplett blockieren würden
    const u = step.unit;
    const cands = free(u);
    const mask = geo.attackMask;
    elim = [];
    for (let c = 0; c < geo.size; c++) {
      if (!st.cand[c] || geo.cellUnits[c].includes(u)) continue;
      if (cands.every((x) => mask[c][x])) elim.push(c);
    }
    const U = unitName(geo, colors, u);
    text = elim.length > 1
      ? `Jedes der ${numWord(elim.length)} rot markierten Felder würde alle freien Felder von ${U} blockieren. Dort kann also keine Katze sitzen.`
      : `Eine Katze auf dem rot markierten Feld würde alle freien Felder von ${U} blockieren. Dort kann also keine Katze sitzen.`;
    focus = cands;
  } else if (step.kind === 'subset') {
    const ta = geo.units[step.units[0]].type;
    const k = step.k;
    const A = unitList(geo, colors, step.units);
    const B = unitList(geo, colors, step.targets);
    const ofA = ta === UNIT_REGION ? `von ${A}` : `der ${A}`;
    const inB = geo.units[step.targets[0]].type === UNIT_REGION ? B : `den ${B}`;
    const ofB = geo.units[step.targets[0]].type === UNIT_REGION ? `von ${B}` : `der ${B}`;
    text = `Alle freien Felder ${ofA} liegen in ${inB}. Diese ${numWord(k)} ${typeWord(ta, k)} brauchen also ${k === 2 ? 'beide Katzen' : `alle ${numWord(k)} Katzen`} ${ofB} – die übrigen Felder ${ofB} fallen weg.`;
    focus = step.units.flatMap(free);
  } else if (step.kind === 'block2x2') {
    const t = geo.units[step.units[0]].type;
    const A = unitList(geo, colors, step.units);
    const ofA = t === UNIT_REGION ? `von ${A}` : `der ${A}`;
    text = `Alle freien Felder ${ofA} liegen in den zwei hervorgehobenen Bereichen. In jeden passt höchstens eine Katze – in einem 2×2-Block berühren sich zwei Katzen immer. Also sitzt in jedem Bereich genau eine der beiden Katzen ${ofA}, die übrigen Felder darin fallen weg.`;
    focus = [...new Set(step.covers.flat())];
  } else if (step.kind === 'contradiction') {
    const E = unitName(geo, colors, step.unit);
    ghost = (step.forced || []).filter((c) => c !== step.cell);
    if (ghost.length === 1) {
      text = `Angenommen, auf dem rot markierten Feld säße eine Katze. Dann müsste eine weitere Katze auf das blasse Feld – und danach bliebe für ${E} kein freies Feld mehr. Also kann auf dem rot markierten Feld keine Katze sitzen.`;
    } else if (ghost.length > 1) {
      text = `Angenommen, auf dem rot markierten Feld säße eine Katze. Dann müssten nacheinander die ${numWord(ghost.length)} blassen Katzen gesetzt werden – und danach bliebe für ${E} kein freies Feld mehr. Also kann auf dem rot markierten Feld keine Katze sitzen.`;
    } else {
      text = `Angenommen, auf dem rot markierten Feld säße eine Katze: Dann bliebe für ${E} kein freies Feld mehr. Also kann dort keine Katze sitzen.`;
    }
    focus = geo.units[step.unit].cells;
  }
  return { kind: step.kind, text, focus, eliminate: elim, ghost, action: { type: 'x', cells: elim } };
}
