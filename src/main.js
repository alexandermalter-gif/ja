// Katzen-Sudoku – Spielsteuerung und Oberfläche.
import { LEVELS } from './logic/levels.js';
import { PALETTE } from './logic/palette.js';
import { makeGeometry } from './logic/solver.js';
import { Board3D, CAT_LAND_MS, CAT_ANIM_MS } from './game/board3d.js';
import { MARK_EMPTY, MARK_X, MARK_CAT, MARK_AUTO_X } from './game/marks.js';
import { computeHint } from './game/hints.js';
import { loadData, saveData, resetData } from './game/storage.js';
import { sfx, setSoundEnabled } from './game/audio.js';
import * as I from './game/icons.js';

const TIERS = [
  { name: 'Leicht', color: '#6fae5f' },
  { name: 'Mittel', color: '#d99a2b' },
  { name: 'Schwer', color: '#c0605a' },
  { name: 'Sehr schwer', color: '#a7405d' },
  { name: 'Experte', color: '#7a3d93' },
];
const FISH = 3;
const JOKERS_PER_LEVEL = 1;
const HINTS_PER_LEVEL = 3;
const STAR_MULT = [0, 1, 1.25, 1.6];
const DOUBLE_TAP_MS = 380;
const WAVE_MS = 45; // Verzögerung der X-Welle pro Feld Abstand

const $ = (sel) => document.querySelector(sel);
const isX = (m) => m === MARK_X || m === MARK_AUTO_X;

let data = loadData();
let board = null;
let game = null;
let timerHandle = 0;
let lastTap = { cell: -1, time: 0, before: MARK_EMPTY };

setSoundEnabled(data.settings.sound);

// ---------------------------------------------------------------------------
// Hilfsfunktionen
// ---------------------------------------------------------------------------

function parseLevel(level) {
  const regions = [...level.regions].map((ch) => parseInt(ch, 36));
  return { ...level, regionArr: regions };
}

function totalPoints() {
  return Object.values(data.best).reduce((a, b) => a + (b.points || 0), 0);
}

function totalStars() {
  return Object.values(data.best).reduce((a, b) => a + (b.stars || 0), 0);
}

function isUnlocked(id) {
  return data.settings.unlockAll || id <= data.unlocked;
}

function persist() {
  saveData(data);
}

