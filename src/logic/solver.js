// Regeln, exakter Löser und logischer Löser (für Hinweise und Schwierigkeitsbewertung).
//
// Spielfeld: n × n Felder, n Farbregionen. Gesucht sind n Katzen, sodass
//   – jede Zeile, jede Spalte und jede Farbe genau eine Katze enthält,
//   – sich keine zwei Katzen berühren (auch nicht diagonal).

export const UNIT_ROW = 0;
export const UNIT_COL = 1;
export const UNIT_REGION = 2;

/** Baut alle statischen Nachschlagetabellen für ein Spielfeld. */
export function makeGeometry(n, regions) {
  const size = n * n;
  const units = [];
  for (let r = 0; r < n; r++) {
    const cells = [];
    for (let c = 0; c < n; c++) cells.push(r * n + c);
    units.push({ type: UNIT_ROW, index: r, cells });
  }
  for (let c = 0; c < n; c++) {
    const cells = [];
    for (let r = 0; r < n; r++) cells.push(r * n + c);
    units.push({ type: UNIT_COL, index: c, cells });
  }
  for (let k = 0; k < n; k++) units.push({ type: UNIT_REGION, index: k, cells: [] });
  // Region -1 = (noch) keiner Farbe zugeordnet; nur für den Generator relevant.
  for (let i = 0; i < size; i++) if (regions[i] >= 0) units[2 * n + regions[i]].cells.push(i);

  const cellUnits = [];
  const neighbors = [];
  const attacks = [];
  for (let i = 0; i < size; i++) {
    const r = Math.floor(i / n);
    const c = i % n;
    cellUnits.push(regions[i] >= 0 ? [r, n + c, 2 * n + regions[i]] : [r, n + c]);
    const nb = [];
    for (let dr = -1; dr <= 1; dr++) {
      for (let dc = -1; dc <= 1; dc++) {
        if (!dr && !dc) continue;
        const rr = r + dr;
        const cc = c + dc;
        if (rr >= 0 && rr < n && cc >= 0 && cc < n) nb.push(rr * n + cc);
      }
    }
    neighbors.push(nb);
  }
  for (let i = 0; i < size; i++) {
    const set = new Set(neighbors[i]);
    for (const u of cellUnits[i]) for (const j of units[u].cells) set.add(j);
    set.delete(i);
    attacks.push([...set]);
  }
  const attackMask = attacks.map((list) => {
    const m = new Uint8Array(size);
    for (const j of list) m[j] = 1;
    return m;
  });
  return { n, size, regions: Array.from(regions), units, cellUnits, neighbors, attacks, attackMask };
}

export function newState(geo) {
  return {
    cand: new Uint8Array(geo.size).fill(1),
    cat: new Uint8Array(geo.size),
    done: new Uint8Array(geo.units.length),
  };
}

export function cloneState(st) {
  return { cand: st.cand.slice(), cat: st.cat.slice(), done: st.done.slice() };
}

/** Setzt eine Katze und streicht alle dadurch blockierten Felder. */
export function placeCat(geo, st, cell) {
  st.cat[cell] = 1;
  st.cand[cell] = 0;
  for (const a of geo.attacks[cell]) st.cand[a] = 0;
  for (const u of geo.cellUnits[cell]) st.done[u] = 1;
}

export function unitCandidates(geo, st, u) {
  const out = [];
  for (const c of geo.units[u].cells) if (st.cand[c]) out.push(c);
  return out;
}

/** Liefert die erste Einheit ohne Katze und ohne freie Felder (Widerspruch) oder -1. */
export function findEmptyUnit(geo, st) {
  for (let u = 0; u < geo.units.length; u++) {
    if (st.done[u]) continue;
    let any = false;
    for (const c of geo.units[u].cells) if (st.cand[c]) { any = true; break; }
    if (!any) return u;
  }
  return -1;
}

export function isSolved(geo, st) {
  for (let u = 0; u < geo.units.length; u++) if (!st.done[u]) return false;
  return true;
}

// ---------------------------------------------------------------------------
// Exakter Löser (Backtracking mit "kleinste Einheit zuerst")
// ---------------------------------------------------------------------------

/**
 * Zählt Lösungen bis `limit`. Gibt { count, solutions } zurück (Lösung = Liste der Katzenfelder).
 * Felder ohne Farbe (Region -1) sind für Katzen gesperrt.
 */
