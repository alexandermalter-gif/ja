// Katzen-Sudoku – Spielsteuerung und Oberfläche.
import { LEVELS } from './logic/levels.js';
import { PALETTE } from './logic/palette.js';
import { makeGeometry } from './logic/solver.js';
import { Board3D, CAT_LAND_MS, CAT_ANIM_MS } from './game/board3d.js';
import { MARK_EMPTY, MARK_X, MARK_CAT, MARK_AUTO_X } from './game/marks.js';
import { computeHint } from './game/hints.js';
import { loadData, saveData, resetData } from './game/storage.js';
import { sfx, setSoundEnabled } from './game/audio.js';
import { dateKey, generateDaily, planFor, formatDate, streakInfo, bestStreak, weekView } from './game/daily.js';
import { SKINS, skinById, isSkinUnlocked, unlockText, unlockProgress, catFaceSvg } from './game/skins.js';
import { averageBySize, barChart, formatTime } from './game/stats.js';
import { TUTORIAL_LEVEL, TUTORIAL_INTRO, TUTORIAL_AUTO_X, tutorialTitle, tutorialAction } from './game/tutorial.js';
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
const STAR_MULT = [0, 1, 1.25, 1.6];
const DOUBLE_TAP_MS = 380;
const WAVE_MS = 45; // Verzögerung der X-Welle pro Feld Abstand
const HISTORY_MAX = 60;
const DAILY_CACHE_VERSION = 2;

const $ = (sel) => document.querySelector(sel);
const isX = (m) => m === MARK_X || m === MARK_AUTO_X;

let data = loadData();
let board = null;
let game = null;
let timerHandle = 0;
let lastTap = { cell: -1, time: 0, before: MARK_EMPTY };
let tut = null; // Zustand des Tutorials
let kbCursor = -1; // Tastatur-Cursor (−1 = aus)

setSoundEnabled(data.settings.sound);

// ---------------------------------------------------------------------------
// Hilfsfunktionen
// ---------------------------------------------------------------------------

function parseLevel(level) {
  const regions = [...level.regions].map((ch) => parseInt(ch, 36));
  return { ...level, regionArr: regions };
}

const today = () => dateKey();

function dailyPointsSum() {
  return Object.values(data.daily.solved).reduce((a, b) => a + (b.points || 0), 0);
}

function totalPoints() {
  return Object.values(data.best).reduce((a, b) => a + (b.points || 0), 0) + dailyPointsSum();
}

function totalStars() {
  return Object.values(data.best).reduce((a, b) => a + (b.stars || 0), 0);
}

function streakNow() {
  return streakInfo(data.daily.solved, today());
}

/** Fortschritt für die Katzen-Freischaltung */
function progress() {
  return {
    stars: totalStars(),
    streak: Math.max(bestStreak(data.daily.solved), streakNow().current),
    daily: Object.keys(data.daily.solved).length,
  };
}

function unlockedSkins() {
  const p = progress();
  return SKINS.filter((s) => isSkinUnlocked(s, p)).map((s) => s.id);
}

function selectedSkin() {
  const id = data.skins.selected;
  return unlockedSkins().includes(id) ? id : 'smoking';
}

function isUnlocked(id) {
  return data.settings.unlockAll || id <= data.unlocked;
}

function persist() {
  saveData(data);
}

function toast(msg) {
  const el = $('#toast');
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toast.t);
  toast.t = setTimeout(() => el.classList.remove('show'), 2600);
}