function formatTime(sec) {
  const s = Math.floor(sec);
  const m = Math.floor(s / 60);
  return `${String(m).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

function toast(msg) {
  const el = $('#toast');
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toast.t);
  toast.t = setTimeout(() => el.classList.remove('show'), 2400);
}

function vibrate(ms) {
  try { navigator.vibrate?.(ms); } catch { /* egal */ }
}

/** CSS-Animation neu starten (Klasse entfernen, Reflow, wieder setzen). */
function retrigger(el, cls) {
  if (!el) return;
  el.classList.remove(cls);
  void el.offsetWidth;
  el.classList.add(cls);
}

/** Zahl in einem Element weich hochzählen. */
function animateNumber(el, from, to, dur = 900, onStep) {
  const t0 = performance.now();
  const fmt = (v) => Math.round(v).toLocaleString('de-DE');
  let last = -1;
  const stepFn = (now) => {
    const p = Math.min(1, (now - t0) / dur);
    const e = 1 - Math.pow(1 - p, 3);
    const v = from + (to - from) * e;
    el.textContent = fmt(v);
    const shown = Math.round(v);
    if (shown !== last) { last = shown; onStep?.(); }
    if (p < 1) requestAnimationFrame(stepFn);
  };
  requestAnimationFrame(stepFn);
}

/** Schrift so weit verkleinern, dass der Text in seine Spalte passt. */
function fitText(el, maxPx, minPx = 12) {
  if (!el) return;
  let size = maxPx;
  el.style.fontSize = `${size}px`;
  while (el.scrollWidth > el.clientWidth + 0.5 && size > minPx) {
    size -= 1;
    el.style.fontSize = `${size}px`;
  }
}

function fitHud() {
  const base = Math.min(26, Math.max(18, window.innerWidth * 0.058));
  document.querySelectorAll('.hud .value').forEach((el) => fitText(el, base, 12));
}
window.addEventListener('resize', () => fitHud());

// ---------------------------------------------------------------------------
// Modale Dialoge
// ---------------------------------------------------------------------------

function openModal({ title, body, buttons = [], dismissible = true, className = '' }) {
  closeModal();
  const root = $('#modal-root');
  const wrap = document.createElement('div');
  wrap.className = 'modal-backdrop';
  wrap.innerHTML = `
    <div class="modal ${className}" role="dialog" aria-modal="true" aria-label="${title}">
      <h2>${title}</h2>
      <div class="modal-body">${body}</div>
      <div class="modal-buttons"></div>
    </div>`;
  const btnRow = wrap.querySelector('.modal-buttons');
  for (const b of buttons) {
    const el = document.createElement('button');
    el.className = `btn ${b.primary ? 'btn-primary' : 'btn-secondary'}`;
    el.innerHTML = b.label;
    el.addEventListener('click', () => {
      if (b.keepOpen !== true) closeModal();
      b.onClick?.();
    });
    btnRow.appendChild(el);
  }
  if (dismissible) {
    wrap.addEventListener('click', (ev) => { if (ev.target === wrap) closeModal(); });
  }
  root.appendChild(wrap);
  requestAnimationFrame(() => wrap.classList.add('open'));
  return wrap;
}

function closeModal() {
  $('#modal-root').innerHTML = '';
}

function rulesHtml() {
  return `
    <div class="rules-list">
      <div class="rule-row">${I.miniGrid('xxxxc.x..')}<p><b>1 Katze pro Farbe.</b> Jede Farbfläche bekommt genau eine Katze.</p></div>
      <div class="rule-row">${I.miniGrid('xcx.x..x.')}<p><b>1 Katze pro Zeile und Spalte.</b> In jeder Zeile und jeder Spalte sitzt genau eine Katze.</p></div>
      <div class="rule-row">${I.miniGrid('xxxxcxxxx')}<p><b>Katzen dürfen sich nicht berühren</b> – auch nicht schräg.</p></div>
    </div>
    <div class="controls-help">
      <p><b>Bedienung:</b> Einmal tippen setzt ein <b>X</b> (hier ist keine Katze), nochmal tippen entfernt es wieder. <b>Doppelt tippen</b> setzt eine <b>Katze</b>, ein Tippen auf die Katze nimmt sie weg. Mit gedrücktem Finger über Felder ziehen markiert mehrere X auf einmal.</p>
      <p><b>Automatische X:</b> Sobald eine Katze sitzt, bekommen alle Felder, die sie blockiert, von selbst ein X.</p>
      <p><b>Fische:</b> Eine Katze an der falschen Stelle kostet einen Fisch. Sind alle drei weg, beginnt das Level von vorn (abschaltbar in den Einstellungen).</p>
      <p><b>Helfer:</b> <span class="inline-ico">${I.ICON_CAT_FACE}</span> setzt eine richtige Katze, <span class="inline-ico">${I.ICON_BULB}</span> erklärt den nächsten logischen Schritt, <span class="inline-ico">${I.ICON_MOUSE}</span> markiert alle Felder, die deine Katzen blockieren.</p>
    </div>`;
}

function showRules(onClose) {
  openModal({
    title: 'So wird gespielt',
    body: rulesHtml(),
    className: 'modal-wide',
    buttons: [{ label: 'Los geht’s!', primary: true, onClick: onClose }],
    dismissible: !onClose,
  });
}

function showSettings() {
  const s = data.settings;
  const toggle = (key, label, desc) => `
    <label class="toggle-row">
      <span><b>${label}</b><small>${desc}</small></span>
      <input type="checkbox" data-key="${key}" ${s[key] ? 'checked' : ''}/>
      <i class="switch" aria-hidden="true"></i>
    </label>`;
  const wrap = openModal({
    title: 'Einstellungen',
    className: 'modal-wide',
    body: `
      ${toggle('sound', 'Töne', 'Soundeffekte beim Spielen')}
      ${toggle('lives', 'Fische (Leben)', 'Falsche Katzen kosten einen Fisch')}
      ${toggle('conflicts', 'Konflikte zeigen', 'Ohne Fische: sich störende Katzen rot markieren')}
      ${toggle('borders', 'Farbgrenzen', 'Dunkle Linien zwischen den Farbflächen (hilft bei Farbschwäche)')}
      ${toggle('unlockAll', 'Alle Level freischalten', 'Jedes Level sofort spielbar')}
      <div class="settings-actions">
        <button class="btn btn-secondary" id="set-rules">${I.ICON_HELP} Spielregeln</button>
        <button class="btn btn-danger" id="set-reset">Fortschritt löschen</button>
      </div>`,
    buttons: [{ label: 'Fertig', primary: true }],
  });
  wrap.querySelectorAll('input[type=checkbox]').forEach((inp) => {
    inp.addEventListener('change', () => {
      data.settings[inp.dataset.key] = inp.checked;
      persist();
      applySettings();
    });
  });
  wrap.querySelector('#set-rules').addEventListener('click', () => showRules());
  wrap.querySelector('#set-reset').addEventListener('click', () => {
    openModal({
      title: 'Fortschritt löschen?',
      body: '<p>Alle Sterne, Punkte und freigeschalteten Level gehen verloren.</p>',
      buttons: [
        { label: 'Abbrechen' },
        {
          label: 'Löschen',
          primary: true,
          onClick: () => {
            data = resetData();
            data.tutorialSeen = true;
            persist();
            applySettings();
            showMenu();
            toast('Fortschritt gelöscht');
          },
        },
      ],
    });
  });
}

function applySettings() {
  setSoundEnabled(data.settings.sound);
  if (board) board.setShowBorders(data.settings.borders);
  if (game) {
    updateConflicts();
    renderFish();
  }
  renderMenu();
}

// ---------------------------------------------------------------------------
// Levelauswahl
// ---------------------------------------------------------------------------

function renderMenu(animate = false) {
  $('#menu-points').textContent = totalPoints().toLocaleString('de-DE');
  $('#menu-stars').textContent = `${totalStars()} / ${LEVELS.length * 3}`;
  const solved = Object.keys(data.best).length;
  $('#menu-solved').textContent = `${solved} / ${LEVELS.length}`;

  const next = nextLevelId();
  const cont = $('#btn-continue');
  if (next) {
    const resume = data.current && data.current.id === next;
    cont.innerHTML = `${I.ICON_PLAY}<span>${resume ? 'Weiterspielen' : 'Spielen'}: Level ${next}</span>`;
    cont.disabled = false;
  } else {
    cont.innerHTML = '<span>Alle 100 Level geschafft! 🎉</span>';
    cont.disabled = true;
  }

  const groups = $('#level-groups');
  groups.innerHTML = '';
  const sizes = [...new Set(LEVELS.map((l) => l.n))];
  groups.classList.toggle('enter', animate);
  let btnIndex = 0;
  for (const n of sizes) {
    const lv = LEVELS.filter((l) => l.n === n);
    const sec = document.createElement('section');
    sec.className = 'level-group';
    const tiers = [...new Set(lv.map((l) => l.tier))].map((t) => TIERS[t].name).join(' · ');
    sec.innerHTML = `<h3><span>${n} × ${n}</span><small>${tiers} · Level ${lv[0].id}–${lv[lv.length - 1].id}</small></h3><div class="level-grid"></div>`;
    const grid = sec.querySelector('.level-grid');
    for (const l of lv) {
      const best = data.best[l.id];
      const unlocked = isUnlocked(l.id);
      const btn = document.createElement('button');
      btn.className = `level-btn tier-${l.tier}${best ? ' done' : ''}${unlocked ? '' : ' locked'}${data.current?.id === l.id ? ' current' : ''}`;
      btn.style.setProperty('--tier', TIERS[l.tier].color);
      btn.style.setProperty('--i', Math.min(btnIndex++, 40));
      btn.setAttribute('aria-label', `Level ${l.id}${unlocked ? '' : ' (gesperrt)'}`);
      const stars = best ? [1, 2, 3].map((k) => `<i class="${k <= best.stars ? 'on' : ''}">${I.ICON_STAR}</i>`).join('') : '';
      btn.innerHTML = unlocked
        ? `<span class="num">${l.id}</span><span class="stars">${stars}</span>`
        : `<span class="lock">${I.ICON_LOCK}</span><span class="num small">${l.id}</span>`;
      btn.addEventListener('click', () => {
        if (!unlocked) { retrigger(btn, 'nope'); sfx.clear(); toast('Löse zuerst die vorherigen Level 🐾'); return; }
        sfx.pop();
        startLevel(l.id);
      });
      grid.appendChild(btn);
    }
    groups.appendChild(sec);
  }
}

function nextLevelId() {
  if (data.current && isUnlocked(data.current.id) && !data.best[data.current.id]) return data.current.id;
  for (const l of LEVELS) if (!data.best[l.id] && isUnlocked(l.id)) return l.id;
  return null;
}

function showMenu() {
  pauseTimer();
  saveCurrent();
  game = null;
  board?.clearHint();
  hideHintBubble();
  $('#screen-game').classList.add('hidden');
  $('#screen-menu').classList.remove('hidden');
  renderMenu(true);
  const cur = document.querySelector('.level-btn.current') || document.querySelector('.level-btn:not(.done):not(.locked)');
  cur?.scrollIntoView({ block: 'center' });
}

// ---------------------------------------------------------------------------
// Spiel
// ---------------------------------------------------------------------------

function ensureBoard() {
  if (board) return true;
  try {
    board = new Board3D($('#board'), {
      onTap: handleTap,
      onDragStart: handleDragStart,
      onDragEnter: handleDragEnter,
      onDragEnd: handleDragEnd,
      onCatLand: () => { sfx.land(); vibrate(18); },
    });
    return true;
  } catch (err) {
    console.error(err);
    $('#board').innerHTML = '<p class="no-webgl">Dein Browser unterstützt leider kein WebGL – das Spielfeld kann nicht angezeigt werden.</p>';
    return false;
  }
}

function startLevel(id, { fresh = false } = {}) {
  const level = parseLevel(LEVELS[id - 1]);
  const geo = makeGeometry(level.n, level.regionArr);
  const n = level.n;
  const saved = !fresh && data.current && data.current.id === id ? data.current : null;
  game = {
    level,
    geo,
    solution: new Set(level.solution.map((c, r) => r * n + c)),
    colorsHex: level.colors.map((p) => PALETTE[p].hex),
    marks: saved ? Uint8Array.from(saved.marks) : new Uint8Array(n * n),
    history: [],
    fish: saved ? saved.fish : FISH,
    mistakes: saved ? saved.mistakes : 0,
    hintsLeft: saved ? saved.hintsLeft : HINTS_PER_LEVEL,
    jokersLeft: saved ? saved.jokersLeft : JOKERS_PER_LEVEL,
    helpersUsed: saved ? saved.helpersUsed : 0,
    elapsed: saved ? saved.elapsed : 0,
    finished: false,
    dragMode: null,
    lastFound: new Set(),
  };
  if (game.fish <= 0) {
    game.fish = FISH;
    game.marks = new Uint8Array(n * n);
  }

  $('#screen-menu').classList.add('hidden');
  $('#screen-game').classList.remove('hidden');
  hideHintBubble();
  if (!ensureBoard()) return;
  board.locked = false;
  board.setLevel({ n, regions: level.regionArr }, game.colorsHex, data.settings.borders);
  board.setMarks(game.marks, false);

  $('#hud-level').textContent = id;
  const tier = TIERS[level.tier];
  $('#hud-diff').textContent = tier.name;
  $('#hud-flame').style.color = tier.color;
  updateHud();
  fitHud();
  renderFish();
  renderCatBar(true);
  updateConflicts();
  renderTools();
  saveCurrent();
  resumeTimer();

  if (!data.tutorialSeen) {
    data.tutorialSeen = true;
    persist();
    showRules();
  }
}

function updateHud(animateFrom = null) {
  const el = $('#hud-points');
  const total = totalPoints();
  if (animateFrom !== null && animateFrom !== total) {
    retrigger(el, 'bump');
    animateNumber(el, animateFrom, total, 1100, () => fitHud());
  } else {
    el.textContent = total.toLocaleString('de-DE');
  }
  fitHud();
}

/** Ein verlorener Fisch springt aus der Leiste und fliegt davon. */
function flyFish(index) {
  const el = $('#fish-bar').children[index];
  if (!el) return;
  const r = el.getBoundingClientRect();
  const ghost = document.createElement('div');
  ghost.className = 'fish-ghost';
  ghost.innerHTML = I.fishIcon(false);
  ghost.style.left = `${r.left}px`;
  ghost.style.top = `${r.top}px`;
  ghost.style.width = `${r.width}px`;
  document.body.appendChild(ghost);
  ghost.addEventListener('animationend', () => ghost.remove());
}

function renderFish() {
  const el = $('#fish-bar');
  const lives = data.settings.lives;
  el.classList.toggle('relaxed', !lives);
  el.title = lives ? `${game.fish} von ${FISH} Fischen übrig` : 'Entspannter Modus – Fehler kosten nichts';
  el.innerHTML = Array.from({ length: FISH }, (_, k) => I.fishIcon(lives && k >= game.fish)).join('');
}

function renderCatBar(initial = false) {
  const el = $('#cat-bar');
  const { level, marks } = game;
  const found = new Set();
  marks.forEach((m, i) => { if (m === MARK_CAT) found.add(level.regionArr[i]); });
  if (initial || el.childElementCount !== level.n) {
    el.innerHTML = '';
    for (let k = 0; k < level.n; k++) {
      const span = document.createElement('span');
      span.className = 'cat-slot';
      span.style.setProperty('--k', k);
      el.appendChild(span);
    }
    el.style.setProperty('--slots', level.n);
  }
  [...el.children].forEach((span, k) => {
    const p = PALETTE[level.colors[k]];
    const f = found.has(k);
    span.innerHTML = I.catHeadIcon(f ? p.hex : p.pastel, f);
    span.title = `${p.name}${f ? ' ✓' : ''}`;
    if (f && !game.lastFound.has(k) && !initial) {
      // erst beim Aufprall der Katze ploppen
      setTimeout(() => retrigger(span, 'pop'), CAT_LAND_MS);
    }
  });
  game.lastFound = found;
}

function renderTools() {
  const j = $('#tool-joker .badge');
  const h = $('#tool-hint .badge');
  if (j.textContent !== String(game.jokersLeft)) retrigger(j, 'bump');
  if (h.textContent !== String(game.hintsLeft)) retrigger(h, 'bump');
  j.textContent = game.jokersLeft;
  h.textContent = game.hintsLeft;
  $('#tool-joker').classList.toggle('empty', game.jokersLeft <= 0);
  $('#tool-hint').classList.toggle('empty', game.hintsLeft <= 0);
  $('#btn-undo').disabled = game.history.length === 0;
}

function saveCurrent() {
  if (!game || game.finished) return;
  data.current = {
    id: game.level.id,
    marks: Array.from(game.marks),
    fish: game.fish,
    mistakes: game.mistakes,
    hintsLeft: game.hintsLeft,
    jokersLeft: game.jokersLeft,
    helpersUsed: game.helpersUsed,
    elapsed: currentElapsed(),
  };
  persist();
}

// Zeit -----------------------------------------------------------------------

function currentElapsed() {
  if (!game) return 0;
  return game.elapsed + (game.runningSince ? (performance.now() - game.runningSince) / 1000 : 0);
}

function resumeTimer() {
  if (!game || game.finished || game.runningSince) return;
  game.runningSince = performance.now();
  clearInterval(timerHandle);
  timerHandle = setInterval(() => { $('#timer').textContent = formatTime(currentElapsed()); }, 500);
  $('#timer').textContent = formatTime(currentElapsed());
}

function pauseTimer() {
  clearInterval(timerHandle);
  if (game && game.runningSince) {
    game.elapsed = currentElapsed();
    game.runningSince = 0;
  }
}

document.addEventListener('visibilitychange', () => {
  if (document.hidden) { pauseTimer(); saveCurrent(); } else if (game && !$('#screen-game').classList.contains('hidden')) resumeTimer();
});

// Markierungen -----------------------------------------------------------------

function snapshot() {
  game.history.push(game.marks.slice());
  if (game.history.length > 300) game.history.shift();
}

function setMark(i, m, animate = true) {
  game.marks[i] = m;
  board.setCell(i, m, animate);
}

function attackedByCat(i, ignore = -1) {
  const { geo, marks } = game;
  for (const a of geo.attacks[i]) if (a !== ignore && marks[a] === MARK_CAT) return true;
  return false;
}

const cellDist = (a, b) => {
  const n = game.level.n;
  return Math.hypot(Math.floor(a / n) - Math.floor(b / n), (a % n) - (b % n));
};

/**
 * Markiert alle von Katzen blockierten Felder mit (automatischen) X –
 * als Welle, die sich von der jeweiligen Katze aus ausbreitet.
 */
function autoMark(onlyFrom = null) {
  const { geo, marks } = game;
  const sources = onlyFrom !== null ? [onlyFrom] : [...marks.keys()].filter((i) => marks[i] === MARK_CAT);
  // Bei einer frisch gesetzten Katze startet die Welle genau beim Aufprall.
  const base = onlyFrom !== null ? CAT_LAND_MS + 30 : 60;
  const delays = new Map();
  for (const s of sources) {
    for (const a of geo.attacks[s]) {
      if (marks[a] !== MARK_EMPTY) continue;
      const d = base + (cellDist(s, a) - 1) * WAVE_MS;
      delays.set(a, Math.min(delays.get(a) ?? Infinity, d));
    }
  }
  for (const [c, d] of delays) {
    game.marks[c] = MARK_AUTO_X;
    board.setCell(c, MARK_AUTO_X, true, d);
  }
  if (delays.size) sfx.wave([...delays.values()].map((d) => d - base));
  return delays.size;
}

/** Entfernt automatische X, die keine Katze mehr blockiert (Welle zurück zur Katze). */
function cleanupAutoX(origin = -1) {
  game.marks.forEach((m, i) => {
    if (m !== MARK_AUTO_X || attackedByCat(i)) return;
    game.marks[i] = MARK_EMPTY;
    const d = origin >= 0 ? Math.max(0, 260 - cellDist(origin, i) * 25) : 0;
    board.setCell(i, MARK_EMPTY, true, d);
  });
}

function placeCat(i, byHelper = false) {
  if (data.settings.lives && !byHelper && !game.solution.has(i)) {
    // Fehler: Katze landet, schüttelt den Kopf, ein Fisch springt weg, Katze verpufft.
    setMark(i, MARK_CAT);
    board.flashError(i);
    sfx.cat();
    board.locked = true;
    game.fish -= 1;
    game.mistakes += 1;
    const lost = game.fish;
    const g = game;
    setTimeout(() => {
      if (game !== g) return;
      sfx.error();
      vibrate([50, 40, 70]);
      flyFish(lost);
      renderFish();
      retrigger($('#fish-bar').children[lost], 'lose');
      retrigger($('#fish-bar'), 'hurt');
      toast(game.fish > 0 ? 'Hier passt keine Katze hin – ein Fisch ist weg! 🐟' : 'Oh nein, keine Fische mehr!');
    }, CAT_LAND_MS);
    setTimeout(() => {
      if (game !== g) return;
      if (game.marks[i] === MARK_CAT) {
        board.poofCat(i);
        sfx.poof();
        game.marks[i] = MARK_X;
        setTimeout(() => { if (game === g && game.marks[i] === MARK_X) board.setCell(i, MARK_X); }, 160);
      }
      board.locked = false;
      afterChange();
      if (game.fish <= 0) gameOver();
    }, CAT_LAND_MS + 820);
    return;
  }
  setMark(i, MARK_CAT);
  sfx.cat();
  autoMark(i);
}

function removeCat(i) {
  setMark(i, MARK_EMPTY);
  sfx.clear();
  cleanupAutoX(i);
}

/**
 * Tippen: leer → X, X → leer, Katze → weg.
 * Doppeltippen: Katze setzen (der erste Tipp wird dabei ersetzt).
 */
function handleTap(i, timeStamp) {
  if (!game || game.finished) return;
  hideHintBubble();
  // Zeitpunkt des Ereignisses (nicht der Verarbeitung) – robust auch auf langsamen Geräten
  const now = timeStamp ?? performance.now();
  if (lastTap.cell === i && now - lastTap.time < DOUBLE_TAP_MS) {
    const before = lastTap.before;
    lastTap.cell = -1;
    // Doppeltippen auf eine Katze: der erste Tipp hat sie schon entfernt.
    if (before !== MARK_CAT) {
      placeCat(i);
      afterChange();
    }
    return;
  }
  const cur = game.marks[i];
  lastTap = { cell: i, time: now, before: cur };
  snapshot();
  if (cur === MARK_EMPTY) {
    setMark(i, MARK_X);
    sfx.mark();
    vibrate(6);
  } else if (isX(cur)) {
    setMark(i, MARK_EMPTY);
    sfx.clear();
    vibrate(4);
  } else {
    removeCat(i);
    vibrate(10);
  }
  afterChange();
}

function handleDragStart(start) {
  if (!game || game.finished) return;
  hideHintBubble();
  lastTap.cell = -1;
  snapshot();
  game.dragMode = isX(game.marks[start]) ? 'erase' : 'mark';
  game.dragChain = 0;
  applyDrag(start);
}

function handleDragEnter(i) {
  if (!game || game.finished || !game.dragMode) return;
  applyDrag(i);
}

function applyDrag(i) {
  const m = game.marks[i];
  if (game.dragMode === 'mark' && m === MARK_EMPTY) { setMark(i, MARK_X); sfx.mark(1 + game.dragChain++); vibrate(5); }
  if (game.dragMode === 'erase' && isX(m)) { setMark(i, MARK_EMPTY); sfx.clear(); vibrate(4); }
}

function handleDragEnd() {
  if (!game) return;
  game.dragMode = null;
  afterChange();
}

function conflictCells() {
  const { geo, marks } = game;
  const out = new Set();
  marks.forEach((m, i) => {
    if (m !== MARK_CAT) return;
    for (const a of geo.attacks[i]) if (marks[a] === MARK_CAT) { out.add(i); out.add(a); }
  });
  return out;
}

function updateConflicts() {
  if (!game || !board) return;
  const show = !data.settings.lives && data.settings.conflicts;
  board.setConflicts(show ? [...conflictCells()] : []);
}

function afterChange() {
  if (!game) return;
  renderCatBar();
  updateConflicts();
  renderTools();
  saveCurrent();
  checkWin();
}

function checkWin() {
  const { marks, level } = game;
  let cats = 0;
  marks.forEach((m) => { if (m === MARK_CAT) cats++; });
  if (cats !== level.n || conflictCells().size) return;
  const units = new Set();
  marks.forEach((m, i) => { if (m === MARK_CAT) game.geo.cellUnits[i].forEach((u) => units.add(u)); });
  if (units.size !== 3 * level.n) return;
  win();
}

function win() {
  game.finished = true;
  board.locked = true;
  pauseTimer();
  hideHintBubble();
  board.clearHint();
  const { level } = game;
  const slips = game.mistakes + game.helpersUsed;
  const stars = slips === 0 ? 3 : slips <= 2 ? 2 : 1;
  const base = 60 + level.id * 6 + Math.round(level.score * 1.5);
  const points = Math.round(base * STAR_MULT[stars]);
  const prev = data.best[level.id];
  const time = currentElapsed();
  const improved = !prev || points > prev.points;
  data.best[level.id] = {
    stars: Math.max(stars, prev?.stars || 0),
    points: Math.max(points, prev?.points || 0),
    time: Math.min(time, prev?.time ?? Infinity),
  };
  const pointsBefore = totalPoints();
  data.unlocked = Math.max(data.unlocked, Math.min(LEVELS.length, level.id + 1));
  data.current = null;
  persist();
  const isLast = level.id === LEVELS.length;
  const g = game;
  // Erst die letzte Katze landen lassen, dann feiern.
  setTimeout(() => {
    if (game !== g) return;
    board.celebrate();
    sfx.win();
    vibrate([30, 60, 30, 60, 80]);
    retrigger($('#cat-bar'), 'party');
    updateHud(pointsBefore);
  }, CAT_ANIM_MS - 80);
  setTimeout(() => {
    if (!game || game.level.id !== level.id) return;
    openModal({
      title: isLast ? 'Meisterkatze! 🏆' : 'Geschafft!',
      className: 'modal-win',
      dismissible: false,
      body: `
        <div class="win-cat">${I.ICON_CAT_FACE}</div>
        <div class="win-stars">${[1, 2, 3].map((k) => `<i class="${k <= stars ? 'on' : ''}" style="--d:${0.25 + k * 0.28}s">${I.ICON_STAR}</i>`).join('')}</div>
        <div class="win-stats">
          <div><small>Punkte</small><b id="win-points">+0</b></div>
          <div><small>Zeit</small><b>${formatTime(time)}</b></div>
          <div><small>Fehler</small><b>${game.mistakes}</b></div>
        </div>
        ${!improved ? '<p class="muted">Dein bisheriger Rekord für dieses Level bleibt bestehen.</p>' : ''}
        ${stars < 3 ? '<p class="muted">Ohne Fehler und ohne Helfer gibt es 3 Sterne.</p>' : ''}
        ${isLast ? '<p>Du hast alle 100 Level gelöst – unglaublich!</p>' : ''}`,
      buttons: [
        { label: 'Levelauswahl', onClick: showMenu },
        ...(isLast ? [] : [{ label: `Weiter: Level ${level.id + 1}`, primary: true, onClick: () => startLevel(level.id + 1) }]),
      ],
    });
    // Sterne einzeln mit Ton, danach die Punkte hochzählen
    for (let k = 0; k < stars; k++) setTimeout(() => { sfx.star(k); vibrate(12); }, (0.25 + (k + 1) * 0.28) * 1000 + 120);
    const pts = document.getElementById('win-points');
    setTimeout(() => {
      if (!pts) return;
      let n = 0;
      animateNumber(pts, 0, points, 900, () => { if (n++ % 3 === 0) sfx.tick(); });
      setTimeout(() => { pts.textContent = `+${points.toLocaleString('de-DE')}`; retrigger(pts, 'bump'); }, 950);
    }, (0.25 + stars * 0.28) * 1000 + 350);
  }, CAT_ANIM_MS + 1900);
}

function gameOver() {
  game.finished = true;
  board.locked = true;
  pauseTimer();
  sfx.lose();
  data.current = null;
  persist();
  const id = game.level.id;
  openModal({
    title: 'Keine Fische mehr',
    dismissible: false,
    body: `<div class="lose-fish">${I.fishIcon(true)}${I.fishIcon(true)}${I.fishIcon(true)}</div><p>Die Katzen sind hungrig nach Hause gegangen. Versuch es gleich noch einmal!</p>`,
    buttons: [
      { label: 'Levelauswahl', onClick: showMenu },
      { label: 'Nochmal', primary: true, onClick: () => startLevel(id, { fresh: true }) },
    ],
  });
}

// Helfer -----------------------------------------------------------------------

function useJoker() {
  if (!game || game.finished) return;
  if (game.jokersLeft <= 0) { toast('Der Katzen-Joker ist für dieses Level aufgebraucht.'); return; }
  const missing = [...game.solution].filter((c) => game.marks[c] !== MARK_CAT);
  if (!missing.length) return;
  hideHintBubble();
  snapshot();
  // Bevorzugt die Farbe mit den meisten noch offenen Feldern – dort hilft der Joker am meisten.
  const openCount = (c) => game.geo.units[game.geo.cellUnits[c][2]].cells.filter((x) => game.marks[x] === MARK_EMPTY).length;
  missing.sort((a, b) => openCount(b) - openCount(a));
  const cell = missing[0];
  retrigger($('#tool-joker'), 'used');
  placeCat(cell, true);
  game.jokersLeft -= 1;
  game.helpersUsed += 1;
  toast('Der Joker hat eine Katze gesetzt 🐱');
  afterChange();
}

function useHint() {
  if (!game || game.finished) return;
  if (!$('#hint-bubble').classList.contains('hidden')) { hideHintBubble(); return; }
  if (game.hintsLeft <= 0) { toast('Keine Hinweise mehr für dieses Level.'); return; }
  const h = computeHint(game.geo, game.level, game.marks);
  if (!h.action) { toast(h.text); return; }
  game.hintsLeft -= 1;
  game.helpersUsed += 1;
  sfx.hint();
  retrigger($('#tool-hint'), 'used');
  $('#tool-hint').classList.add('active');
  board.setHint(h);
  showHintBubble(h);
  renderTools();
  saveCurrent();
}

function useMouse() {
  if (!game || game.finished) return;
  hideHintBubble();
  const before = game.marks.slice();
  const count = autoMark();
  retrigger($('#tool-mouse'), 'used');
  if (!count) {
    toast(game.marks.some((m) => m === MARK_CAT) ? 'Alles schon markiert – die Maus ist zufrieden.' : 'Setze zuerst eine Katze – dann jagt die Maus los.');
    return;
  }
  game.history.push(before);
  sfx.mouse();
  afterChange();
}

function showHintBubble(h) {
  const el = $('#hint-bubble');
  el.querySelector('p').textContent = h.text;
  const apply = el.querySelector('#hint-apply');
  apply.onclick = () => {
    const a = h.action;
    hideHintBubble();
    if (!a || game.finished) return;
    snapshot();
    if (a.type === 'cat') placeCat(a.cells[0], true);
    if (a.type === 'x') {
      a.cells.forEach((c, k) => {
        if (game.marks[c] !== MARK_EMPTY) return;
        game.marks[c] = MARK_X;
        setTimeout(() => {
          if (game && game.marks[c] === MARK_X) { board.setCell(c, MARK_X); sfx.mark(1 + k); }
        }, k * 70);
      });
    }
    if (a.type === 'clear') a.cells.forEach((c) => { if (game.marks[c] === MARK_CAT) removeCat(c); else setMark(c, MARK_EMPTY); });
    afterChange();
  };
  el.classList.remove('hidden');
}

function hideHintBubble() {
  $('#hint-bubble').classList.add('hidden');
  $('#tool-hint').classList.remove('active');
  board?.clearHint();
}

function undo() {
  if (!game || game.finished || !game.history.length) return;
  hideHintBubble();
  lastTap.cell = -1;
  const prev = game.history.pop();
  game.marks = prev;
  board.setMarks(prev);
  sfx.clear();
  afterChange();
}

function restart() {
  if (!game || game.finished) return;
  if (!game.marks.some((m) => m !== MARK_EMPTY)) return;
  hideHintBubble();
  snapshot();
  game.marks.forEach((_, i) => setMark(i, MARK_EMPTY));
  afterChange();
  toast('Spielfeld geleert – „Rückgängig“ holt alles zurück.');
}

// ---------------------------------------------------------------------------
// Start
// ---------------------------------------------------------------------------

function buildStaticUi() {
  $('#btn-back').innerHTML = I.ICON_BACK;
  document.querySelectorAll('.btn-settings').forEach((b) => { b.innerHTML = I.ICON_GEAR; });
  $('#hud-flame').innerHTML = I.ICON_FLAME;
  $('#logo-cat').innerHTML = I.ICON_CAT_FACE;
  $('#rule-1 .ico').innerHTML = I.miniGrid('xxxxc.x..');
  $('#rule-2 .ico').innerHTML = I.miniGrid('xcx.x..x.');
  $('#rule-3 .ico').innerHTML = I.miniGrid('xxxxcxxxx');
  $('#tool-joker .ico').innerHTML = I.ICON_CAT_FACE;
  $('#tool-hint .ico').innerHTML = I.ICON_BULB;
  $('#tool-mouse .ico').innerHTML = I.ICON_MOUSE;
  $('#btn-undo').insertAdjacentHTML('afterbegin', I.ICON_UNDO);
  $('#btn-restart').insertAdjacentHTML('afterbegin', I.ICON_RESTART);
  $('#btn-rules').innerHTML = I.ICON_HELP;

  $('#btn-back').addEventListener('click', showMenu);
  document.querySelectorAll('.btn-settings').forEach((b) => b.addEventListener('click', showSettings));
  $('#btn-rules').addEventListener('click', () => showRules());
  $('#btn-continue').addEventListener('click', () => { const id = nextLevelId(); if (id) startLevel(id); });
  $('#tool-joker').addEventListener('click', useJoker);
  $('#tool-hint').addEventListener('click', useHint);
  $('#tool-mouse').addEventListener('click', useMouse);
  $('#btn-undo').addEventListener('click', undo);
  $('#btn-restart').addEventListener('click', restart);
  $('#hint-close').addEventListener('click', hideHintBubble);
  $('#rules-card').addEventListener('click', () => showRules());

  document.addEventListener('keydown', (ev) => {
    if (!game || $('#screen-game').classList.contains('hidden')) return;
    if ((ev.ctrlKey || ev.metaKey) && ev.key.toLowerCase() === 'z') { ev.preventDefault(); undo(); }
    if (ev.key === 'Escape') { if ($('#modal-root').childElementCount) closeModal(); else hideHintBubble(); }
  });
}

buildStaticUi();
renderMenu(true);
if (!data.tutorialSeen) {
  showRules(() => {
    data.tutorialSeen = true;
    persist();
    startLevel(nextLevelId() || 1);
  });
}