export function countSolutions(geo, limit = 2) {
  const solutions = [];
  let count = 0;
  const rec = (st) => {
    if (count >= limit) return;
    let best = -1;
    let bestCnt = Infinity;
    for (let u = 0; u < geo.units.length; u++) {
      if (st.done[u]) continue;
      let cnt = 0;
      for (const c of geo.units[u].cells) cnt += st.cand[c];
      if (cnt === 0) return;
      if (cnt < bestCnt) { bestCnt = cnt; best = u; }
    }
    if (best === -1) {
      count++;
      const sol = [];
      for (let i = 0; i < geo.size; i++) if (st.cat[i]) sol.push(i);
      solutions.push(sol);
      return;
    }
    for (const c of geo.units[best].cells) {
      if (!st.cand[c]) continue;
      const s2 = cloneState(st);
      placeCat(geo, s2, c);
      rec(s2);
      if (count >= limit) return;
    }
  };
  const start = newState(geo);
  for (let i = 0; i < geo.size; i++) if (geo.regions[i] < 0) start.cand[i] = 0;
  rec(start);
  return { count, solutions };
}

// ---------------------------------------------------------------------------
// Logischer Löser – Techniken in aufsteigender Schwierigkeit
// ---------------------------------------------------------------------------
//  1  Einzelfeld:   Eine Zeile/Spalte/Farbe hat nur noch ein freies Feld.
//  2  Eingesperrt:  Alle freien Felder einer Einheit liegen in einer anderen Einheit
//                   → die restlichen Felder dieser anderen Einheit sind frei von Katzen.
//  3  Blockade:     Eine Katze auf Feld X würde eine ganze Einheit blockieren → X streichen.
//  4  Gruppen:      k Einheiten teilen sich genau k andere Einheiten (k ≥ 2).
//  5  Widerspruch:  Annahme "Katze auf X" führt mit Techniken 1–3 zu einem Widerspruch.
//  6  Tiefer Widerspruch: wie 5, aber mit Techniken 1–4.

export const TECH_NAMES = {
  1: 'Einzelfeld',
  2: 'Eingesperrt',
  3: 'Blockade',
  4: 'Gruppen',
  5: 'Widerspruch',
  6: 'Tiefer Widerspruch',
};

function findSingle(geo, st) {
  // Farben zuerst – das ist für Spieler am anschaulichsten.
  const order = [];
  for (let u = 2 * geo.n; u < geo.units.length; u++) order.push(u);
  for (let u = 0; u < 2 * geo.n; u++) order.push(u);
  for (const u of order) {
    if (st.done[u]) continue;
    let only = -1;
    let cnt = 0;
    for (const c of geo.units[u].cells) {
      if (st.cand[c]) { cnt++; only = c; if (cnt > 1) break; }
    }
    if (cnt === 1) return { tech: 1, kind: 'single', unit: u, place: only, eliminate: [] };
  }
  return null;
}

function unitsOfType(geo, type) {
  const base = type * geo.n;
  const out = [];
  for (let i = 0; i < geo.n; i++) out.push(base + i);
  return out;
}

function findConfinement(geo, st) {
  for (let u = 0; u < geo.units.length; u++) {
    if (st.done[u]) continue;
    const cands = unitCandidates(geo, st, u);
    if (cands.length < 2) continue;
    const typeU = geo.units[u].type;
    for (let t = 0; t < 3; t++) {
      if (t === typeU) continue;
      const v = geo.cellUnits[cands[0]][t];
      let ok = true;
      for (const c of cands) if (geo.cellUnits[c][t] !== v) { ok = false; break; }
      if (!ok) continue;
      const elim = [];
      for (const c of geo.units[v].cells) {
        if (st.cand[c] && geo.cellUnits[c][typeU] !== u) elim.push(c);
      }
      if (elim.length) return { tech: 2, kind: 'confine', unit: u, target: v, eliminate: elim, k: 1 };
    }
  }
  return null;
}

function findBlockade(geo, st) {
  for (let c = 0; c < geo.size; c++) {
    if (!st.cand[c]) continue;
    const mask = geo.attackMask[c];
    for (let u = 0; u < geo.units.length; u++) {
      if (st.done[u]) continue;
      if (geo.cellUnits[c].includes(u)) continue;
      let any = false;
      let all = true;
      for (const x of geo.units[u].cells) {
        if (!st.cand[x]) continue;
        any = true;
        if (!mask[x]) { all = false; break; }
      }
      if (any && all) return { tech: 3, kind: 'blockade', cell: c, unit: u, eliminate: [c] };
    }
  }
  return null;
}

function* combinations(arr, k, start = 0, acc = []) {
  if (acc.length === k) { yield acc.slice(); return; }
  for (let i = start; i <= arr.length - (k - acc.length); i++) {
    acc.push(arr[i]);
    yield* combinations(arr, k, i + 1, acc);
    acc.pop();
  }
}