/** Ansage für Screenreader */
function announce(msg) {
  const el = $('#sr-live');
  el.textContent = '';
  setTimeout(() => { el.textContent = msg; }, 30);
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
function animateNumber(el, from, to, dur = 900, onStep, fmt = (v) => Math.round(v).toLocaleString('de-DE')) {
  const t0 = performance.now();
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
window.addEventListener('resize', () => { fitHud(); placeHand(); });

function cellName(i) {
  if (!game) return '';
  const n = game.level.n;
  const color = PALETTE[game.level.colors[game.level.regionArr[i]]].name;
  return `Zeile ${Math.floor(i / n) + 1}, Spalte ${(i % n) + 1}, ${color}`;
}

function cellStateName(i) {
  const m = game.marks[i];
  if (m === MARK_CAT) return 'Katze';
  if (m === MARK_X) return 'X';
  if (m === MARK_AUTO_X) return 'automatisches X';
  return game.notes[i] ? 'Notiz' : 'leer';
}

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
  if (!buttons.length) btnRow.remove();
  if (dismissible) {
    wrap.addEventListener('click', (ev) => { if (ev.target === wrap) closeModal(); });
  }
  root.appendChild(wrap);
  requestAnimationFrame(() => wrap.classList.add('open'));
  setTimeout(() => wrap.querySelector('.modal-buttons .btn-primary, .modal-buttons .btn')?.focus({ preventScroll: true }), 60);
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
      <p><b>Bedienung:</b> Einmal tippen setzt ein <b>X</b>, nochmal tippen entfernt es. <b>Doppelt tippen</b> setzt eine <b>Katze</b>, ein Tippen auf die Katze nimmt sie weg. Über Felder ziehen markiert mehrere X auf einmal.</p>
      <p><b>Notizen:</b> <b>Lange drücken</b> (am Computer: Rechtsklick) setzt einen <span class="inline-ico">${I.ICON_PAW}</span> Pfotenabdruck – „hier vielleicht eine Katze“.</p>
      <p><b>Automatische X:</b> Sobald eine Katze sitzt, bekommen alle Felder, die sie blockiert, ein hellgraues X. Deine eigenen X sind dunkelgrau.</p>
      <p><b>Fische:</b> Eine Katze an der falschen Stelle kostet einen Fisch. Sind alle drei weg, beginnt das Level von vorn (abschaltbar in den Einstellungen).</p>
      <p><b>Helfer:</b> <span class="inline-ico">${I.ICON_CAT_FACE}</span> Joker setzt eine richtige Katze (1× pro Level). <span class="inline-ico">${I.ICON_BULB}</span> Hinweis erklärt den nächsten Schritt – neue Hinweise gibt es für 3 Sterne in neuen Leveln und für jedes Tägliche Rätsel. <span class="inline-ico">${I.ICON_MOUSE}</span> Die Maus sucht Fehler und knabbert sie weg; findet sie keinen, kostet sie nichts.</p>
      <p><b>Tastatur:</b> Pfeiltasten bewegen den Rahmen, Leertaste setzt X, Enter setzt eine Katze, N setzt eine Notiz, Strg+Z macht rückgängig.</p>
    </div>`;
}

function showRules(onClose) {
  openModal({
    title: 'So wird gespielt',
    body: rulesHtml(),
    className: 'modal-wide',
    buttons: [
      ...(onClose ? [] : [{ label: `${I.ICON_CAP} Tutorial starten`, onClick: () => startTutorial() }]),
      { label: 'Los geht’s!', primary: true, onClick: onClose },
    ],
    dismissible: !onClose,
  });
}

function showSettings() {
  const s = data.settings;
  const toggle = (key, label, desc) => `
    <label class="toggle-row">
      <span><b>${label}</b><small>${desc}</small></span>
      <input type="checkbox" id="set-${key}" data-key="${key}" ${s[key] ? 'checked' : ''}/>
      <i class="switch" aria-hidden="true"></i>
    </label>`;
  const wrap = openModal({
    title: 'Einstellungen',
    className: 'modal-wide',
    body: `
      ${toggle('sound', 'Töne', 'Soundeffekte beim Spielen')}
      ${toggle('lives', 'Fische (Leben)', 'Falsche Katzen kosten einen Fisch')}
      ${toggle('conflicts', 'Konflikte zeigen', 'Ohne Fische: sich störende Katzen rot markieren')}
      ${toggle('patterns', 'Muster auf Farben', 'Jede Farbe bekommt ein eigenes Muster (Punkte, Streifen …) – hilft bei Farbschwäche')}
      ${toggle('borders', 'Farbgrenzen', 'Dunkle Linien zwischen den Farbflächen')}
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
      sfx.pop();
    });
  });
  wrap.querySelector('#set-rules').addEventListener('click', () => showRules());
  wrap.querySelector('#set-reset').addEventListener('click', () => {
    openModal({
      title: 'Fortschritt löschen?',
      body: '<p>Alle Sterne, Punkte, Serien, Katzen und freigeschalteten Level gehen verloren.</p>',
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
  if (board) {
    board.setShowBorders(data.settings.borders);
    board.setPatterns(data.settings.patterns);
  }
  if (game) {
    updateConflicts();
    renderFish();
  }
  renderMenu();
}

// ---------------------------------------------------------------------------
// Menü
// ---------------------------------------------------------------------------

function renderDailyCard() {
  const key = today();
  const plan = planFor(key);
  const solved = data.daily.solved[key];
  const st = streakNow();
  const week = weekView(data.daily.solved, key);
  const inProgress = data.current?.key === `D${key}`;
  const card = $('#daily-card');
  card.classList.toggle('solved', !!solved);
  card.innerHTML = `
    <div class="daily-top">
      <span class="daily-ico">${I.ICON_CALENDAR}</span>
      <div class="daily-title">
        <small>Tägliches Rätsel</small>
        <b>${formatDate(key)}</b>
        <span>${plan.n} × ${plan.n} · ${TIERS[plan.tier].name}</span>
      </div>
      <div class="streak-badge${st.current ? ' on' : ''}" title="Tage in Folge gelöst">
        <span class="flame">${I.ICON_FLAME}</span><b>${st.current}</b><small>${st.current === 1 ? 'Tag' : 'Tage'}</small>
      </div>
    </div>
    <div class="week" aria-label="Diese Woche">
      ${week.map((d) => `<span class="day ${d.state}" title="${d.key}"><i>${d.state === 'done' ? I.ICON_CHECK : ''}</i><small>${d.label}</small></span>`).join('')}
    </div>
    <button class="btn ${solved ? 'btn-secondary' : 'btn-primary'} btn-daily" id="btn-daily">
      ${solved ? `${I.ICON_CHECK}<span>Gelöst in ${formatTime(solved.time)} – morgen gibt’s ein neues</span>` : `${I.ICON_PLAY}<span>${inProgress ? 'Weiterspielen' : 'Jetzt spielen'}</span>`}
    </button>
    ${!solved && !st.todayDone && st.current ? `<p class="daily-note">Löse heute, damit deine Serie von ${st.current} ${st.current === 1 ? 'Tag' : 'Tagen'} weiterläuft!</p>` : ''}`;
  card.querySelector('#btn-daily').addEventListener('click', () => {
    if (solved) { retrigger(card, 'nope'); toast('Heute schon gelöst – morgen wartet ein neues Rätsel 🐾'); return; }
    sfx.pop();
    startDaily();
  });
}

function renderMenu(animate = false) {
  $('#menu-points').textContent = totalPoints().toLocaleString('de-DE');
  $('#menu-stars').textContent = `${totalStars()} / ${LEVELS.length * 3}`;
  const solved = Object.keys(data.best).length;
  $('#menu-solved').textContent = `${solved} / ${LEVELS.length}`;
  renderDailyCard();

  const skins = unlockedSkins();
  $('#logo-cat').innerHTML = catFaceSvg(skinById(selectedSkin()));
  $('#btn-skins').innerHTML = `<span class="chip-ico">${catFaceSvg(skinById(selectedSkin()), { wink: false })}</span><span>Katzen <b>${skins.length}/${SKINS.length}</b></span>`;
  $('#btn-hints-info').innerHTML = `<span class="chip-ico">${I.ICON_BULB}</span><span>Hinweise <b>${data.hints}</b></span>`;

  const next = nextLevelId();
  const cont = $('#btn-continue');
  if (next) {
    const resume = data.current && data.current.key === `L${next}`;
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
      btn.className = `level-btn tier-${l.tier}${best ? ' done' : ''}${unlocked ? '' : ' locked'}${data.current?.key === `L${l.id}` ? ' current' : ''}`;
      btn.style.setProperty('--tier', TIERS[l.tier].color);
      btn.style.setProperty('--i', Math.min(btnIndex++, 40));
      const timeInfo = best?.time != null && Number.isFinite(best.time) ? `, Bestzeit ${formatTime(best.time)}` : '';
      btn.setAttribute('aria-label', `Level ${l.id}${unlocked ? '' : ' (gesperrt)'}${best ? `, ${best.stars} Sterne${timeInfo}` : ''}`);
      if (best?.time != null && Number.isFinite(best.time)) btn.title = `Bestzeit ${formatTime(best.time)}`;
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
  const cur = data.current?.key;
  if (cur && cur.startsWith('L')) {
    const id = Number(cur.slice(1));
    if (isUnlocked(id) && !data.best[id]) return id;
  }
  for (const l of LEVELS) if (!data.best[l.id] && isUnlocked(l.id)) return l.id;
  return null;
}

function showMenu() {
  pauseTimer();
  saveCurrent();
  endTutorialUi();
  game = null;
  board?.clearHint();
  board?.stopMouse();
  hideHintBubble();
  $('#screen-game').classList.add('hidden');
  $('#screen-menu').classList.remove('hidden');
  renderMenu(true);
  const cur = document.querySelector('.level-btn.current') || document.querySelector('.level-btn:not(.done):not(.locked)');
  cur?.scrollIntoView({ block: 'center' });
  window.scrollTo({ top: 0 });
}

// --- Katzen-Sammlung ------------------------------------------------------------

function showSkins() {
  const p = progress();
  const sel = selectedSkin();
  const cards = SKINS.map((s) => {
    const open = isSkinUnlocked(s, p);
    const pct = Math.round(unlockProgress(s, p) * 100);
    return `<button class="skin-card${open ? '' : ' locked'}${s.id === sel ? ' selected' : ''}" data-id="${s.id}" ${open ? '' : 'aria-disabled="true"'}>
      <span class="skin-face">${catFaceSvg(s, { wink: false, locked: !open })}${open ? '' : `<span class="skin-lock">${I.ICON_LOCK}</span>`}</span>
      <b>${s.name}</b>
      <small>${s.id === sel ? 'Ausgewählt' : open ? 'Antippen zum Auswählen' : unlockText(s)}</small>
      ${open ? '' : `<span class="skin-progress" aria-label="${pct} Prozent"><i style="width:${pct}%"></i></span>`}
    </button>`;
  }).join('');
  const wrap = openModal({
    title: 'Deine Katzen',
    className: 'modal-wide',
    body: `<p class="muted">Sterne, Serien und Tägliche Rätsel schalten neue Katzen frei. Die ausgewählte Katze sitzt auf dem Spielfeld.</p><div class="skin-grid">${cards}</div>`,
    buttons: [{ label: 'Fertig', primary: true }],
  });
  wrap.querySelectorAll('.skin-card').forEach((btn) => {
    btn.addEventListener('click', () => {
      const s = skinById(btn.dataset.id);
      if (!isSkinUnlocked(s, progress())) { retrigger(btn, 'nope'); sfx.clear(); toast(`Noch gesperrt: ${unlockText(s)}`); return; }
      selectSkin(s.id);
      wrap.querySelectorAll('.skin-card').forEach((b) => {
        b.classList.toggle('selected', b === btn);
        const sm = b.querySelector('small');
        if (!b.classList.contains('locked')) sm.textContent = b === btn ? 'Ausgewählt' : 'Antippen zum Auswählen';
      });
      retrigger(btn, 'picked');
    });
  });
}

function selectSkin(id) {
  data.skins.selected = id;
  persist();
  board?.setSkin(id);
  sfx.cat();
  renderMenu();
  refreshSkinIcons();
}

function refreshSkinIcons() {
  $('#tool-joker .ico').innerHTML = catFaceSvg(skinById(selectedSkin()));
}

/** Neu freigeschaltete Katzen (seit der letzten Meldung). */
function takeNewSkins() {
  const now = unlockedSkins();
  const fresh = now.filter((id) => !data.skins.seen.includes(id));
  if (fresh.length) {
    data.skins.seen = [...new Set([...data.skins.seen, ...fresh])];
    persist();
  }
  return fresh.map(skinById);
}

// --- Statistik --------------------------------------------------------------------

function showStats() {
  const st = streakNow();
  const solvedLevels = Object.keys(data.best).length;
  const dailyCount = Object.keys(data.daily.solved).length;
  const bySize = averageBySize(LEVELS, data.best);
  const hist = data.history.slice(-20);
  const fastest = Object.entries(data.best)
    .filter(([, b]) => b.time != null && Number.isFinite(b.time))
    .sort((a, b) => a[1].time - b[1].time)[0];
  const playTime = data.history.reduce((a, h) => a + (h.time || 0), 0);
  const tile = (label, value) => `<div class="stat-tile"><small>${label}</small><b>${value}</b></div>`;
  openModal({
    title: 'Statistik',
    className: 'modal-wide',
    body: `
      <div class="stat-tiles">
        ${tile('Gelöste Level', `${solvedLevels}<span>/${LEVELS.length}</span>`)}
        ${tile('Sterne', `${totalStars()}<span>/${LEVELS.length * 3}</span>`)}
        ${tile('Punkte', totalPoints().toLocaleString('de-DE'))}
        ${tile('Tägliche Rätsel', dailyCount)}
        ${tile('Serie', `${st.current} ${st.current === 1 ? 'Tag' : 'Tage'}`)}
        ${tile('Längste Serie', `${bestStreak(data.daily.solved)} ${bestStreak(data.daily.solved) === 1 ? 'Tag' : 'Tage'}`)}
      </div>
      <section class="chart-block">
        <h3>Ø Bestzeit je Brettgröße</h3>
        ${bySize.some((b) => b.avg != null)
          ? barChart(bySize.map((b) => ({ label: b.label, value: b.avg, title: `${b.label} – ${b.solved} von ${b.total} gelöst` })), { labelAll: true, highlightMin: false })
          : '<p class="muted">Löse ein paar Level, dann erscheint hier deine Durchschnittszeit.</p>'}
        <p class="chart-foot">${bySize.map((b) => `${b.label}: ${b.solved}/${b.total}`).join(' · ')}</p>
      </section>
      <section class="chart-block">
        <h3>Deine letzten ${hist.length || ''} Lösungen</h3>
        ${hist.length
          ? barChart(hist.map((h) => ({ label: '', value: h.time, title: h.kind === 'daily' ? `Tägliches Rätsel ${h.day}` : `Level ${h.key.slice(1)} (${h.n}×${h.n})` })), { height: 120 })
          : '<p class="muted">Noch keine Lösungen – leg los!</p>'}
        ${hist.length ? '<p class="chart-foot">Hervorgehoben: deine schnellste Lösung dieser Reihe · rechts die neueste</p>' : ''}
      </section>
      <div class="stat-lines">
        ${fastest ? `<p>Schnellstes Level: <b>Level ${fastest[0]}</b> in <b>${formatTime(fastest[1].time)}</b></p>` : ''}
        ${playTime ? `<p>Gesamte Rätselzeit: <b>${formatTime(playTime)}</b></p>` : ''}
      </div>`,
    buttons: [{ label: 'Fertig', primary: true }],
  });
}

// ---------------------------------------------------------------------------
// Spiel starten
// ---------------------------------------------------------------------------

function ensureBoard() {
  if (board) return true;
  try {
    board = new Board3D($('#board'), {
      onTap: handleTap,
      onDragStart: handleDragStart,
      onDragEnter: handleDragEnter,
      onDragEnd: handleDragEnd,
      onLongPress: toggleNote,
      onCatLand: () => { sfx.land(); vibrate(18); },
      onPointerUsed: () => setKbCursor(-1),
    });
    const cv = board.canvas;
    cv.tabIndex = 0;
    cv.setAttribute('role', 'application');
    cv.setAttribute('aria-label', 'Spielfeld. Pfeiltasten bewegen den Rahmen, Leertaste setzt ein X, Enter eine Katze, N eine Notiz.');
    cv.addEventListener('focus', () => { if (kbCursor < 0 && game && !game.finished && cv.matches(':focus-visible')) handleBoardKey({ key: 'ArrowUp' }); });
    return true;
  } catch (err) {
    console.error(err);
    $('#board').innerHTML = '<p class="no-webgl">Dein Browser unterstützt leider kein WebGL – das Spielfeld kann nicht angezeigt werden.</p>';
    return false;
  }
}

function startLevel(id, opts = {}) {
  startPuzzle({ key: `L${id}`, kind: 'level', level: LEVELS[id - 1] }, opts);
}

async function startDaily() {
  const key = today();
  // v: Version der Farbverteilung – ältere Zwischenspeicher werden neu erzeugt (gleiches Rätsel)
  let level = data.daily.cache?.key === key && data.daily.cache.v === DAILY_CACHE_VERSION ? data.daily.cache.level : null;
  if (!level) {
    const loader = $('#loader');
    loader.classList.remove('hidden');
    const pause = () => new Promise((r) => setTimeout(r, 0));
    await pause();
    await new Promise((r) => requestAnimationFrame(() => r()));
    try {
      level = await generateDaily(key, pause);
      data.daily.cache = { key, level, v: DAILY_CACHE_VERSION };
      persist();
    } finally {
      loader.classList.add('hidden');
    }
  }
  startPuzzle({ key: `D${key}`, kind: 'daily', level, day: key });
}

function startTutorial() {
  closeModal();
  startPuzzle({ key: 'T', kind: 'tutorial', level: TUTORIAL_LEVEL }, { fresh: true });
}

function startPuzzle(p, { fresh = false } = {}) {
  endTutorialUi();
  const level = parseLevel(p.level);
  const geo = makeGeometry(level.n, level.regionArr);
  const n = level.n;
  const saved = !fresh && p.kind !== 'tutorial' && data.current && data.current.key === p.key ? data.current : null;
  game = {
    key: p.key,
    kind: p.kind,
    day: p.day || null,
    level,
    geo,
    solution: new Set(level.solution.map((c, r) => r * n + c)),
    colorsHex: level.colors.map((k) => PALETTE[k].hex),
    marks: saved ? Uint8Array.from(saved.marks) : new Uint8Array(n * n),
    notes: saved?.notes ? Uint8Array.from(saved.notes) : new Uint8Array(n * n),
    history: [],
    fish: saved ? saved.fish : FISH,
    mistakes: saved ? saved.mistakes : 0,
    jokersLeft: saved ? saved.jokersLeft ?? JOKERS_PER_LEVEL : JOKERS_PER_LEVEL,
    helpersUsed: saved ? saved.helpersUsed : 0,
    elapsed: saved ? saved.elapsed : 0,
    finished: false,
    dragMode: null,
    lastFound: new Set(),
  };
  if (game.fish <= 0) {
    game.fish = FISH;
    game.marks = new Uint8Array(n * n);
    game.notes = new Uint8Array(n * n);
  }

  $('#screen-menu').classList.add('hidden');
  $('#screen-game').classList.remove('hidden');
  $('#screen-game').classList.toggle('tutorial-mode', p.kind === 'tutorial');
  window.scrollTo({ top: 0 });
  hideHintBubble();
  if (!ensureBoard()) return;
  board.locked = false;
  board.setSkin(selectedSkin());
  board.setLevel({ n, regions: level.regionArr }, game.colorsHex, {
    borders: data.settings.borders,
    patterns: data.settings.patterns,
    paletteIdx: level.colors,
  });
  board.setMarks(game.marks, false);
  game.notes.forEach((v, i) => { if (v) board.setNote(i, true, false); });
  kbCursor = -1;

  // Kopfzeile je Spielart
  const tier = TIERS[level.tier];
  if (p.kind === 'daily') {
    $('#hud-level-label').textContent = 'Täglich';
    $('#hud-level').textContent = planFor(p.day).weekday.slice(0, 2);
  } else if (p.kind === 'tutorial') {
    $('#hud-level-label').textContent = 'Tutorial';
    $('#hud-level').textContent = '5×5';
  } else {
    $('#hud-level-label').textContent = 'Level';
    $('#hud-level').textContent = level.id;
  }
  $('#hud-diff').textContent = tier.name;
  $('#hud-flame').style.color = tier.color;
  updateHud();
  renderFish();
  renderCatBar(true);
  updateConflicts();
  renderTools();
  refreshSkinIcons();
  saveCurrent();
  resumeTimer();
  fitHud();

  const title = p.kind === 'daily' ? 'Tägliches Rätsel' : p.kind === 'tutorial' ? 'Tutorial' : `Level ${level.id}`;
  announce(`${title}, ${n} mal ${n} Felder. Pfeiltasten bewegen den Rahmen, Leertaste setzt ein X, Enter eine Katze.`);

  if (p.kind === 'tutorial') {
    tut = { step: null, shownAutoX: false, stepNo: 0 };
    showCoach({ ...TUTORIAL_INTRO, button: 'Los geht’s', onButton: tutorialNext });
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

function livesOn() {
  return data.settings.lives && game?.kind !== 'tutorial';
}

function renderFish() {
  const el = $('#fish-bar');
  const lives = livesOn();
  el.classList.toggle('relaxed', !lives);
  el.title = lives ? `${game.fish} von ${FISH} Fischen übrig` : 'Entspannter Modus – Fehler kosten nichts';
  el.setAttribute('aria-label', el.title);
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
  }
  [...el.children].forEach((span, k) => {
    const p = PALETTE[level.colors[k]];
    const f = found.has(k);
    span.innerHTML = I.catHeadIcon(f ? p.hex : p.pastel, f);
    span.title = `${p.name}${f ? ' ✓' : ''}`;
    if (f && !game.lastFound.has(k) && !initial) {
      setTimeout(() => retrigger(span, 'pop'), CAT_LAND_MS);
    }
  });
  el.setAttribute('aria-label', `${found.size} von ${level.n} Farben haben eine Katze`);
  game.lastFound = found;
}

function renderTools() {
  const j = $('#tool-joker .badge');
  const h = $('#tool-hint .badge');
  const hv = String(data.hints);
  if (j.textContent !== String(game.jokersLeft)) retrigger(j, 'bump');
  if (h.textContent !== hv) retrigger(h, 'bump');
  j.textContent = game.jokersLeft;
  h.textContent = hv;
  $('#tool-joker').classList.toggle('empty', game.jokersLeft <= 0);
  $('#tool-hint').classList.toggle('empty', data.hints <= 0);
  $('#btn-undo').disabled = game.history.length === 0;
}

function saveCurrent() {
  if (!game || game.finished || game.kind === 'tutorial') return;
  data.current = {
    key: game.key,
    marks: Array.from(game.marks),
    notes: Array.from(game.notes),
    fish: game.fish,
    mistakes: game.mistakes,
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

// ---------------------------------------------------------------------------
// Markierungen
// ---------------------------------------------------------------------------

function snapshot() {
  game.history.push({ marks: game.marks.slice(), notes: game.notes.slice() });
  if (game.history.length > 300) game.history.shift();
}

function setMark(i, m, animate = true) {
  game.marks[i] = m;
  board.setCell(i, m, animate);
  if (m !== MARK_EMPTY && game.notes[i]) {
    game.notes[i] = 0;
    board.setNote(i, false);
  }
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
  const g = game;
  for (const [c, d] of delays) {
    game.marks[c] = MARK_AUTO_X;
    board.setCell(c, MARK_AUTO_X, true, d);
    if (game.notes[c]) {
      game.notes[c] = 0;
      setTimeout(() => { if (game === g) board.setNote(c, false); }, d);
    }
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
  if (livesOn() && !byHelper && !game.solution.has(i)) {
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
      const msg = game.fish > 0 ? 'Hier passt keine Katze hin – ein Fisch ist weg! 🐟' : 'Oh nein, keine Fische mehr!';
      toast(msg);
      announce(`Falsch. ${msg}`);
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
  announce(`${cellName(i)}: Katze gesetzt.`);
}

function removeCat(i) {
  setMark(i, MARK_EMPTY);
  sfx.clear();
  cleanupAutoX(i);
  announce(`${cellName(i)}: Katze entfernt.`);
}

// --- Tutorial-Schranke ---------------------------------------------------------

/** Im Tutorial sind nur die gerade erklärten Züge erlaubt. */
function tutorialAllows(i, kind) {
  if (game?.kind !== 'tutorial') return true;
  const step = tut?.step;
  if (!step) return false;
  if (step.action.type === 'cat') return kind === 'tap' && i === step.action.cells[0];
  if (step.action.type === 'x') return kind === 'tap' && step.action.cells.includes(i) && game.marks[i] === MARK_EMPTY;
  return false;
}

function tutorialRefuse(i) {
  if (i >= 0) board.wobble(i, 0.2);
  retrigger($('#coach'), 'nudge');
  sfx.clear();
}

/**
 * Tippen: leer → X, X → leer, Katze → weg.
 * Doppeltippen: Katze setzen (der erste Tipp wird dabei ersetzt).
 */
function handleTap(i, timeStamp) {
  if (!game || game.finished) return;
  if (!tutorialAllows(i, 'tap')) { tutorialRefuse(i); return; }
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
    announce(`${cellName(i)}: X.`);
  } else if (isX(cur)) {
    setMark(i, MARK_EMPTY);
    sfx.clear();
    vibrate(4);
    announce(`${cellName(i)}: leer.`);
  } else {
    removeCat(i);
    vibrate(10);
  }
  afterChange();
}

function handleDragStart(start) {
  if (!game || game.finished) return;
  if (game.kind === 'tutorial') { tutorialRefuse(-1); return; }
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
  if (!game || !game.dragMode) return;
  game.dragMode = null;
  afterChange();
}

/** Notiz (Pfote) per langem Drücken / Rechtsklick / Taste N. */
function toggleNote(i) {
  if (!game || game.finished) return;
  if (game.kind === 'tutorial') { tutorialRefuse(i); return; }
  if (game.marks[i] !== MARK_EMPTY) {
    board.wobble(i, 0.15);
    toast('Notizen gehen nur auf leeren Feldern 🐾');
    return;
  }
  hideHintBubble();
  lastTap.cell = -1;
  snapshot();
  const on = !game.notes[i];
  game.notes[i] = on ? 1 : 0;
  board.setNote(i, on);
  sfx.note(on);
  vibrate(on ? 20 : 8);
  announce(`${cellName(i)}: ${on ? 'Notiz gesetzt' : 'Notiz entfernt'}.`);
  renderTools();
  saveCurrent();
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
  const show = !livesOn() && data.settings.conflicts;
  board.setConflicts(show ? [...conflictCells()] : []);
}

function afterChange() {
  if (!game) return;
  renderCatBar();
  updateConflicts();
  renderTools();
  saveCurrent();
  checkWin();
  if (game.kind === 'tutorial' && !game.finished) tutorialCheck();
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

// ---------------------------------------------------------------------------
// Sieg
// ---------------------------------------------------------------------------

function celebrateBoard(g) {
  setTimeout(() => {
    if (game !== g) return;
    board.celebrate();
    sfx.win();
    vibrate([30, 60, 30, 60, 80]);
    retrigger($('#cat-bar'), 'party');
  }, CAT_ANIM_MS - 80);
}

function win() {
  game.finished = true;
  board.locked = true;
  pauseTimer();
  hideHintBubble();
  board.clearHint();
  setKbCursor(-1);
  const g = game;
  const { level } = game;
  const time = currentElapsed();
  announce('Geschafft! Alle Katzen sitzen richtig.');

  if (game.kind === 'tutorial') {
    hideCoach();
    hideHand();
    data.tutorialSeen = true;
    persist();
    celebrateBoard(g);
    setTimeout(() => {
      if (game !== g) return;
      openModal({
        title: 'Tutorial geschafft! 🎓',
        dismissible: false,
        body: `
          <div class="win-cat">${catFaceSvg(skinById(selectedSkin()))}</div>
          <p>Super, du kennst jetzt die wichtigsten Tricks. Noch ein paar Tipps:</p>
          <ul class="tip-list">
            <li><span class="inline-ico">${I.ICON_PAW}</span> <b>Lange drücken</b> setzt eine Notiz – „hier vielleicht eine Katze“.</li>
            <li><span class="inline-ico">${I.ICON_BULB}</span> Steckst du fest, erklärt dir der <b>Hinweis</b> den nächsten Schritt.</li>
            <li><span class="inline-ico">${I.ICON_MOUSE}</span> Die <b>Maus</b> findet Fehler – und kostet nichts, wenn alles stimmt.</li>
            <li><span class="inline-ico">${I.ICON_CALENDAR}</span> Jeden Tag gibt es ein neues <b>Tägliches Rätsel</b>.</li>
          </ul>`,
        buttons: [
          { label: 'Zum Menü', onClick: showMenu },
          { label: `Level ${nextLevelId() || 1} spielen`, primary: true, onClick: () => startLevel(nextLevelId() || 1) },
        ],
      });
    }, CAT_ANIM_MS + 1700);
    return;
  }

  const slips = game.mistakes + game.helpersUsed;
  const stars = slips === 0 ? 3 : slips <= 2 ? 2 : 1;
  const pointsBefore = totalPoints();
  const skinsBefore = unlockedSkins();
  let points;
  let prevTime = null;
  let rewardHint = false;
  let streakBefore = 0;
  let streakAfter = 0;

  if (game.kind === 'daily') {
    const base = 120 + Math.round(level.score * 1.5);
    points = Math.round(base * STAR_MULT[stars]);
    streakBefore = streakNow().current;
    if (!data.daily.solved[game.day]) {
      data.daily.solved[game.day] = { time, stars, points };
      rewardHint = true;
    }
    streakAfter = streakInfo(data.daily.solved, today()).current;
  } else {
    const base = 60 + level.id * 6 + Math.round(level.score * 1.5);
    points = Math.round(base * STAR_MULT[stars]);
    const prev = data.best[level.id];
    prevTime = prev?.time != null && Number.isFinite(prev.time) ? prev.time : null;
    rewardHint = stars === 3 && (!prev || prev.stars < 3);
    data.best[level.id] = {
      stars: Math.max(stars, prev?.stars || 0),
      points: Math.max(points, prev?.points || 0),
      time: Math.min(time, prevTime ?? Infinity),
    };
    data.unlocked = Math.max(data.unlocked, Math.min(LEVELS.length, level.id + 1));
  }
  if (rewardHint) data.hints += 1;
  data.history.push({ key: game.key, kind: game.kind, n: level.n, time, stars, day: today() });
  if (data.history.length > HISTORY_MAX) data.history = data.history.slice(-HISTORY_MAX);
  data.current = null;
  persist();
  const newSkins = takeNewSkins().filter((s) => !skinsBefore.includes(s.id));
  const newBest = prevTime != null && time < prevTime - 0.5;

  celebrateBoard(g);
  setTimeout(() => { if (game === g) updateHud(pointsBefore); }, CAT_ANIM_MS);

  const isLast = game.kind === 'level' && level.id === LEVELS.length;
  setTimeout(() => {
    if (game !== g) return;
    const badges = [];
    if (newBest) badges.push(`<span class="win-badge best">${I.ICON_STAR} Neue Bestzeit!</span>`);
    else if (prevTime != null) badges.push(`<span class="win-badge">Bestzeit ${formatTime(prevTime)}</span>`);
    if (rewardHint) badges.push(`<span class="win-badge hint"><span class="inline-ico">${I.ICON_BULB}</span> +1 Hinweis</span>`);
    const streakHtml = game.kind === 'daily' ? `
      <div class="win-streak">
        <span class="flame">${I.ICON_FLAME}</span>
        <b id="win-streak">${streakBefore}</b>
        <small>${streakAfter === 1 ? 'Tag' : 'Tage'} in Folge</small>
      </div>
      <div class="week small">${weekView(data.daily.solved, today()).map((d) => `<span class="day ${d.state}"><i>${d.state === 'done' ? I.ICON_CHECK : ''}</i><small>${d.label}</small></span>`).join('')}</div>` : '';
    const skinHtml = newSkins.map((s) => `
      <div class="new-skin">
        <span class="skin-face">${catFaceSvg(s)}</span>
        <div><small>Neue Katze freigeschaltet!</small><b>${s.name}</b></div>
        <button class="btn btn-secondary btn-small" data-skin="${s.id}">Auswählen</button>
      </div>`).join('');
    const wrap = openModal({
      title: isLast ? 'Meisterkatze! 🏆' : game.kind === 'daily' ? 'Tagesrätsel gelöst!' : 'Geschafft!',
      className: 'modal-win',
      dismissible: false,
      body: `
        <div class="win-cat">${catFaceSvg(skinById(selectedSkin()))}</div>
        <div class="win-stars">${[1, 2, 3].map((k) => `<i class="${k <= stars ? 'on' : ''}" style="--d:${0.25 + k * 0.28}s">${I.ICON_STAR}</i>`).join('')}</div>
        <div class="win-stats">
          <div><small>Punkte</small><b id="win-points">+0</b></div>
          <div><small>Zeit</small><b>${formatTime(time)}</b></div>
          <div><small>Fehler</small><b>${game.mistakes}</b></div>
        </div>
        ${badges.length ? `<div class="win-badges">${badges.join('')}</div>` : ''}
        ${streakHtml}
        ${skinHtml}
        ${stars < 3 ? '<p class="muted">Ohne Fehler und ohne Helfer gibt es 3 Sterne.</p>' : ''}
        ${isLast ? '<p>Du hast alle 100 Level gelöst – unglaublich!</p>' : ''}`,
      buttons: game.kind === 'daily'
        ? [
          { label: 'Menü', onClick: showMenu },
          ...(nextLevelId() ? [{ label: `Weiter: Level ${nextLevelId()}`, primary: true, onClick: () => startLevel(nextLevelId()) }] : []),
        ]
        : [
          { label: 'Levelauswahl', onClick: showMenu },
          ...(isLast ? [] : [{ label: `Weiter: Level ${level.id + 1}`, primary: true, onClick: () => startLevel(level.id + 1) }]),
        ],
    });
    wrap.querySelectorAll('[data-skin]').forEach((b) => b.addEventListener('click', () => {
      selectSkin(b.dataset.skin);
      b.textContent = 'Ausgewählt ✓';
      b.disabled = true;
      wrap.querySelector('.win-cat').innerHTML = catFaceSvg(skinById(b.dataset.skin));
      retrigger(wrap.querySelector('.win-cat'), 'picked');
    }));
    // Sterne einzeln mit Ton, danach Punkte hochzählen, Serie und neue Katzen
    for (let k = 0; k < stars; k++) setTimeout(() => { sfx.star(k); vibrate(12); }, (0.25 + (k + 1) * 0.28) * 1000 + 120);
    const pts = wrap.querySelector('#win-points');
    const afterStars = (0.25 + stars * 0.28) * 1000 + 350;
    setTimeout(() => {
      let n = 0;
      animateNumber(pts, 0, points, 900, () => { if (n++ % 3 === 0) sfx.tick(); });
      setTimeout(() => { pts.textContent = `+${points.toLocaleString('de-DE')}`; retrigger(pts, 'bump'); }, 950);
    }, afterStars);
    const streakEl = wrap.querySelector('#win-streak');
    if (streakEl && streakAfter !== streakBefore) {
      setTimeout(() => {
        streakEl.textContent = streakAfter;
        retrigger(wrap.querySelector('.win-streak'), 'flare');
        sfx.streak();
      }, afterStars + 1100);
    }
    if (newSkins.length) setTimeout(() => sfx.unlock(), afterStars + 1500);
  }, CAT_ANIM_MS + 1900);
}

function gameOver() {
  game.finished = true;
  board.locked = true;
  pauseTimer();
  sfx.lose();
  data.current = null;
  persist();
  const kind = game.kind;
  const id = game.level.id;
  const restart = () => (kind === 'daily' ? startDaily() : startLevel(id, { fresh: true }));
  openModal({
    title: 'Keine Fische mehr',
    dismissible: false,
    body: `<div class="lose-fish">${I.fishIcon(true)}${I.fishIcon(true)}${I.fishIcon(true)}</div><p>Die Katzen sind hungrig nach Hause gegangen. Versuch es gleich noch einmal!</p>`,
    buttons: [
      { label: 'Menü', onClick: showMenu },
      { label: 'Nochmal', primary: true, onClick: restart },
    ],
  });
}

// ---------------------------------------------------------------------------
// Helfer
// ---------------------------------------------------------------------------

function useJoker() {
  if (!game || game.finished) return;
  if (game.kind === 'tutorial') { tutorialRefuse(-1); return; }
  if (game.jokersLeft <= 0) { retrigger($('#tool-joker'), 'nope'); toast('Der Katzen-Joker ist für dieses Rätsel aufgebraucht.'); return; }
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
  if (game.kind === 'tutorial') { tutorialRefuse(-1); return; }
  if (!$('#hint-bubble').classList.contains('hidden')) { hideHintBubble(); return; }
  if (data.hints <= 0) {
    retrigger($('#tool-hint'), 'nope');
    toast('Keine Hinweise mehr. Neue gibt es für 3 Sterne in neuen Leveln und für jedes Tägliche Rätsel.');
    return;
  }
  const h = computeHint(game.geo, game.level, game.marks);
  if (!h.action) { toast(h.text); return; }
  data.hints -= 1;
  game.helpersUsed += 1;
  persist();
  sfx.hint();
  retrigger($('#tool-hint'), 'used');
  $('#tool-hint').classList.add('active');
  board.setHint(h);
  showHintBubble(h);
  renderTools();
  saveCurrent();
}

/** Die Maus sucht Fehler: falsche X und (ohne Fische) falsche Katzen. */
function findMistakes() {
  const out = [];
  game.marks.forEach((m, i) => {
    if (m === MARK_CAT && !game.solution.has(i)) out.push(i);
    if (isX(m) && game.solution.has(i)) out.push(i);
  });
  return out;
}

function useMouse() {
  if (!game || game.finished) return;
  if (game.kind === 'tutorial') { tutorialRefuse(-1); return; }
  if (board.mouseRun) return;
  hideHintBubble();
  retrigger($('#tool-mouse'), 'used');
  const errors = findMistakes();
  const g = game;
  board.locked = true;
  sfx.scurry();
  if (errors.length) {
    snapshot();
    game.helpersUsed += 1;
  }
  board.runMouse(errors, {
    onArrive: (c) => {
      if (game !== g) return;
      sfx.squeak();
      vibrate(12);
      if (game.marks[c] === MARK_CAT) removeCat(c);
      else setMark(c, MARK_EMPTY);
    },
    onDone: () => {
      if (game !== g) return;
      board.locked = false;
      if (errors.length) {
        const msg = errors.length === 1 ? 'Die Maus hat einen Fehler gefunden und weggeknabbert.' : `Die Maus hat ${errors.length} Fehler gefunden und weggeknabbert.`;
        toast(`🐭 ${msg}`);
        announce(msg);
      } else {
        toast('🐭 Kein Fehler gefunden – stark! (Das war gratis.)');
        announce('Kein Fehler gefunden.');
        sfx.hint();
      }
      afterChange();
    },
  });
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
        if (game.notes[c]) { game.notes[c] = 0; board.setNote(c, false); }
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
  if (game?.kind !== 'tutorial') board?.clearHint();
}

function undo() {
  if (!game || game.finished || !game.history.length || game.kind === 'tutorial') return;
  hideHintBubble();
  lastTap.cell = -1;
  const prev = game.history.pop();
  game.marks = prev.marks;
  board.setMarks(prev.marks);
  prev.notes.forEach((v, i) => { if (!!v !== !!game.notes[i]) board.setNote(i, !!v); });
  game.notes = prev.notes;
  sfx.clear();
  announce('Rückgängig.');
  afterChange();
}

function restart() {
  if (!game || game.finished || game.kind === 'tutorial') return;
  if (!game.marks.some((m) => m !== MARK_EMPTY) && !game.notes.some((v) => v)) return;
  hideHintBubble();
  snapshot();
  game.marks.forEach((_, i) => setMark(i, MARK_EMPTY));
  game.notes.forEach((v, i) => { if (v) { game.notes[i] = 0; board.setNote(i, false); } });
  afterChange();
  toast('Spielfeld geleert – „Rückgängig“ holt alles zurück.');
}

// ---------------------------------------------------------------------------
// Tutorial
// ---------------------------------------------------------------------------

function showCoach({ title, text, button = null, onButton = null, step = null }) {
  const el = $('#coach');
  el.querySelector('.coach-title').textContent = title;
  el.querySelector('.coach-text').textContent = text;
  el.querySelector('.coach-step').textContent = step ? `Schritt ${step}` : '';
  const btn = el.querySelector('#coach-next');
  btn.classList.toggle('hidden', !button);
  btn.textContent = button || '';
  btn.onclick = () => { sfx.pop(); onButton?.(); };
  el.classList.remove('hidden');
  retrigger(el, 'in');
  announce(`${title}. ${text}`);
}

function hideCoach() {
  $('#coach').classList.add('hidden');
}

function placeHand() {
  const hand = $('#tut-hand');
  if (!board || hand.classList.contains('hidden') || hand.dataset.cell === undefined) return;
  const pos = board.cellScreenPos(Number(hand.dataset.cell));
  const canvas = board.canvas;
  hand.style.left = `${canvas.offsetLeft + pos.x}px`;
  hand.style.top = `${canvas.offsetTop + pos.y}px`;
}

function showHand(cell, mode) {
  const hand = $('#tut-hand');
  hand.dataset.cell = cell;
  hand.classList.remove('hidden', 'tap', 'double');
  void hand.offsetWidth;
  hand.classList.add(mode);
  placeHand();
}

function hideHand() {
  $('#tut-hand').classList.add('hidden');
}

function endTutorialUi() {
  tut = null;
  hideCoach();
  hideHand();
  $('#screen-game').classList.remove('tutorial-mode');
}

function tutorialNext() {
  if (!game || game.kind !== 'tutorial' || game.finished) return;
  const h = computeHint(game.geo, game.level, game.marks);
  if (!h.action) return;
  tut.step = h;
  tut.stepNo += 1;
  board.setHint(h);
  showCoach({ title: tutorialTitle(h), text: `${h.text} ${tutorialAction(h)}`, step: tut.stepNo });
  pointHand();
}

function pointHand() {
  const h = tut?.step;
  if (!h) return;
  if (h.action.type === 'cat') showHand(h.action.cells[0], 'double');
  else {
    const next = h.action.cells.find((c) => game.marks[c] === MARK_EMPTY);
    if (next !== undefined) showHand(next, 'tap');
  }
}

/** Nach jeder Aktion prüfen, ob der aktuelle Tutorial-Schritt erledigt ist. */
function tutorialCheck() {
  const h = tut?.step;
  if (!h) return;
  if (h.action.type === 'cat') {
    if (game.marks[h.action.cells[0]] !== MARK_CAT) { pointHand(); return; }
    tut.step = null;
    hideHand();
    board.clearHint();
    const g = game;
    setTimeout(() => {
      if (game !== g || game.finished) return;
      if (!tut.shownAutoX) {
        tut.shownAutoX = true;
        showCoach({ ...TUTORIAL_AUTO_X, button: 'Weiter', onButton: tutorialNext });
      } else tutorialNext();
    }, CAT_ANIM_MS + 500);
  } else {
    const open = h.action.cells.filter((c) => game.marks[c] === MARK_EMPTY);
    if (open.length) { pointHand(); return; }
    tut.step = null;
    hideHand();
    board.clearHint();
    const g = game;
    setTimeout(() => { if (game === g && !game.finished) tutorialNext(); }, 450);
  }
}

// ---------------------------------------------------------------------------
// Tastatur
// ---------------------------------------------------------------------------

function setKbCursor(i) {
  kbCursor = i;
  board?.setCursor(i);
}

function handleBoardKey(ev) {
  if (!game || game.finished || $('#modal-root').childElementCount) return false;
  const n = game.level.n;
  const key = ev.key;
  const move = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] }[key];
  if (move) {
    let cur = kbCursor >= 0 ? kbCursor : Math.floor(n / 2) * n + Math.floor(n / 2);
    if (kbCursor >= 0) {
      const r = Math.min(n - 1, Math.max(0, Math.floor(cur / n) + move[0]));
      const c = Math.min(n - 1, Math.max(0, (cur % n) + move[1]));
      cur = r * n + c;
    }
    setKbCursor(cur);
    sfx.tick();
    announce(`${cellName(cur)}, ${cellStateName(cur)}.`);
    return true;
  }
  if (kbCursor < 0) return false;
  const i = kbCursor;
  if (key === ' ' || key.toLowerCase() === 'x') {
    lastTap.cell = -1;
    handleTap(i, -1e9);
    return true;
  }
  if (key === 'Enter' || key.toLowerCase() === 'k') {
    if (!tutorialAllows(i, 'tap')) { tutorialRefuse(i); return true; }
    if (game.marks[i] === MARK_CAT) {
      snapshot();
      removeCat(i);
    } else {
      snapshot();
      placeCat(i);
    }
    afterChange();
    return true;
  }
  if (key.toLowerCase() === 'n') {
    toggleNote(i);
    return true;
  }
  return false;
}

// ---------------------------------------------------------------------------
// Start
// ---------------------------------------------------------------------------

function buildStaticUi() {
  $('#btn-back').innerHTML = I.ICON_BACK;
  document.querySelectorAll('.btn-settings').forEach((b) => { b.innerHTML = I.ICON_GEAR; });
  $('#hud-flame').innerHTML = I.ICON_FLAME;
  $('#rule-1 .ico').innerHTML = I.miniGrid('xxxxc.x..');
  $('#rule-2 .ico').innerHTML = I.miniGrid('xcx.x..x.');
  $('#rule-3 .ico').innerHTML = I.miniGrid('xxxxcxxxx');
  $('#tool-hint .ico').innerHTML = I.ICON_BULB;
  $('#tool-mouse .ico').innerHTML = I.ICON_MOUSE;
  $('#btn-undo').insertAdjacentHTML('afterbegin', I.ICON_UNDO);
  $('#btn-restart').insertAdjacentHTML('afterbegin', I.ICON_RESTART);
  $('#btn-rules').innerHTML = I.ICON_HELP;
  $('#btn-stats').insertAdjacentHTML('afterbegin', `<span class="chip-ico">${I.ICON_CHART}</span>`);
  $('#btn-tutorial').insertAdjacentHTML('afterbegin', `<span class="chip-ico">${I.ICON_CAP}</span>`);
  refreshSkinIcons();

  $('#btn-back').addEventListener('click', showMenu);
  document.querySelectorAll('.btn-settings').forEach((b) => b.addEventListener('click', showSettings));
  $('#btn-rules').addEventListener('click', () => showRules());
  $('#btn-skins').addEventListener('click', () => { sfx.pop(); showSkins(); });
  $('#btn-stats').addEventListener('click', () => { sfx.pop(); showStats(); });
  $('#btn-tutorial').addEventListener('click', () => { sfx.pop(); startTutorial(); });
  $('#btn-hints-info').addEventListener('click', () => {
    sfx.pop();
    toast(`Du hast ${data.hints} ${data.hints === 1 ? 'Hinweis' : 'Hinweise'}. +1 für 3 Sterne in einem neuen Level und für jedes Tägliche Rätsel.`);
  });
  $('#btn-continue').addEventListener('click', () => { const id = nextLevelId(); if (id) startLevel(id); });
  $('#tool-joker').addEventListener('click', useJoker);
  $('#tool-hint').addEventListener('click', useHint);
  $('#tool-mouse').addEventListener('click', useMouse);
  $('#btn-undo').addEventListener('click', undo);
  $('#btn-restart').addEventListener('click', restart);
  $('#hint-close').addEventListener('click', hideHintBubble);
  $('#rules-card').addEventListener('click', () => showRules());
  $('#coach-skip').addEventListener('click', () => {
    data.tutorialSeen = true;
    persist();
    showMenu();
  });

  document.addEventListener('keydown', (ev) => {
    if (!game || $('#screen-game').classList.contains('hidden')) return;
    if (ev.key === 'Escape') {
      if ($('#modal-root').childElementCount) closeModal();
      else hideHintBubble();
      return;
    }
    if ((ev.ctrlKey || ev.metaKey) && ev.key.toLowerCase() === 'z') { ev.preventDefault(); undo(); return; }
    if (ev.ctrlKey || ev.metaKey || ev.altKey) return;
    const t = ev.target;
    if (t && /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName)) return;
    // Leertaste/Enter auf einem Knopf gehören dem Knopf
    if (t && t.tagName === 'BUTTON' && (ev.key === ' ' || ev.key === 'Enter')) return;
    if (handleBoardKey(ev)) {
      ev.preventDefault();
      if (document.activeElement !== board?.canvas) board?.canvas.focus({ preventScroll: true });
    }
  });
}

buildStaticUi();
renderMenu(true);
if (!data.tutorialSeen) {
  // Erster Start: direkt ins interaktive Tutorial
  startTutorial();
}