function findSubset(geo, st, maxK = geo.n) {
  const pairs = [
    [UNIT_REGION, UNIT_ROW], [UNIT_REGION, UNIT_COL],
    [UNIT_ROW, UNIT_REGION], [UNIT_COL, UNIT_REGION],
    [UNIT_ROW, UNIT_COL], [UNIT_COL, UNIT_ROW],
  ];
  const open = [[], [], []];
  for (let t = 0; t < 3; t++) open[t] = unitsOfType(geo, t).filter((u) => !st.done[u]);
  const candsOf = new Map();
  for (let u = 0; u < geo.units.length; u++) if (!st.done[u]) candsOf.set(u, unitCandidates(geo, st, u));

  for (let k = 2; k <= maxK; k++) {
    for (const [ta, tb] of pairs) {
      const A = open[ta];
      if (k >= A.length) continue;
      for (const combo of combinations(A, k)) {
        const touched = new Set();
        let tooMany = false;
        for (const u of combo) {
          for (const c of candsOf.get(u)) {
            touched.add(geo.cellUnits[c][tb]);
            if (touched.size > k) { tooMany = true; break; }
          }
          if (tooMany) break;
        }
        if (tooMany || touched.size !== k) continue;
        const inCombo = new Set(combo);
        const elim = [];
        for (const v of touched) {
          for (const c of geo.units[v].cells) {
            if (st.cand[c] && !inCombo.has(geo.cellUnits[c][ta])) elim.push(c);
          }
        }
        if (elim.length) {
          return { tech: 4, kind: 'subset', k, units: combo, targets: [...touched], eliminate: elim };
        }
      }
    }
  }
  return null;
}

function basicStep(geo, st, maxTech) {
  return findSingle(geo, st)
    || findConfinement(geo, st)
    || findBlockade(geo, st)
    || (maxTech >= 4 ? findSubset(geo, st) : null);
}

export function applyStep(geo, st, step) {
  if (step.place !== undefined) placeCat(geo, st, step.place);
  for (const c of step.eliminate) st.cand[c] = 0;
}

/** Nimmt eine Katze auf `cell` an und propagiert. Gibt bei Widerspruch die leere Einheit + Kettenlänge zurück. */
function tryAssume(geo, st, cell, maxTech) {
  const s2 = cloneState(st);
  placeCat(geo, s2, cell);
  let chain = 0;
  for (;;) {
    const empty = findEmptyUnit(geo, s2);
    if (empty >= 0) return { contradiction: true, unit: empty, chain };
    if (isSolved(geo, s2)) return { contradiction: false };
    const step = basicStep(geo, s2, maxTech);
    if (!step) return { contradiction: false };
    applyStep(geo, s2, step);
    chain++;
  }
}

function findContradiction(geo, st, deep) {
  let best = null;
  for (let c = 0; c < geo.size; c++) {
    if (!st.cand[c]) continue;
    const res = tryAssume(geo, st, c, deep ? 4 : 3);
    if (res.contradiction && (!best || res.chain < best.chain)) {
      best = { tech: deep ? 6 : 5, kind: 'contradiction', cell: c, unit: res.unit, chain: res.chain, eliminate: [c] };
      if (res.chain === 0) break;
    }
  }
  return best;
}

/** Nächster logischer Schritt (leichteste Technik zuerst) oder null. */
export function findNextStep(geo, st) {
  return basicStep(geo, st, 4)
    || findContradiction(geo, st, false)
    || findContradiction(geo, st, true);
}

const WEIGHTS = { 1: 1, 2: 3, 3: 6, 4: 12, 5: 22, 6: 40 };

/** Löst rein logisch. Liefert Statistik zur Schwierigkeit. */
export function gradePuzzle(geo) {
  const st = newState(geo);
  const counts = { 1: 0, 2: 0, 3: 0, 4: 0, 5: 0, 6: 0 };
  let score = 0;
  let maxTech = 0;
  let maxK = 0;
  let steps = 0;
  for (;;) {
    if (isSolved(geo, st)) break;
    if (findEmptyUnit(geo, st) >= 0) return { solved: false, counts, score, maxTech };
    const step = findNextStep(geo, st);
    if (!step) return { solved: false, counts, score, maxTech };
    applyStep(geo, st, step);
    steps++;
    counts[step.tech]++;
    maxTech = Math.max(maxTech, step.tech);
    let w = WEIGHTS[step.tech];
    if (step.tech === 4) { w += 6 * (step.k - 2); maxK = Math.max(maxK, step.k); }
    if (step.tech >= 5) w += 2 * step.chain;
    score += w;
  }
  return { solved: true, counts, score, maxTech, maxK, steps };
}
