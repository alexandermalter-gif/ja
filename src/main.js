// Katzen-Sudoku – Spielsteuerung und Oberfläche.
import { LEVELS } from './logic/levels.js';
import { PALETTE } from './logic/palette.js';
import { makeGeometry } from './logic/solver.js';
import { Board3D } from './game/board3d.js';
import { MARK_EMPTY, MARK_X, MARK_CAT, MARK_AUTO_X } from './game/marks.js';
import { computeHint } from './game/hints.js';
import {
  KEY, loadData, saveData, mergeData, pruneSaves, resetProgress, storageWorks, exportCode, importCode,
} from './game/storage.js';
import { sfx, setSoundEnabled, suspendAudio } from './game/audio.js';
import {
  dateKey, addDays, generateDaily, tableDaily, planFor, formatDate, streakInfo, bestStreak, weekView, DAILY_CACHE_VERSION,
} from './game/daily.js';
import {
  SKINS, skinById, isSkinUnlocked, unlockText, unlockProgress, catFaceSvg, nextMilestone,
} from './game/skins.js';
import { averageBySize, barChart, formatTime, formatLong } from './game/stats.js';
import {
  TUTORIAL_LEVEL, TUTORIAL_INTRO, TUTORIAL_AUTO_X, tutorialTitle, tutorialAction,
} from './game/tutorial.js';
import { TIERS, starsFor, pointsFor, tierFor } from './game/scoring.js';
import { Session, puzzleSig, isX } from './game/session.js';
import * as T from './game/timing.js';
import { TRICKS, trickFor, trickHtml } from './game/tricks.js';
import { FREE_SIZES } from './game/freeplay.js';
import { generateFreeAsync, generateDailyAsync } from './game/workerClient.js';
import {
  $, announce, toast, hideToast, retrigger, animateNumber, fitText, esc, openModal, closeModal, modalOpen,
  setModalHooks, handleModalKey, usingKeyboard, focusQuiet,
} from './ui/dom.js';
import * as I from './game/icons.js';

const FISH = 3;
const JOKERS_PER_LEVEL = 1;
const HISTORY_MAX = 60;
const ARCHIVE_DAYS = 30;

// ---------------------------------------------------------------------------
// Zustand
// ---------------------------------------------------------------------------

const loaded = loadData();
let data = loaded.data;
const canSave = storageWorks();
const webglOk = Board3D.supported();
let saveWarned = false;
let board = null;
let game = null;
let timerHandle = 0;
let lastTap = { cell: -1, time: 0, before: MARK_EMPTY };
let tut = null; // Zustand des Tutorials
let kbCursor = -1; // Tastatur-Cursor (−1 = aus)
let boardInView = true;
let midnightTimer = 0;
const dailyMem = new Map(); // erzeugte Tagesrätsel (auch Archiv) dieser Sitzung

setSoundEnabled(data.settings.sound);

// ---------------------------------------------------------------------------
// Hilfsfunktionen
// ---------------------------------------------------------------------------

function parseLevel(level) {
  const regions = [...level.regions].map((ch) => parseInt(ch, 36));
  return { ...level, regionArr: regions };
}

const today = () => dateKey();
const sum = (obj, f) => Object.values(obj).reduce((a, b) => a + (f(b) || 0), 0);

function totalPoints() {
  return sum(data.best, (b) => b.points) + sum(data.daily.solved, (b) => b.points) + sum(data.daily.late, (b) => b.points);
}

function totalStars() {
  return sum(data.best, (b) => b.stars);
}

const streakNow = () => streakInfo(data.daily.solved, today());

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

function unseenSkins() {
  return unlockedSkins().filter((id) => !data.skins.seen.includes(id));
}

function markSkinsSeen(ids = unlockedSkins()) {
  const fresh = ids.filter((id) => !data.skins.seen.includes(id));
  if (!fresh.length) return;
  data.skins.seen = [...data.skins.seen, ...fresh];
  persist();
}

function selectedSkin() {
  const id = data.skins.selected;
  return unlockedSkins().includes(id) ? id : 'smoking';
}

/** Gelöste Level plus die nächsten zwei sind offen. */
function isUnlocked(id) {
  return data.settings.unlockAll || id <= data.unlocked + 2;
}

const levelTier = (level) => tierFor(level);

function persist() {
  const ok = saveData(data);
  if (!ok && !saveWarned) {
    saveWarned = true;
    toast('Speichern klappt hier nicht – dein Fortschritt geht beim Schließen verloren.');
    renderMenuNote();
  }
  return ok;
}

function vibrate(pattern) {
  if (!data.settings.vibration) return;
  try { navigator.vibrate?.(pattern); } catch { /* egal */ }
}

/** Höhe aller Bedienelemente außer dem Brett → CSS-Variable --chrome-h (Brett so groß wie möglich). */
function measureChrome() {
  const scr = $('#screen-game');
  if (scr.classList.contains('hidden')) return;
  const card = scr.querySelector('.board-card');
  let h = 38; // Innenabstand von #app
  for (const el of scr.children) {
    if (el === card || el.offsetParent === null || getComputedStyle(el).position === 'fixed') continue;
    h += el.offsetHeight + 14; // + Abstand zwischen den Zeilen
  }
  if (!$('.tools').classList.contains('hidden') && $('.tools').offsetParent) h += 22; // Beschriftung unter den Helfern
  scr.style.setProperty('--chrome-h', `${Math.round(h)}px`);
}

function fitHud() {
  const base = Math.min(26, Math.max(18, window.innerWidth * 0.058));
  document.querySelectorAll('.hud .value:not(.stars-live)').forEach((el) => fitText(el, base, 12));
  document.querySelectorAll('.hud .label').forEach((el) => fitText(el, Math.min(15, base * 0.6), 10));
}
window.addEventListener('resize', () => { fitHud(); measureChrome(); });

function cellName(i) {
  if (!game) return '';
  const n = game.level.n;
  const color = PALETTE[game.level.colors[game.level.regionArr[i]]].name;
  return `Zeile ${Math.floor(i / n) + 1}, Spalte ${(i % n) + 1}, ${color}`;
}

function cellStateName(i) {
  const m = game.s.marks[i];
  if (m === MARK_CAT) return 'Katze';
  if (m === MARK_X) return 'X';
  if (m === MARK_AUTO_X) return 'automatisches X';
  return game.s.notes[i] ? 'Notiz' : 'leer';
}

const plural = (k, one, many) => `${k} ${k === 1 ? one : many}`;

// ---------------------------------------------------------------------------
// Fehler abfangen
// ---------------------------------------------------------------------------

let errorShownAt = 0;
let rebuildOffered = false;

function reportError(err) {
  console.error(err);
  const now = Date.now();
  if (now - errorShownAt < 15000) return;
  errorShownAt = now;
  if (game && board && !rebuildOffered && !modalOpen()) {
    rebuildOffered = true;
    openModal({
      title: 'Hoppla',
      body: '<p>Da ist etwas schiefgelaufen. Falls das Spielfeld hängt, baue es neu auf – dein Spielstand bleibt erhalten.</p>',
      buttons: [
        { label: 'Weiterspielen' },
        { label: 'Spielfeld neu aufbauen', primary: true, onClick: rebuildBoard },
      ],
    });
  } else {
    toast('Da ist etwas schiefgelaufen. Falls etwas hängt: zurück ins Menü und neu öffnen.');
  }
}

window.addEventListener('error', (ev) => {
  // harmlose Browsermeldung, kein Fehler im Spiel
  if (/ResizeObserver loop/.test(ev.message || '')) return;
  reportError(ev.error || ev.message);
});
window.addEventListener('unhandledrejection', (ev) => reportError(ev.reason));

function rebuildBoard() {
  if (!game) return;
  const p = game.p;
  leaveGame();
  try { board?.dispose(); } catch { /* egal */ }
  board?.canvas.remove();
  board = null;
  startPuzzle(p);
}

// ---------------------------------------------------------------------------
// Dialoge
// ---------------------------------------------------------------------------

setModalHooks({ onOpen: () => syncTimer(), onClose: () => syncTimer() });

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
      <p><b>Automatische X:</b> Sobald eine Katze sitzt, bekommen alle Felder, die sie blockiert, ein hellgraues X. Deine eigenen X sind dunkelgrau. Die hellgrauen verschwinden mit ihrer Katze.</p>
      <p><b>Fische:</b> Eine Katze an der falschen Stelle kostet einen Fisch. Sind alle drei weg, kannst du neu beginnen oder mit höchstens einem Stern weiterspielen (abschaltbar in den Einstellungen).</p>
      <p><b>Sterne:</b> 3 ★ ohne Fehler und ohne Helfer, 2 ★ bei höchstens zwei Patzern, sonst 1 ★. Oben im Spiel siehst du, wie viele Sterne gerade noch drin sind.</p>
      <p><b>Helfer:</b> <span class="inline-ico">${I.ICON_CAT_FACE}</span> Der Joker setzt eine richtige Katze (1× pro Rätsel). <span class="inline-ico">${I.ICON_BULB}</span> Der Hinweis erklärt den nächsten Schritt – neue Hinweise gibt es für das erste 3-Sterne-Ergebnis eines Levels und für jedes Tagesrätsel. <span class="inline-ico">${I.ICON_MOUSE}</span> Die Maus sucht Fehler und knabbert sie weg; findet sie keinen, kostet sie nichts.</p>
      <p><b>Tastatur:</b> Pfeiltasten bewegen den Rahmen, Leertaste setzt X, Enter setzt eine Katze, N setzt eine Notiz, Strg+Z macht rückgängig.</p>
    </div>
    <h3 class="tricks-title">Tricks</h3>
    <div class="tricks">${TRICKS.map(trickHtml).join('')}</div>`;
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
  const canVibrate = typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function';
  const wrap = openModal({
    title: 'Einstellungen',
    className: 'modal-wide',
    body: `
      ${toggle('sound', 'Töne', 'Soundeffekte beim Spielen')}
      ${canVibrate ? toggle('vibration', 'Vibration', 'Kurzes Brummen bei Katzen, X und Fehlern') : ''}
      ${toggle('lives', 'Fische (Leben)', 'Aus: Fehler kosten keine Fische, aber weiterhin Sterne')}
      ${toggle('conflicts', 'Konflikte zeigen', 'Ohne Fische: Katzen, die sich stören, rot umrahmen')}
      ${toggle('autoX', 'Automatische X', 'Felder, die eine Katze blockiert, bekommen von selbst ein X (im Tutorial immer an)')}
      ${toggle('patterns', 'Muster auf Farben', 'Jede Farbe bekommt ein eigenes Muster (Punkte, Streifen …) – hilft bei Farbschwäche')}
      ${toggle('borders', 'Farbgrenzen', 'Dunkle Linien zwischen den Farbflächen')}
      ${toggle('calm', 'Ruhige Animationen', 'Weniger Wackeln, Partikel und Konfetti')}
      ${toggle('unlockAll', 'Alle Level freischalten', 'Jedes Level sofort spielbar')}
      <div class="settings-actions">
        <button class="btn btn-secondary" id="set-rules">${I.ICON_HELP} Spielregeln</button>
        <button class="btn btn-secondary" id="set-export">Spielstand sichern</button>
        <button class="btn btn-secondary" id="set-import">Spielstand laden</button>
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
      if (inp.dataset.key === 'vibration' && inp.checked) vibrate(20);
    });
  });
  wrap.querySelector('#set-rules').addEventListener('click', () => showRules());
  wrap.querySelector('#set-export').addEventListener('click', showExport);
  wrap.querySelector('#set-import').addEventListener('click', showImport);
  wrap.querySelector('#set-reset').addEventListener('click', () => {
    openModal({
      title: 'Fortschritt löschen?',
      body: '<p>Alle Sterne, Punkte, Serien, Katzen und freigeschalteten Level gehen verloren. Deine Einstellungen bleiben.</p>',
      buttons: [
        { label: 'Abbrechen' },
        {
          label: 'Löschen',
          primary: true,
          onClick: () => {
            leaveGame({ save: false });
            data = resetProgress(data.settings);
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

function showExport() {
  const code = exportCode(data);
  const wrap = openModal({
    title: 'Spielstand sichern',
    className: 'modal-wide',
    body: `<p>Kopiere diesen Code und bewahre ihn auf. Unter „Spielstand laden“ holst du damit deinen Fortschritt zurück – auch auf einem anderen Gerät.</p>
      <textarea class="code-box" id="export-code" readonly rows="5" aria-label="Spielstand-Code">${esc(code)}</textarea>`,
    buttons: [
      { label: 'Schließen' },
      {
        label: 'Kopieren',
        primary: true,
        keepOpen: true,
        onClick: async () => {
          const ta = wrap.querySelector('#export-code');
          try {
            await navigator.clipboard.writeText(code);
            toast('Code kopiert');
          } catch {
            ta.focus();
            ta.select();
            toast('Code markiert – jetzt kopieren (Strg+C bzw. lange drücken).');
          }
        },
      },
    ],
  });
  wrap.querySelector('#export-code').addEventListener('focus', (ev) => ev.target.select());
}

function showImport() {
  const wrap = openModal({
    title: 'Spielstand laden',
    className: 'modal-wide',
    body: `<p>Füge einen gesicherten Code ein. Er wird mit deinem jetzigen Fortschritt zusammengeführt – es geht nichts verloren.</p>
      <textarea class="code-box" id="import-code" rows="5" placeholder="KS1:…" aria-label="Spielstand-Code einfügen"></textarea>
      <p class="form-error hidden" id="import-error" role="alert"></p>`,
    buttons: [
      { label: 'Abbrechen' },
      {
        label: 'Laden',
        primary: true,
        keepOpen: true,
        onClick: () => {
          const err = wrap.querySelector('#import-error');
          try {
            const imported = importCode(wrap.querySelector('#import-code').value);
            const merged = mergeData(data, imported);
            merged.hints = Math.max(data.hints, imported.hints);
            merged.lastLevel = data.lastLevel ?? imported.lastLevel;
            data = merged;
            persist();
            closeModal();
            applySettings();
            toast('Spielstand geladen');
          } catch (e) {
            err.textContent = e instanceof SyntaxError || /atob|decode|base64/i.test(String(e)) ? 'Der Code ist unvollständig oder beschädigt.' : e.message || 'Der Code konnte nicht gelesen werden.';
            err.classList.remove('hidden');
          }
        },
      },
    ],
  });
  wrap.querySelector('#import-code').focus();
}

function applySettings() {
  setSoundEnabled(data.settings.sound);
  document.documentElement.classList.toggle('calm', data.settings.calm);
  if (board) {
    board.setShowBorders(data.settings.borders);
    board.setPatterns(data.settings.patterns);
    board.setCalm(data.settings.calm);
  }
  if (game) {
    updateConflicts();
    renderFish();
    renderCatBar(true);
    renderStars();
    renderTools();
  }
  renderMenu();
}

// ---------------------------------------------------------------------------
// Menü
// ---------------------------------------------------------------------------

function weekHtml(week) {
  const stateText = { done: 'gelöst', missed: 'verpasst', today: 'heute', future: '' };
  return week.map((d) => `<span class="day ${d.state}" title="${d.key}"><i aria-hidden="true">${d.state === 'done' ? I.ICON_CHECK : ''}</i><small aria-hidden="true">${d.label}</small><span class="sr-only">${d.label}${stateText[d.state] ? `: ${stateText[d.state]}` : ''}</span></span>`).join('');
}

function renderDailyCard() {
  const key = today();
  const plan = planFor(key);
  const solved = data.daily.solved[key];
  const st = streakNow();
  const week = weekView(data.daily.solved, key);
  const inProgress = !!data.saves[`D${key}`];
  const cached = dailyMem.get(key) || (data.daily.cache?.key === key ? data.daily.cache.level : null);
  const tier = cached ? tierFor(cached) : plan.tier;
  const card = $('#daily-card');
  card.classList.toggle('solved', !!solved);
  const fewSolved = Object.keys(data.best).length < 10;
  card.innerHTML = `
    <div class="daily-top">
      <span class="daily-ico">${I.ICON_CALENDAR}</span>
      <div class="daily-title">
        <small>Tagesrätsel</small>
        <b>${formatDate(key)}</b>
        <span>${plan.n} × ${plan.n} · ${TIERS[tier].name}</span>
      </div>
      <div class="streak-badge${st.current ? ' on' : ''}" title="Tage in Folge gelöst" aria-label="Serie: ${plural(st.current, 'Tag', 'Tage')}">
        <span class="flame" aria-hidden="true">${I.ICON_FLAME}</span><b aria-hidden="true">${st.current}</b><small aria-hidden="true">${st.current === 1 ? 'Tag' : 'Tage'}</small>
      </div>
    </div>
    <div class="week" role="group" aria-label="Diese Woche">${weekHtml(week)}</div>
    <button class="btn ${solved ? 'btn-secondary' : 'btn-primary'} btn-daily" id="btn-daily">
      ${solved ? `${I.ICON_CHECK}<span>Gelöst in ${formatTime(solved.time)} – morgen gibt’s ein neues</span>` : `${I.ICON_PLAY}<span>${inProgress ? 'Weiterspielen' : 'Jetzt spielen'}</span>`}
    </button>
    ${!solved && !st.todayDone && st.current ? `<p class="daily-note">Löse heute, damit deine Serie von ${plural(st.current, 'Tag', 'Tagen')} weiterläuft!</p>` : ''}
    ${!solved && fewSolved && tier >= 2 ? '<p class="daily-hint">Heute eher knifflig – die ersten Level sind ein guter Einstieg.</p>' : ''}`;
  card.querySelector('#btn-daily').addEventListener('click', () => {
    // Datum beim Klick neu bestimmen (die Karte kann von gestern stammen)
    const now = today();
    if (now !== key) { renderDailyCard(); }
    if (data.daily.solved[now]) { retrigger(card, 'nope'); toast('Heute schon gelöst – morgen wartet ein neues Rätsel.'); return; }
    sfx.pop();
    startDaily();
  });
}

function scheduleMidnight() {
  clearTimeout(midnightTimer);
  const now = new Date();
  const next = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 0, 0, 5);
  midnightTimer = setTimeout(() => {
    if (!game) renderMenu();
    scheduleMidnight();
  }, Math.max(1000, next - now));
}

function renderMenuNote() {
  const el = $('#menu-note');
  if (!el) return;
  const notes = [];
  if (!webglOk) notes.push('Dein Browser kann leider kein 3D (WebGL) anzeigen – das Spielfeld lässt sich hier nicht öffnen. Versuche einen anderen Browser oder schalte die Hardware-Beschleunigung ein.');
  if (!canSave || saveWarned) notes.push('Fortschritt kann hier nicht gespeichert werden (z. B. im privaten Modus). Unter Einstellungen → Spielstand sichern kannst du ihn als Code mitnehmen.');
  el.innerHTML = notes.map((t) => `<p>${t}</p>`).join('');
  el.classList.toggle('hidden', !notes.length);
}

function renderMenu(animate = false) {
  $('#menu-points').textContent = totalPoints().toLocaleString('de-DE');
  $('#menu-stars').textContent = `${totalStars()} / ${LEVELS.length * 3}`;
  const solved = Object.keys(data.best).length;
  $('#menu-solved').textContent = `${solved} / ${LEVELS.length}`;
  renderDailyCard();
  renderMenuNote();

  const skins = unlockedSkins();
  const sel = skinById(selectedSkin());
  const unseen = unseenSkins().length;
  const ms = nextMilestone(progress());
  $('#logo-cat').innerHTML = catFaceSvg(sel);
  $('#btn-skins').innerHTML = `<span class="chip-ico">${catFaceSvg(sel, { wink: false })}</span><span class="chip-text">Katzen <b>${skins.length}/${SKINS.length}</b>${ms ? `<small>${ms.text}</small>` : ''}</span>${unseen ? '<span class="new-badge">Neu</span>' : ''}`;
  $('#btn-skins').setAttribute('aria-label', `Katzen: ${skins.length} von ${SKINS.length}${unseen ? ', neue Katze freigeschaltet' : ''}${ms ? `. ${ms.text}` : ''}`);
  $('#btn-hints-info').innerHTML = `<span class="chip-ico">${I.ICON_BULB}</span><span>Hinweise <b>${data.hints}</b></span>`;

  const next = nextLevelId();
  const cont = $('#btn-continue');
  if (next) {
    const resume = !!data.saves[`L${next}`];
    cont.innerHTML = `${I.ICON_PLAY}<span>${resume ? 'Weiterspielen' : 'Spielen'}: Level ${next}</span>`;
    cont.disabled = false;
  } else {
    cont.innerHTML = '<span>Alle 100 Level geschafft!</span>';
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
    const tiers = [...new Set(lv.map(levelTier))].sort().map((t) => TIERS[t].name);
    const tierText = tiers.length > 1 ? `${tiers[0]} – ${tiers[tiers.length - 1]}` : tiers[0];
    sec.innerHTML = `<h3><span>${n} × ${n}</span><small>${tierText} · Level ${lv[0].id}–${lv[lv.length - 1].id}</small></h3><div class="level-grid"></div>`;
    const grid = sec.querySelector('.level-grid');
    for (const l of lv) {
      const best = data.best[l.id];
      const unlocked = isUnlocked(l.id);
      const started = !!data.saves[`L${l.id}`];
      const tier = levelTier(l);
      const btn = document.createElement('button');
      btn.className = `level-btn tier-${tier}${best ? ' done' : ''}${unlocked ? '' : ' locked'}${next === l.id ? ' current' : ''}${started ? ' started' : ''}`;
      btn.style.setProperty('--tier', TIERS[tier].color);
      btn.style.setProperty('--i', Math.min(btnIndex++, 40));
      const hasTime = best?.time != null && Number.isFinite(best.time);
      btn.setAttribute('aria-label', `Level ${l.id}, ${TIERS[tier].name}${unlocked ? '' : ', gesperrt'}${best ? `, ${plural(best.stars, 'Stern', 'Sterne')}` : ''}${hasTime ? `, Bestzeit ${formatTime(best.time)}` : ''}${started ? ', angefangen' : ''}`);
      const stars = best ? [1, 2, 3].map((k) => `<i class="${k <= best.stars ? 'on' : ''}">${I.ICON_STAR}</i>`).join('') : '';
      btn.innerHTML = unlocked
        ? `<span class="num">${l.id}</span><span class="stars">${stars}</span>${hasTime ? `<span class="best-time">${formatTime(best.time)}</span>` : ''}${started ? '<span class="started-dot" aria-hidden="true"></span>' : ''}`
        : `<span class="lock">${I.ICON_LOCK}</span><span class="num small">${l.id}</span>`;
      btn.addEventListener('click', () => {
        if (!unlocked) { retrigger(btn, 'nope'); sfx.clear(); toast('Löse zuerst die vorherigen Level.'); return; }
        sfx.pop();
        startLevel(l.id);
      });
      grid.appendChild(btn);
    }
    groups.appendChild(sec);
  }
}

function nextLevelId() {
  const last = data.lastLevel;
  if (last && isUnlocked(last) && !data.best[last] && LEVELS[last - 1]) return last;
  for (const l of LEVELS) if (!data.best[l.id] && isUnlocked(l.id)) return l.id;
  return null;
}

function showMenu() {
  leaveGame();
  $('#screen-game').classList.add('hidden');
  $('#screen-menu').classList.remove('hidden');
  board?.pause();
  renderMenu(true);
  window.scrollTo({ top: 0 });
  pregenerate();
}

// --- Katzen-Sammlung ------------------------------------------------------------

function showSkins() {
  const p = progress();
  const sel = selectedSkin();
  const unseen = new Set(unseenSkins());
  const cards = SKINS.map((s) => {
    const open = isSkinUnlocked(s, p);
    const pct = Math.round(unlockProgress(s, p) * 100);
    return `<button class="skin-card${open ? '' : ' locked'}${s.id === sel ? ' selected' : ''}" data-id="${s.id}" ${open ? '' : 'aria-disabled="true"'}>
      <span class="skin-face">${catFaceSvg(s, { wink: false, locked: !open })}${open ? '' : `<span class="skin-lock">${I.ICON_LOCK}</span>`}</span>
      ${unseen.has(s.id) ? '<span class="new-badge">Neu</span>' : ''}
      <b>${s.name}</b>
      <small>${s.id === sel ? 'Ausgewählt' : open ? 'Antippen zum Auswählen' : unlockText(s)}</small>
      ${open ? '' : `<span class="skin-progress" role="img" aria-label="${pct} Prozent geschafft"><i style="width:${pct}%"></i></span>`}
    </button>`;
  }).join('');
  const wrap = openModal({
    title: 'Deine Katzen',
    className: 'modal-wide',
    body: `<p class="muted">Sterne, Serien und Tagesrätsel schalten neue Katzen frei. Die ausgewählte Katze sitzt auf dem Spielfeld.</p><div class="skin-grid">${cards}</div>`,
    buttons: [{ label: 'Fertig', primary: true }],
    onClose: () => renderMenu(),
  });
  markSkinsSeen();
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

// --- Statistik --------------------------------------------------------------------

function showStats() {
  const st = streakNow();
  const solvedLevels = Object.keys(data.best).length;
  const dailyCount = Object.keys(data.daily.solved).length;
  const lateCount = Object.keys(data.daily.late).length;
  const bySize = averageBySize(LEVELS, data.best);
  const hist = data.history.slice(-20);
  const fastest = Object.entries(data.best)
    .filter(([, b]) => b.time != null && Number.isFinite(b.time))
    .sort((a, b) => a[1].time - b[1].time)[0];
  const longest = bestStreak(data.daily.solved);
  const tile = (label, value) => `<div class="stat-tile"><small>${label}</small><b>${value}</b></div>`;
  const histTitle = (h) => (h.kind === 'daily' ? `Tagesrätsel ${h.day}` : h.kind === 'archive' ? 'Tagesrätsel (Archiv)' : h.kind === 'free' ? `Freies Spiel ${h.n}×${h.n}` : `Level ${h.key.slice(1)} (${h.n}×${h.n})`);
  openModal({
    title: 'Statistik',
    className: 'modal-wide',
    body: `
      <div class="stat-tiles">
        ${tile('Gelöste Level', `${solvedLevels}<span>/${LEVELS.length}</span>`)}
        ${tile('Sterne', `${totalStars()}<span>/${LEVELS.length * 3}</span>`)}
        ${tile('Punkte', totalPoints().toLocaleString('de-DE'))}
        ${tile('Tagesrätsel', lateCount ? `${dailyCount}<span> +${lateCount} nachgeholt</span>` : dailyCount)}
        ${tile('Serie', plural(st.current, 'Tag', 'Tage'))}
        ${tile('Längste Serie', plural(longest, 'Tag', 'Tage'))}
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
          ? barChart(hist.map((h) => ({ label: '', value: h.time, title: histTitle(h) })), { height: 120 })
          : '<p class="muted">Noch keine Lösungen – leg los!</p>'}
        ${hist.length ? '<p class="chart-foot">Hervorgehoben: deine schnellste Lösung dieser Reihe · rechts die neueste</p>' : ''}
      </section>
      <div class="stat-lines">
        ${fastest ? `<p>Schnellstes Level: <b>Level ${fastest[0]}</b> in <b>${formatTime(fastest[1].time)}</b></p>` : ''}
        ${data.totals.solves ? `<p>Gelöste Rätsel insgesamt: <b>${data.totals.solves}</b></p>` : ''}
        ${data.totals.time ? `<p>Gesamte Rätselzeit: <b>${formatLong(data.totals.time)}</b></p>` : ''}
      </div>`,
    buttons: [{ label: 'Fertig', primary: true }],
  });
}

// --- Archiv und Freies Spiel ------------------------------------------------------

function showArchive() {
  const t = today();
  const days = Array.from({ length: ARCHIVE_DAYS }, (_, k) => addDays(t, -(k + 1)));
  const items = days.map((key) => {
    const done = data.daily.solved[key] || data.daily.late[key];
    const started = !!data.saves[`A${key}`];
    const d = new Date(`${key}T12:00:00`);
    const label = d.toLocaleDateString('de-DE', { weekday: 'short', day: 'numeric', month: 'short' });
    const plan = planFor(key);
    return `<button class="archive-day${done ? ' done' : ''}${started ? ' started' : ''}" data-key="${key}" aria-label="${formatDate(key)}, ${plan.n} mal ${plan.n}${done ? ', gelöst' : started ? ', angefangen' : ''}">
      <b>${label}</b><small>${plan.n}×${plan.n}</small><i aria-hidden="true">${done ? I.ICON_CHECK : ''}</i>
    </button>`;
  }).join('');
  const wrap = openModal({
    title: 'Archiv',
    className: 'modal-wide',
    body: `<p class="muted">Hol die Tagesrätsel der letzten ${ARCHIVE_DAYS} Tage nach. Sie zählen für Punkte und Statistik, verändern aber deine Serie nicht.</p><div class="archive-grid">${items}</div>`,
    buttons: [{ label: 'Schließen', primary: true }],
  });
  wrap.querySelectorAll('.archive-day').forEach((b) => b.addEventListener('click', () => {
    sfx.pop();
    closeModal();
    startArchive(b.dataset.key);
  }));
}

function showFreePlay() {
  const lastN = Number(sessionStorageGet('free-n')) || 7;
  const lastT = Number(sessionStorageGet('free-t')) || 1;
  const sizeBtns = FREE_SIZES.map((n) => `<button class="seg-btn${n === lastN ? ' on' : ''}" data-n="${n}" aria-pressed="${n === lastN}">${n}×${n}</button>`).join('');
  const tierBtns = TIERS.map((t, k) => `<button class="seg-btn${k === lastT ? ' on' : ''}" data-t="${k}" aria-pressed="${k === lastT}" style="--tier:${t.color}">${t.name}</button>`).join('');
  const saved = data.saves.F;
  const wrap = openModal({
    title: 'Freies Spiel',
    className: 'modal-wide',
    body: `<p class="muted">Ein neues Zufallsrätsel nach Wahl – ohne Punkte, nur für den Spaß.</p>
      <h3 class="seg-title">Größe</h3><div class="seg" id="free-n">${sizeBtns}</div>
      <h3 class="seg-title">Schwierigkeit</h3><div class="seg" id="free-t">${tierBtns}</div>
      ${saved?.puzzle ? '<p class="muted">Ein angefangenes Rätsel wartet noch auf dich.</p>' : ''}`,
    buttons: [
      ...(saved?.puzzle ? [{ label: 'Weiterspielen', onClick: () => startPuzzle({ key: 'F', kind: 'free', level: saved.puzzle }) }] : []),
      {
        label: 'Rätsel erzeugen',
        primary: true,
        onClick: () => {
          const n = Number(wrap.querySelector('#free-n .on').dataset.n);
          const t = Number(wrap.querySelector('#free-t .on').dataset.t);
          sessionStorageSet('free-n', n);
          sessionStorageSet('free-t', t);
          startFree(n, t);
        },
      },
    ],
  });
  for (const id of ['#free-n', '#free-t']) {
    wrap.querySelectorAll(`${id} .seg-btn`).forEach((b) => b.addEventListener('click', () => {
      wrap.querySelectorAll(`${id} .seg-btn`).forEach((o) => { o.classList.toggle('on', o === b); o.setAttribute('aria-pressed', String(o === b)); });
      sfx.tick();
    }));
  }
}

function sessionStorageGet(k) {
  try { return sessionStorage.getItem(`${KEY}-${k}`); } catch { return null; }
}

function sessionStorageSet(k, v) {
  try { sessionStorage.setItem(`${KEY}-${k}`, String(v)); } catch { /* egal */ }
}

// ---------------------------------------------------------------------------
// Spiel starten
// ---------------------------------------------------------------------------

function ensureBoard() {
  if (board) return true;
  if (!webglOk) {
    toast('Dein Browser kann das 3D-Spielfeld leider nicht anzeigen.');
    return false;
  }
  try {
    board = new Board3D($('#board'), {
      onTap: handleTap,
      onDragStart: handleDragStart,
      onDragEnter: handleDragEnter,
      onDragEnd: handleDragEnd,
      onLongPress: toggleNote,
      onCatLand: (i, err) => { sfx.land(err); if (!err) vibrate(18); },
      onPointerUsed: () => setKbCursor(-1),
      onResize: placeHand,
      onError: reportError,
    });
    board.setCalm(data.settings.calm);
    const cv = board.canvas;
    cv.tabIndex = 0;
    cv.setAttribute('role', 'application');
    cv.setAttribute('aria-roledescription', 'Spielfeld');
    cv.setAttribute('aria-label', 'Spielfeld. Pfeiltasten bewegen den Rahmen, Leertaste setzt ein X, Enter eine Katze, N eine Notiz.');
    cv.addEventListener('focus', () => { if (kbCursor < 0 && game && !game.finished && cv.matches(':focus-visible')) handleBoardKey({ key: 'ArrowUp' }); });
    new IntersectionObserver((entries) => {
      boardInView = entries[entries.length - 1].isIntersecting;
      syncTimer();
    }, { threshold: 0.15 }).observe($('#board'));
    return true;
  } catch (err) {
    console.error(err);
    board = null;
    toast('Das Spielfeld konnte nicht erstellt werden (WebGL).');
    return false;
  }
}

function startLevel(id, opts = {}) {
  startPuzzle({ key: `L${id}`, kind: 'level', level: LEVELS[id - 1] }, opts);
}

/** Tagesrätsel holen: aus der Tabelle, dem Zwischenspeicher oder neu erzeugt (mit Ladeanzeige). */
async function loadDaily(key) {
  const t = tableDaily(key);
  if (t) return t;
  if (dailyMem.has(key)) return dailyMem.get(key);
  const c = data.daily.cache;
  if (c?.key === key && c.v === DAILY_CACHE_VERSION && c.level) {
    dailyMem.set(key, c.level);
    return c.level;
  }
  const loader = $('#loader');
  loader.classList.remove('hidden');
  try {
    const level = await (pregen.get(key) || generateDaily(key, null, generateDailyAsync));
    rememberDaily(key, level);
    return level;
  } catch (err) {
    console.error(err);
    toast('Das Tagesrätsel konnte nicht erstellt werden. Versuch es gleich noch einmal.');
    return null;
  } finally {
    pregen.delete(key);
    loader.classList.add('hidden');
  }
}

function rememberDaily(key, level) {
  dailyMem.set(key, level);
  if (key === today()) {
    data.daily.cache = { key, level, v: DAILY_CACHE_VERSION };
    persist();
  }
}

// Im Leerlauf vorbereiten: heutiges Tagesrätsel (falls nicht in der Tabelle) und das nächste freie Rätsel
const pregen = new Map();
let freeNext = null;
function idle(fn) {
  if (typeof requestIdleCallback === 'function') requestIdleCallback(fn, { timeout: 4000 });
  else setTimeout(fn, 1500);
}
function pregenerate() {
  idle(() => {
    const key = today();
    if (!tableDaily(key) && !dailyMem.has(key) && !(data.daily.cache?.key === key && data.daily.cache.v === DAILY_CACHE_VERSION) && !pregen.has(key)) {
      const p = generateDaily(key, null, generateDailyAsync);
      pregen.set(key, p);
      p.then((level) => { rememberDaily(key, level); pregen.delete(key); }, () => pregen.delete(key));
    }
    const n = Number(sessionStorageGet('free-n')) || 7;
    const t = Number(sessionStorageGet('free-t')) || 1;
    if (!freeNext || freeNext.n !== n || freeNext.t !== t) {
      const seed = (Date.now() ^ Math.floor(Math.random() * 0x7fffffff)) >>> 0;
      freeNext = { n, t, promise: generateFreeAsync(n, t, seed) };
      freeNext.promise.catch(() => { freeNext = null; });
    }
  });
}

async function startDaily({ fresh = false } = {}) {
  const key = today();
  const level = await loadDaily(key);
  if (!level) return;
  startPuzzle({ key: `D${key}`, kind: 'daily', level, day: key }, { fresh });
}

async function startArchive(key, { fresh = false } = {}) {
  const saved = data.saves[`A${key}`]?.puzzle;
  const level = saved || await loadDaily(key);
  if (!level) return;
  startPuzzle({ key: `A${key}`, kind: 'archive', level, day: key }, { fresh });
}

async function startFree(n, tier) {
  const loader = $('#loader');
  loader.classList.remove('hidden');
  try {
    let promise;
    if (freeNext && freeNext.n === n && freeNext.t === tier) {
      promise = freeNext.promise;
      freeNext = null;
    } else {
      const seed = (Date.now() ^ Math.floor(Math.random() * 0x7fffffff)) >>> 0;
      promise = generateFreeAsync(n, tier, seed);
    }
    const level = await promise;
    startPuzzle({ key: 'F', kind: 'free', level }, { fresh: true });
  } catch (err) {
    console.error(err);
    toast('Das Rätsel konnte nicht erzeugt werden. Versuch es noch einmal.');
  } finally {
    loader.classList.add('hidden');
  }
}

function startTutorial() {
  closeModal({ silent: true });
  startPuzzle({ key: 'T', kind: 'tutorial', level: TUTORIAL_LEVEL }, { fresh: true });
}

function dropSave(key) {
  if (!data.saves[key]) return;
  delete data.saves[key];
  data.deletedSaves = [...(data.deletedSaves || []), key];
}

/** Laufendes Rätsel verlassen: Uhr anhalten, speichern, Zeitgeber und Anzeigen abräumen. */
function leaveGame({ save = true } = {}) {
  if (!game) return;
  if (game.kind === 'tutorial' && !data.tutorialSeen) {
    data.tutorialSeen = true;
    persist();
  }
  pauseTimer();
  if (save) saveCurrent();
  game.sched.clear();
  endTutorialUi();
  hideHintBubble();
  board?.clearHint();
  board?.stopMouse();
  setKbCursor(-1);
  game = null;
}

const KIND_TITLE = { daily: 'Tagesrätsel', archive: 'Tagesrätsel', tutorial: 'Tutorial', free: 'Freies Spiel' };

function startPuzzle(p, { fresh = false } = {}) {
  if (!ensureBoard()) return;
  leaveGame();
  closeModal({ silent: true });
  const level = parseLevel(p.level);
  const geo = makeGeometry(level.n, level.regionArr);
  const n = level.n;
  if (fresh) dropSave(p.key);
  let saved = !fresh && p.kind !== 'tutorial' ? data.saves[p.key] : null;
  if (saved && saved.fish <= 0) saved = null; // Spiel war schon verloren → neu beginnen
  const s = new Session(level, geo, saved);
  if (!s.restored) saved = null;
  const tier = p.kind === 'tutorial' ? 0 : tierFor(level);
  game = {
    p,
    key: p.key,
    kind: p.kind,
    day: p.day || null,
    level,
    geo,
    s,
    sig: puzzleSig(level),
    tier,
    colorsHex: level.colors.map((k) => PALETTE[k].hex),
    fish: saved ? saved.fish : FISH,
    mistakes: saved ? saved.mistakes : 0,
    jokersLeft: saved ? saved.jokersLeft : JOKERS_PER_LEVEL,
    helpersUsed: saved ? saved.helpersUsed : 0,
    capStars: saved ? saved.capStars : 3,
    elapsed: saved ? saved.elapsed : 0,
    runningSince: 0,
    finished: false,
    busy: 0,
    lockUntil: performance.now() + T.START_LOCK_MS,
    dragMode: null,
    lastFound: new Set(),
    hint: null,
    autoXAt: new Map(),
    autoXToast: false,
    sched: new T.Scheduler(),
  };
  lastTap = { cell: -1, time: 0, before: MARK_EMPTY };

  $('#screen-menu').classList.add('hidden');
  $('#screen-game').classList.remove('hidden');
  $('#screen-game').classList.toggle('tutorial-mode', p.kind === 'tutorial');
  $('#rules-card').classList.toggle('compact', !(p.kind === 'tutorial' || (p.kind === 'level' && level.id <= 8)));
  window.scrollTo({ top: 0 });
  hideHintBubble();
  board.resume();
  board.setSkin(selectedSkin());
  board.setLevel({ n, regions: level.regionArr }, game.colorsHex, {
    borders: data.settings.borders,
    patterns: data.settings.patterns,
    paletteIdx: level.colors,
  });
  board.setMarks(s.marks, false);
  s.notes.forEach((v, i) => { if (v) board.setNote(i, true, false); });
  kbCursor = -1;
  syncLock();
  game.sched.after(T.START_LOCK_MS + 10, syncLock);

  // Kopfzeile je Spielart
  const tierInfo = TIERS[tier];
  if (p.kind === 'daily' || p.kind === 'archive') {
    $('#hud-level-label').textContent = p.kind === 'archive' ? 'Archiv' : 'Tagesrätsel';
    $('#hud-level').textContent = planFor(p.day).weekday.slice(0, 2);
  } else if (p.kind === 'tutorial') {
    $('#hud-level-label').textContent = 'Tutorial';
    $('#hud-level').textContent = '5×5';
  } else if (p.kind === 'free') {
    $('#hud-level-label').textContent = 'Frei';
    $('#hud-level').textContent = `${n}×${n}`;
  } else {
    $('#hud-level-label').textContent = 'Level';
    $('#hud-level').textContent = level.id;
  }
  $('#hud-diff').textContent = tierInfo.name;
  $('#hud-flame').style.color = tierInfo.color;
  renderStars(false);
  renderFish();
  renderCatBar(true);
  updateConflicts();
  renderTools();
  refreshSkinIcons();
  saveCurrent();
  syncTimer();
  fitHud();
  measureChrome();

  const title = KIND_TITLE[p.kind] || `Level ${level.id}`;
  announce(`${title}, ${n} mal ${n} Felder, ${tierInfo.name}.${s.restored ? ' Spielstand fortgesetzt.' : ''} Pfeiltasten bewegen den Rahmen, Leertaste setzt ein X, Enter eine Katze.`);

  if (p.kind === 'tutorial') {
    tut = { step: null, shownAutoX: false, stepNo: 0 };
    showCoach({ ...TUTORIAL_INTRO, button: 'Los geht’s', onButton: tutorialNext });
  } else if (!s.restored) {
    maybeShowTrick(level);
  }
}

/** Neuer Trick (Gruppen, Wenn-dann) zum ersten Mal nötig → kurze Erklärkarte. */
function maybeShowTrick(level) {
  if (!level.maxTech || level.maxTech < 4) return;
  const t = trickFor(level.maxTech);
  if (!t || data.techniquesSeen.includes(t.tech)) return;
  data.techniquesSeen.push(t.tech);
  persist();
  game.sched.after(700, () => {
    openModal({
      title: `Neuer Trick: ${t.name}`,
      className: 'modal-trick',
      body: `${trickHtml(t)}<p class="muted">Für dieses Rätsel brauchst du ihn wahrscheinlich. Der Hinweis (Glühbirne) zeigt ihn dir auch direkt auf dem Brett. Alle Tricks stehen in den Spielregeln.</p>`,
      buttons: [{ label: 'Probier’s aus', primary: true }],
    });
  });
}

// ---------------------------------------------------------------------------
// Kopfzeile und Leisten
// ---------------------------------------------------------------------------

const currentStars = () => starsFor(game.mistakes, game.helpersUsed, game.capStars);

/** Sterne, die gerade noch möglich sind (statt Punkte). */
function renderStars(animate = true) {
  if (!game) return;
  const el = $('#hud-stars');
  const stars = currentStars();
  const before = Number(el.dataset.stars || 3);
  el.dataset.stars = stars;
  el.innerHTML = [1, 2, 3].map((k) => `<i class="${k <= stars ? 'on' : ''}">${I.ICON_STAR}</i>`).join('');
  el.setAttribute('aria-label', `${plural(stars, 'Stern', 'Sterne')} möglich`);
  if (animate && stars < before) {
    retrigger(el, 'lose');
    announce(`Nur noch ${plural(stars, 'Stern', 'Sterne')} möglich.`);
  }
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
  setTimeout(() => ghost.remove(), 2000);
}

function livesOn() {
  return data.settings.lives && game?.kind !== 'tutorial';
}

function autoXOn() {
  return data.settings.autoX || game?.kind === 'tutorial';
}

function renderFish() {
  const el = $('#fish-bar');
  const lives = livesOn();
  el.classList.toggle('relaxed', !lives);
  const label = lives ? `${game.fish} von ${FISH} Fischen übrig` : 'Ohne Fische – Fehler kosten nur Sterne';
  el.title = label;
  el.setAttribute('role', 'img');
  el.setAttribute('aria-label', label);
  el.innerHTML = Array.from({ length: FISH }, (_, k) => I.fishIcon(lives && k >= game.fish)).join('');
}

function renderCatBar(initial = false) {
  const el = $('#cat-bar');
  const { level } = game;
  const found = game.s.foundRegions();
  if (initial || el.childElementCount !== level.n) {
    el.innerHTML = '';
    for (let k = 0; k < level.n; k++) {
      const span = document.createElement('span');
      span.className = 'cat-slot';
      span.style.setProperty('--k', k);
      el.appendChild(span);
    }
  }
  const pat = data.settings.patterns;
  [...el.children].forEach((span, k) => {
    const p = PALETTE[level.colors[k]];
    const f = found.has(k);
    span.innerHTML = I.catHeadIcon(p.hex, f, pat ? level.colors[k] : null);
    span.title = `${p.name}${f ? ': Katze gefunden' : ''}`;
    if (f && !game.lastFound.has(k) && !initial) {
      game.sched.after(T.CAT_LAND_MS, () => retrigger(span, 'pop'));
    }
  });
  el.setAttribute('role', 'img');
  el.setAttribute('aria-label', `${found.size} von ${level.n} Farben haben eine Katze`);
  game.lastFound = found;
}

/** Würde ein weiterer Helfer einen Stern kosten? */
function helperCostsStar() {
  return game.kind !== 'tutorial' && starsFor(game.mistakes, game.helpersUsed + 1, game.capStars) < currentStars();
}

function renderTools() {
  if (!game) return;
  const j = $('#tool-joker .badge');
  const h = $('#tool-hint .badge');
  const hv = String(data.hints);
  if (j.textContent !== String(game.jokersLeft)) retrigger(j, 'bump');
  if (h.textContent !== hv) retrigger(h, 'bump');
  j.textContent = game.jokersLeft;
  h.textContent = hv;
  const busy = isBusy();
  const cost = helperCostsStar();
  $('#tool-joker').classList.toggle('empty', game.jokersLeft <= 0);
  $('#tool-hint').classList.toggle('empty', data.hints <= 0);
  for (const id of ['#tool-joker', '#tool-hint', '#tool-mouse']) $(id).classList.toggle('busy', busy);
  $('#tool-joker').classList.toggle('costs', cost && game.jokersLeft > 0);
  $('#tool-hint').classList.toggle('costs', cost && data.hints > 0);
  const costText = cost ? ' Kostet einen Stern.' : '';
  $('#tool-joker').setAttribute('aria-label', `Joker, ${game.jokersLeft} übrig – setzt eine richtige Katze.${game.jokersLeft ? costText : ''}`);
  $('#tool-hint').setAttribute('aria-label', `Hinweis, ${data.hints} übrig – erklärt den nächsten Schritt.${data.hints ? costText : ''}`);
  $('#tool-mouse').setAttribute('aria-label', 'Maus – sucht Fehler und knabbert sie weg. Kostet nur etwas, wenn sie einen Fehler findet.');
  $('#btn-undo').disabled = game.s.history.length === 0 || game.kind === 'tutorial';
}

function saveCurrent() {
  if (!game || game.finished || game.kind === 'tutorial') return;
  const s = game.s;
  const elapsed = currentElapsed();
  if (s.isEmpty() && elapsed < 15 && !game.mistakes && !game.helpersUsed && game.jokersLeft === JOKERS_PER_LEVEL) {
    dropSave(game.key);
  } else {
    data.saves[game.key] = {
      key: game.key,
      sig: game.sig,
      marks: Array.from(s.marks),
      notes: Array.from(s.notes),
      fish: game.fish,
      mistakes: game.mistakes,
      jokersLeft: game.jokersLeft,
      helpersUsed: game.helpersUsed,
      capStars: game.capStars,
      elapsed,
      updatedAt: Date.now(),
      puzzle: game.kind === 'free' || game.kind === 'archive' ? game.p.level : null,
    };
  }
  if (game.kind === 'level') data.lastLevel = game.level.id;
  pruneSaves(data, today());
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
  if (game) $('#timer').textContent = formatTime(currentElapsed());
}

/** Die Uhr läuft nur, wenn das Rätsel sichtbar ist und kein Dialog offen ist. */
function syncTimer() {
  const run = game && !game.finished && !$('#screen-game').classList.contains('hidden')
    && !document.hidden && boardInView && !modalOpen();
  if (run) resumeTimer();
  else pauseTimer();
}

document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    syncTimer();
    saveCurrent();
    board?.pause();
    suspendAudio();
  } else {
    if (game && !$('#screen-game').classList.contains('hidden')) board?.resume();
    syncTimer();
    if (!game) renderDailyCard();
  }
});
window.addEventListener('focus', () => { if (!game) renderDailyCard(); });
window.addEventListener('pagehide', () => saveCurrent());

// Zweiter Tab: gespeicherten Stand übernehmen und zusammenführen
window.addEventListener('storage', (ev) => {
  if (ev.key !== KEY || !ev.newValue) return;
  const { data: fresh } = loadData();
  const merged = mergeData(data, fresh);
  merged.hints = fresh.hints;
  data = merged;
  if (!game) renderMenu();
});

// ---------------------------------------------------------------------------
// Sperre während Animationen
// ---------------------------------------------------------------------------

function isBusy() {
  return !game || !board || game.finished || game.busy > 0 || !!board.mouseRun || performance.now() < game.lockUntil;
}

function syncLock() {
  if (!board || !game) return;
  board.locked = isBusy();
  renderTools();
}

function setBusy(delta) {
  game.busy = Math.max(0, game.busy + delta);
  syncLock();
}

function nope(el) {
  retrigger(el, 'nope');
  sfx.clear();
}

// ---------------------------------------------------------------------------
// Markierungen
// ---------------------------------------------------------------------------

function setMark(i, m, animate = true) {
  const s = game.s;
  s.marks[i] = m;
  game.autoXAt.delete(i);
  board.setCell(i, m, animate);
  if (m !== MARK_EMPTY && s.notes[i]) {
    s.notes[i] = 0;
    board.setNote(i, false);
  }
}

/**
 * Markiert alle von Katzen blockierten Felder mit (automatischen) X –
 * als Welle, die sich von der jeweiligen Katze aus ausbreitet.
 */
function autoMark(onlyFrom = null) {
  const s = game.s;
  const sources = onlyFrom !== null ? [onlyFrom] : s.catCells();
  // Bei einer frisch gesetzten Katze startet die Welle genau beim Aufprall.
  const base = onlyFrom !== null ? T.CAT_LAND_MS + T.WAVE_START_MS : 60;
  const delays = s.autoXTargets(sources, base, T.WAVE_MS);
  const g = game;
  const now = performance.now();
  for (const [c, d] of delays) {
    s.marks[c] = MARK_AUTO_X;
    g.autoXAt.set(c, now + d);
    board.setCell(c, MARK_AUTO_X, true, d);
    if (s.notes[c]) {
      s.notes[c] = 0;
      g.sched.after(d, () => { if (!g.s.notes[c]) board.setNote(c, false); });
    }
  }
  if (delays.size) sfx.wave([...delays.values()].map((d) => d - base));
  return delays.size;
}

/** Entfernt automatische X, die keine Katze mehr blockiert (Welle zurück zur Katze). */
function cleanupAutoX(origin = -1) {
  const s = game.s;
  for (const i of s.staleAutoX()) {
    s.marks[i] = MARK_EMPTY;
    game.autoXAt.delete(i);
    const d = origin >= 0 ? Math.max(0, 260 - s.dist(origin, i) * 25) : 0;
    board.setCell(i, MARK_EMPTY, true, d);
  }
}

function placeCat(i, byHelper = false) {
  const s = game.s;
  const wrong = !byHelper && game.kind !== 'tutorial' && !s.solution.has(i);
  if (wrong && livesOn()) { wrongCat(i); return; }
  if (wrong) game.mistakes += 1; // ohne Fische: zählt still als Fehler (Sterne)
  setMark(i, MARK_CAT);
  sfx.cat();
  if (autoXOn()) autoMark(i);
  announce(`${cellName(i)}: Katze gesetzt.`);
}

/**
 * Falsche Katze bei eingeschalteten Fischen: Im Spielstand steht sofort ein X,
 * nur die Anzeige zeigt die Katze kurz (landen, Kopfschütteln, verpuffen).
 */
function wrongCat(i) {
  const g = game;
  const s = g.s;
  if (s.notes[i]) { s.notes[i] = 0; board.setNote(i, false, false); }
  s.marks[i] = MARK_X;
  board.setCell(i, MARK_CAT);
  board.flashError(i);
  sfx.drop();
  g.fish -= 1;
  g.mistakes += 1;
  const lost = g.fish;
  setBusy(+1);
  saveCurrent();
  g.sched.after(T.CAT_LAND_MS, () => {
    sfx.error();
    vibrate([60, 40, 90]);
    flyFish(lost);
    renderFish();
    renderStars();
    retrigger($('#fish-bar').children[lost], 'lose');
    retrigger($('#fish-bar'), 'hurt');
    toast(g.fish > 0 ? 'Hier passt keine Katze hin – ein Fisch ist weg!' : 'Oh nein, keine Fische mehr!');
  });
  g.sched.after(T.CAT_LAND_MS + T.ERROR_HOLD_MS, () => {
    board.poofCat(i);
    sfx.poof();
    g.sched.after(160, () => { if (s.marks[i] === MARK_X) board.setCell(i, MARK_X); });
    setBusy(-1);
    afterChange();
    if (g.fish <= 0) gameOver();
  });
}

function removeCat(i) {
  setMark(i, MARK_EMPTY);
  sfx.clear();
  cleanupAutoX(i);
  announce(`${cellName(i)}: Katze entfernt.`);
}

/** Automatisches X antippen: bleibt stehen, die blockierende Katze leuchtet kurz auf. */
function refuseAutoX(i) {
  const cats = game.s.blockingCats(i);
  if (!cats.length) return false;
  board.wobble(i, 0.2);
  cats.forEach((c, k) => { board.glowCell(c, 0.7, 520, k * 60); board.hopCell(c, 0.1, 300, k * 60); });
  sfx.clear();
  vibrate(8);
  if (!game.autoXToast) {
    game.autoXToast = true;
    toast('Dieses X kommt von einer Katze. Nimm die Katze weg, dann verschwindet es.');
  } else announce('Automatisches X, gehört zu einer Katze.');
  return true;
}

// --- Tutorial-Schranke ---------------------------------------------------------

/** Im Tutorial sind nur die gerade erklärten Züge erlaubt. kind: 'tap' | 'cat' */
function tutorialAllows(i, kind) {
  if (game?.kind !== 'tutorial') return true;
  const step = tut?.step;
  if (!step) return false;
  if (step.action.type === 'cat') return i === step.action.cells[0];
  if (step.action.type === 'x') return kind === 'tap' && step.action.cells.includes(i) && game.s.marks[i] === MARK_EMPTY;
  return false;
}

function tutorialRefuse(i) {
  if (i >= 0) board.wobble(i, 0.2);
  retrigger($('#coach'), 'nudge');
  sfx.clear();
  if (tut?.step) pointHand(true);
}

/**
 * Tippen: leer → X, eigenes X → leer, Katze → weg.
 * Doppeltippen: Katze setzen (der erste Tipp wird dabei ersetzt).
 */
function handleTap(i, timeStamp) {
  if (isBusy()) return;
  if (!tutorialAllows(i, 'tap')) { tutorialRefuse(i); return; }
  hideHintBubble();
  const s = game.s;
  // Zeitpunkt des Ereignisses (nicht der Verarbeitung) – robust auch auf langsamen Geräten
  const now = timeStamp ?? performance.now();
  if (lastTap.cell === i && now - lastTap.time < T.DOUBLE_TAP_MS) {
    const before = lastTap.before;
    lastTap.cell = -1;
    // Doppeltippen auf eine Katze: der erste Tipp hat sie schon entfernt.
    if (before !== MARK_CAT) {
      placeCat(i);
      afterChange();
    }
    return;
  }
  const cur = s.marks[i];
  if (cur === MARK_AUTO_X && refuseAutoX(i)) { lastTap.cell = -1; return; }
  lastTap = { cell: i, time: now, before: cur };
  s.snapshot();
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
  if (isBusy()) return;
  if (game.kind === 'tutorial') { tutorialRefuse(-1); return; }
  hideHintBubble();
  lastTap.cell = -1;
  game.s.snapshot();
  game.dragMode = game.s.marks[start] === MARK_X ? 'erase' : 'mark';
  game.dragChain = 0;
  applyDrag(start);
}

function handleDragEnter(i) {
  if (!game || game.finished || !game.dragMode) return;
  applyDrag(i);
}

function applyDrag(i) {
  const m = game.s.marks[i];
  if (game.dragMode === 'mark' && m === MARK_EMPTY) { setMark(i, MARK_X); sfx.mark(1 + game.dragChain++); vibrate(5); }
  if (game.dragMode === 'erase' && m === MARK_X) { setMark(i, MARK_EMPTY); sfx.clear(); vibrate(4); }
}

function handleDragEnd() {
  if (!game || !game.dragMode) return;
  game.dragMode = null;
  afterChange();
}

/** Notiz (Pfote) per langem Drücken / Rechtsklick / Taste N. */
function toggleNote(i) {
  if (isBusy()) return;
  if (game.kind === 'tutorial') { tutorialRefuse(i); return; }
  const s = game.s;
  if (s.marks[i] !== MARK_EMPTY) {
    board.wobble(i, 0.15);
    toast('Notizen gehen nur auf leeren Feldern.');
    return;
  }
  hideHintBubble();
  lastTap.cell = -1;
  s.snapshot();
  const on = !s.notes[i];
  s.notes[i] = on ? 1 : 0;
  board.setNote(i, on);
  sfx.note(on);
  vibrate(on ? 20 : 8);
  announce(`${cellName(i)}: ${on ? 'Notiz gesetzt' : 'Notiz entfernt'}.`);
  renderTools();
  saveCurrent();
}

function updateConflicts() {
  if (!game || !board) return;
  const show = !livesOn() && data.settings.conflicts;
  board.setConflicts(show ? [...game.s.conflictCells()] : []);
}

function afterChange() {
  if (!game) return;
  renderCatBar();
  updateConflicts();
  updateHintProgress();
  renderStars();
  renderTools();
  saveCurrent();
  if (game.s.isSolved()) win();
  else if (game.kind === 'tutorial' && !game.finished) tutorialCheck();
}

// ---------------------------------------------------------------------------
// Sieg und Niederlage
// ---------------------------------------------------------------------------

function celebrateBoard(g) {
  g.sched.after(T.CAT_ANIM_MS - 80, () => {
    board.celebrate();
    sfx.win();
    vibrate([30, 60, 30, 60, 80]);
    retrigger($('#cat-bar'), 'party');
  });
}

function win() {
  const g = game;
  g.finished = true;
  pauseTimer();
  syncLock();
  hideHintBubble();
  clearHintState();
  setKbCursor(-1);
  const { level } = g;
  const time = currentElapsed();
  announce('Geschafft! Alle Katzen sitzen richtig.');

  if (g.kind === 'tutorial') {
    hideCoach();
    hideHand();
    data.tutorialSeen = true;
    persist();
    celebrateBoard(g);
    g.sched.after(T.CAT_ANIM_MS + 1700, () => {
      const next = nextLevelId() || 1;
      openModal({
        title: 'Tutorial geschafft!',
        dismissible: false,
        body: `
          <div class="win-cat">${catFaceSvg(skinById(selectedSkin()))}</div>
          <p>Super, du kennst jetzt die wichtigsten Tricks. Noch ein paar Tipps:</p>
          <ul class="tip-list">
            <li><span class="inline-ico">${I.fishIcon(false)}</span> Eine Katze an der falschen Stelle kostet einen <b>Fisch</b>. Nach drei Fehlern kannst du neu beginnen oder mit einem Stern weiterspielen.</li>
            <li><span class="inline-ico">${I.ICON_STAR}</span> <b>3 Sterne</b> gibt es ohne Fehler und ohne Helfer. Oben im Spiel siehst du, wie viele noch drin sind.</li>
            <li><span class="inline-ico">${I.ICON_PAW}</span> <b>Lange drücken</b> setzt eine Notiz – „hier vielleicht eine Katze“.</li>
            <li><span class="inline-ico">${I.ICON_BULB}</span> Steckst du fest, erklärt dir der <b>Hinweis</b> den nächsten Schritt.</li>
            <li><span class="inline-ico">${I.ICON_MOUSE}</span> Die <b>Maus</b> findet Fehler – und kostet nichts, wenn alles stimmt.</li>
            <li><span class="inline-ico">${I.ICON_CALENDAR}</span> Jeden Tag gibt es ein neues <b>Tagesrätsel</b>.</li>
          </ul>`,
        buttons: [
          { label: 'Zum Menü', onClick: showMenu },
          { label: `Level ${next} spielen`, primary: true, onClick: () => startLevel(next) },
        ],
      });
    });
    return;
  }

  const stars = currentStars();
  const pointsBefore = totalPoints();
  const skinsBefore = unlockedSkins();
  let points = 0;
  let prevTime = null;
  let prevBest = null;
  let rewardHint = false;
  let streakBefore = 0;
  let streakAfter = 0;

  if (g.kind === 'daily') {
    points = pointsFor('daily', level, stars);
    streakBefore = streakNow().current;
    if (!data.daily.solved[g.day]) {
      data.daily.solved[g.day] = { time, stars, points };
      rewardHint = true;
    } else points = 0;
    streakAfter = streakNow().current;
  } else if (g.kind === 'archive') {
    points = pointsFor('daily', level, stars);
    if (!data.daily.solved[g.day] && !data.daily.late[g.day]) data.daily.late[g.day] = { time, stars, points };
    else points = 0;
  } else if (g.kind === 'level') {
    points = pointsFor('level', level, stars);
    prevBest = data.best[level.id] || null;
    prevTime = prevBest?.time != null && Number.isFinite(prevBest.time) ? prevBest.time : null;
    rewardHint = stars === 3 && (!prevBest || prevBest.stars < 3);
    data.best[level.id] = {
      stars: Math.max(stars, prevBest?.stars || 0),
      points: Math.max(points, prevBest?.points || 0),
      time: Math.min(time, prevTime ?? Infinity),
    };
    points = Math.max(0, data.best[level.id].points - (prevBest?.points || 0));
    data.unlocked = Math.max(data.unlocked, Math.min(LEVELS.length, level.id + 1));
  }
  if (rewardHint) data.hints += 1;
  data.history.push({ key: g.key, kind: g.kind, n: level.n, time, stars, day: today() });
  if (data.history.length > HISTORY_MAX) data.history = data.history.slice(-HISTORY_MAX);
  data.totals = { time: data.totals.time + time, solves: data.totals.solves + 1 };
  dropSave(g.key);
  persist();
  const newSkins = unlockedSkins().filter((id) => !skinsBefore.includes(id)).map(skinById);
  const newBest = prevTime != null && time < prevTime - 0.5;

  celebrateBoard(g);
  g.sched.after(T.CAT_ANIM_MS, () => renderMenuPointsBump(pointsBefore));

  const isLast = g.kind === 'level' && level.id === LEVELS.length;
  const replay = g.kind === 'level' && !!prevBest;
  const nextId = g.kind === 'level' && !replay && !isLast && isUnlocked(level.id + 1) ? level.id + 1 : nextLevelId();
  const retryReward = g.kind === 'level' && stars < 3 && (data.best[level.id]?.stars || 0) < 3;
  g.sched.after(T.CAT_ANIM_MS + T.WIN_DIALOG_MS, () => {
    markSkinsSeen(newSkins.map((s) => s.id));
    const badges = [];
    if (newBest) badges.push(`<span class="win-badge best">${I.ICON_STAR} Neue Bestzeit!</span>`);
    else if (prevTime != null) badges.push(`<span class="win-badge">Bestzeit ${formatTime(prevTime)}</span>`);
    if (rewardHint) badges.push(`<span class="win-badge hint"><span class="inline-ico">${I.ICON_BULB}</span> +1 Hinweis</span>`);
    if (g.capStars < 3) badges.push('<span class="win-badge">Weitergespielt: höchstens 1 Stern</span>');
    const streakHtml = g.kind === 'daily' ? `
      <div class="win-streak">
        <span class="flame" aria-hidden="true">${I.ICON_FLAME}</span>
        <b id="win-streak">${streakBefore}</b>
        <small>${streakAfter === 1 ? 'Tag' : 'Tage'} in Folge</small>
      </div>
      <div class="week small" role="group" aria-label="Diese Woche">${weekHtml(weekView(data.daily.solved, today()))}</div>` : '';
    const skinHtml = newSkins.map((s) => `
      <div class="new-skin">
        <span class="skin-face">${catFaceSvg(s)}</span>
        <div><small>Neu freigeschaltet!</small><b>${s.name}</b></div>
        <button class="btn btn-secondary btn-small" data-skin="${s.id}">Auswählen</button>
      </div>`).join('');
    const showPoints = g.kind !== 'free';
    const title = isLast ? 'Meisterkatze!' : g.kind === 'daily' || g.kind === 'archive' ? 'Tagesrätsel gelöst!' : 'Geschafft!';
    const buttons = [];
    if (g.kind === 'level') {
      buttons.push({ label: 'Levelauswahl', onClick: showMenu });
      if (stars < 3) buttons.push({ label: `Nochmal für ★★★${retryReward ? ' (+1 Hinweis)' : ''}`, onClick: () => startLevel(level.id, { fresh: true }) });
      if (nextId) buttons.push({ label: `Weiter: Level ${nextId}`, primary: true, onClick: () => startLevel(nextId) });
    } else if (g.kind === 'free') {
      buttons.push({ label: 'Menü', onClick: showMenu });
      buttons.push({ label: 'Neues Rätsel', primary: true, onClick: () => startFree(level.n, tierFor(level)) });
    } else {
      buttons.push({ label: 'Menü', onClick: showMenu });
      if (g.kind === 'archive') buttons.push({ label: 'Archiv', onClick: showArchive });
      const nl = nextLevelId();
      if (nl) buttons.push({ label: `Weiter: Level ${nl}`, primary: true, onClick: () => startLevel(nl) });
    }
    const wrap = openModal({
      title,
      className: 'modal-win',
      dismissible: false,
      body: `
        <div class="win-cat">${catFaceSvg(skinById(selectedSkin()))}</div>
        <div class="win-stars" role="img" aria-label="${plural(stars, 'Stern', 'Sterne')}">${[1, 2, 3].map((k) => `<i class="${k <= stars ? 'on' : ''}" style="--d:${0.25 + k * 0.28}s">${I.ICON_STAR}</i>`).join('')}</div>
        <div class="win-stats">
          ${showPoints ? '<div><small>Punkte</small><b id="win-points">+0</b></div>' : ''}
          <div><small>Zeit</small><b>${formatTime(time)}</b></div>
          <div><small>Fehler</small><b>${g.mistakes}</b></div>
          <div><small>Helfer</small><b>${g.helpersUsed}</b></div>
        </div>
        ${badges.length ? `<div class="win-badges">${badges.join('')}</div>` : ''}
        ${streakHtml}
        ${skinHtml}
        ${stars < 3 ? '<p class="muted">Ohne Fehler und ohne Helfer gibt es 3 Sterne.</p>' : ''}
        ${isLast ? '<p>Du hast alle 100 Level gelöst – unglaublich!</p>' : ''}`,
      buttons,
    });
    announce(`${title} ${plural(stars, 'Stern', 'Sterne')}, Zeit ${formatTime(time)}, ${plural(g.mistakes, 'Fehler', 'Fehler')}, ${plural(g.helpersUsed, 'Helfer', 'Helfer')}.${points ? ` Plus ${points} Punkte.` : ''}${rewardHint ? ' Plus ein Hinweis.' : ''}${newSkins.length ? ` Neue Katze: ${newSkins.map((s) => s.name).join(', ')}.` : ''}`);
    wrap.querySelectorAll('[data-skin]').forEach((b) => b.addEventListener('click', () => {
      selectSkin(b.dataset.skin);
      b.textContent = 'Ausgewählt ✓';
      b.disabled = true;
      wrap.querySelector('.win-cat').innerHTML = catFaceSvg(skinById(b.dataset.skin));
      retrigger(wrap.querySelector('.win-cat'), 'picked');
    }));
    // Sterne einzeln mit Ton, danach Punkte hochzählen, Serie und neue Katzen
    const sched = new T.Scheduler();
    const stop = () => sched.clear();
    wrap.querySelectorAll('.modal-buttons .btn').forEach((b) => b.addEventListener('click', stop));
    for (let k = 0; k < stars; k++) sched.after((0.25 + (k + 1) * 0.28) * 1000 + 120, () => { sfx.star(k); vibrate(12); });
    const pts = wrap.querySelector('#win-points');
    const afterStars = (0.25 + stars * 0.28) * 1000 + 350;
    if (pts) {
      sched.after(afterStars, () => {
        let n = 0;
        const final = `+${points.toLocaleString('de-DE')}`;
        animateNumber(pts, 0, points, 900, {
          fmt: (v) => `+${Math.round(v).toLocaleString('de-DE')}`,
          finalText: final,
          onStep: (done) => { if (done) retrigger(pts, 'bump'); else if (n++ % 3 === 0) sfx.tick(); },
        });
      });
    }
    const streakEl = wrap.querySelector('#win-streak');
    if (streakEl && streakAfter !== streakBefore) {
      sched.after(afterStars + 1100, () => {
        streakEl.textContent = streakAfter;
        retrigger(wrap.querySelector('.win-streak'), 'flare');
        sfx.streak();
      });
    }
    if (newSkins.length) sched.after(afterStars + 1500, () => sfx.unlock());
  });
}

/** Menüpunkte im Hintergrund aktualisieren (die Kopfzeile zeigt im Spiel Sterne). */
function renderMenuPointsBump(before) {
  const el = $('#menu-points');
  const total = totalPoints();
  if (total !== before) animateNumber(el, before, total, 1100);
}

function gameOver() {
  const g = game;
  g.finished = true;
  pauseTimer();
  syncLock();
  sfx.lose();
  const restart = () => {
    dropSave(g.key);
    persist();
    if (g.kind === 'daily') startDaily({ fresh: true });
    else startPuzzle(g.p, { fresh: true });
  };
  openModal({
    title: 'Keine Fische mehr',
    dismissible: false,
    body: `<div class="lose-fish">${I.fishIcon(true)}${I.fishIcon(true)}${I.fishIcon(true)}</div>
      <p>Die Katzen sind hungrig nach Hause gegangen. Du kannst neu beginnen – oder mit drei frischen Fischen weiterspielen, dann gibt es höchstens einen Stern.</p>`,
    buttons: [
      { label: 'Menü', onClick: () => { dropSave(g.key); persist(); showMenu(); } },
      { label: 'Neu starten', onClick: restart },
      { label: 'Weiterspielen', primary: true, onClick: continueAfterGameOver },
    ],
  });
}

function continueAfterGameOver() {
  if (!game) return;
  game.finished = false;
  game.fish = FISH;
  game.capStars = 1;
  renderFish();
  renderStars(false);
  syncLock();
  saveCurrent();
  syncTimer();
  toast('Drei frische Fische! Dieses Rätsel bringt jetzt höchstens einen Stern.');
}

// ---------------------------------------------------------------------------
// Helfer
// ---------------------------------------------------------------------------

function useJoker() {
  if (!game) return;
  if (game.kind === 'tutorial') { tutorialRefuse(-1); return; }
  if (isBusy()) { nope($('#tool-joker')); return; }
  if (game.jokersLeft <= 0) { nope($('#tool-joker')); toast('Der Joker ist für dieses Rätsel aufgebraucht.'); return; }
  const s = game.s;
  const cell = s.jokerCell();
  if (cell < 0) return;
  hideHintBubble();
  s.snapshot();
  // Falsche Katzen, die das Zielfeld blockieren, räumt der Joker zuerst weg.
  for (const c of s.blockingCats(cell)) if (!s.solution.has(c)) removeCat(c);
  retrigger($('#tool-joker'), 'used');
  placeCat(cell, true);
  game.jokersLeft -= 1;
  game.helpersUsed += 1;
  toast('Der Joker hat eine Katze gesetzt.');
  afterChange();
}

/** Noch offene Felder eines Hinweises. */
function hintRemaining(h) {
  if (!h?.action || !game) return [];
  const m = game.s.marks;
  if (h.action.type === 'cat') return m[h.action.cells[0]] === MARK_CAT ? [] : [h.action.cells[0]];
  if (h.action.type === 'x') return h.action.cells.filter((c) => m[c] === MARK_EMPTY);
  return [];
}

function clearHintState() {
  if (!game) return;
  game.hint = null;
  if (game.kind !== 'tutorial') board?.clearHint();
}

/** Nach jedem Zug: erledigte Felder aus der Hervorhebung nehmen, fertige Hinweise löschen. */
function updateHintProgress() {
  if (!game?.hint || game.kind === 'tutorial') return;
  const rem = hintRemaining(game.hint);
  if (!rem.length) { clearHintState(); hideHintBubble(); return; }
  board.trimHint(new Set(rem));
}

function useHint() {
  if (!game) return;
  if (game.kind === 'tutorial') { tutorialRefuse(-1); return; }
  if (isBusy()) { nope($('#tool-hint')); return; }
  if (!$('#hint-bubble').classList.contains('hidden')) { hideHintBubble(); return; }
  const s = game.s;
  // Fehler auf dem Brett: Die Maus findet alle auf einmal – das kostet keinen Hinweis.
  if (s.mistakes().length) {
    retrigger($('#tool-mouse'), 'suggest');
    toast('Da stimmt etwas nicht – schick die Maus los, sie findet alle Fehler auf einmal.');
    return;
  }
  // Schon bezahlter Hinweis gilt noch → gratis wieder zeigen
  if (game.hint && hintRemaining(game.hint).length) {
    showHint(game.hint);
    return;
  }
  let h = computeHint(game.geo, game.level, s.marks);
  if (h.kind === 'blocked' && autoXOn()) {
    // fehlende automatische X still nachtragen, dann den echten Hinweis zeigen
    s.snapshot();
    autoMark();
    afterChange();
    h = computeHint(game.geo, game.level, s.marks);
  }
  if (!h.action) { toast(h.text); return; }
  if (h.kind !== 'blocked') {
    if (data.hints <= 0) {
      nope($('#tool-hint'));
      toast('Keine Hinweise mehr. Neue gibt es für das erste 3-Sterne-Ergebnis eines Levels und für jedes Tagesrätsel.');
      return;
    }
    data.hints -= 1;
    game.helpersUsed += 1;
    persist();
  }
  sfx.hint();
  retrigger($('#tool-hint'), 'used');
  showHint(h);
  renderStars();
  renderTools();
  saveCurrent();
}

function showHint(h) {
  game.hint = h;
  const rem = new Set(hintRemaining(h));
  board.setHint({
    ...h,
    target: h.action.type === 'cat' && rem.size ? h.action.cells[0] : -1,
    eliminate: (h.eliminate || h.action.cells).filter((c) => h.action.type !== 'x' || rem.has(c)),
  });
  showHintBubble(h);
}

function showHintBubble(h) {
  const el = $('#hint-bubble');
  el.querySelector('p').textContent = h.text;
  // Liegen die markierten Felder unten, wandert die Blase nach oben.
  const n = game.level.n;
  const cells = [...(h.focus || []), ...(h.eliminate || []), ...(h.action?.cells || [])];
  const avgRow = cells.length ? cells.reduce((a, c) => a + Math.floor(c / n), 0) / cells.length : 0;
  el.classList.toggle('top', avgRow > (n - 1) / 2);
  const apply = el.querySelector('#hint-apply');
  apply.onclick = applyHint;
  el.classList.remove('hidden');
  retrigger(el, 'in');
  $('#tool-hint').classList.add('active');
  announce(h.text);
  if (usingKeyboard()) focusQuiet(apply);
}

function applyHint() {
  const h = game?.hint;
  hideHintBubble();
  if (!h || isBusy()) return;
  const rem = hintRemaining(h);
  if (!rem.length) return;
  const s = game.s;
  s.snapshot();
  if (h.action.type === 'cat') {
    placeCat(rem[0], true);
  } else {
    const g = game;
    rem.forEach((c, k) => {
      s.marks[c] = MARK_X;
      g.autoXAt.delete(c);
      if (s.notes[c]) { s.notes[c] = 0; board.setNote(c, false); }
      g.sched.after(k * 70, () => {
        if (s.marks[c] === MARK_X) { board.setCell(c, MARK_X); sfx.mark(1 + k); }
      });
    });
    announce(rem.length === 1 ? 'Ein X gesetzt.' : `${rem.length} X gesetzt.`);
  }
  afterChange();
}

function hideHintBubble() {
  const el = $('#hint-bubble');
  const hadFocus = el.contains(document.activeElement);
  el.classList.add('hidden');
  $('#tool-hint').classList.remove('active');
  if (hadFocus) focusQuiet($('#tool-hint'));
}

function useMouse() {
  if (!game) return;
  if (game.kind === 'tutorial') { tutorialRefuse(-1); return; }
  if (isBusy()) { nope($('#tool-mouse')); return; }
  hideHintBubble();
  retrigger($('#tool-mouse'), 'used');
  const g = game;
  const s = g.s;
  const errors = s.mistakes();
  sfx.scurry();
  if (errors.length) s.snapshot();
  let removed = 0;
  board.runMouse(errors, {
    onArrive: (c) => {
      if (game !== g || g.finished) return;
      const m = s.marks[c];
      // nur, wenn der Fehler noch da ist
      if (!((m === MARK_CAT && !s.solution.has(c)) || (m === MARK_X && s.solution.has(c)))) return;
      removed++;
      sfx.squeak();
      vibrate(12);
      if (m === MARK_CAT) removeCat(c);
      else setMark(c, MARK_EMPTY);
      renderCatBar();
    },
    onDone: () => {
      if (game !== g) return;
      if (removed) {
        g.helpersUsed += 1;
        toast(removed === 1 ? 'Die Maus hat einen Fehler gefunden und weggeknabbert.' : `Die Maus hat ${removed} Fehler gefunden und weggeknabbert.`);
      } else {
        toast('Kein Fehler gefunden – stark! (Das war gratis.)');
        sfx.hint();
      }
      syncLock();
      afterChange();
    },
  });
  syncLock();
}

function undo() {
  if (!game || game.kind === 'tutorial') return;
  if (isBusy()) { if (!game.finished) nope($('#btn-undo')); return; }
  const s = game.s;
  if (!s.history.length) return;
  hideHintBubble();
  clearHintState();
  lastTap.cell = -1;
  s.undo();
  game.autoXAt.clear();
  board.setMarks(s.marks);
  s.notes.forEach((v, i) => board.setNote(i, !!v));
  sfx.clear();
  announce('Rückgängig.');
  afterChange();
}

function clearBoard() {
  if (!game || game.kind === 'tutorial') return;
  if (isBusy()) { if (!game.finished) nope($('#btn-restart')); return; }
  const s = game.s;
  if (s.isEmpty()) return;
  hideHintBubble();
  clearHintState();
  s.snapshot();
  s.marks.forEach((_, i) => setMark(i, MARK_EMPTY));
  s.notes.forEach((v, i) => { if (v) { s.notes[i] = 0; board.setNote(i, false); } });
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
  measureChrome();
  // Panel unter dem Brett sichtbar machen, ohne das Brett aus dem Blick zu schieben
  requestAnimationFrame(() => el.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' }));
}

function hideCoach() {
  $('#coach').classList.add('hidden');
}

function placeHand() {
  const hand = $('#tut-hand');
  if (!hand || !board || hand.classList.contains('hidden') || hand.dataset.cell === undefined) return;
  const pos = board.cellScreenPos(Number(hand.dataset.cell));
  const canvas = board.canvas;
  hand.style.left = `${canvas.offsetLeft + pos.x}px`;
  hand.style.top = `${canvas.offsetTop + pos.y}px`;
}

function showHand(cell, mode) {
  const hand = $('#tut-hand');
  if (!hand) return;
  hand.dataset.cell = cell;
  hand.classList.remove('hidden', 'tap', 'double');
  void hand.offsetWidth;
  hand.classList.add(mode);
  placeHand();
}

function hideHand() {
  $('#tut-hand')?.classList.add('hidden');
}

function endTutorialUi() {
  tut = null;
  hideCoach();
  hideHand();
  $('#screen-game').classList.remove('tutorial-mode');
}

function tutorialNext() {
  if (!game || game.kind !== 'tutorial' || game.finished) return;
  const h = computeHint(game.geo, game.level, game.s.marks);
  if (!h.action) return;
  tut.step = h;
  tut.stepNo += 1;
  board.setHint(h);
  showCoach({ title: tutorialTitle(h), text: `${h.text} ${tutorialAction(h)}`, step: tut.stepNo });
  hideHand();
  // Die ersten Schritte zeigt die Hand sofort, später erst nach einer Denkpause.
  if (tut.stepNo <= 3) pointHand();
  else {
    const g = game;
    g.sched.after(5000, () => { if (tut?.step === h) pointHand(true); });
  }
}

function pointHand(now = false) {
  const h = tut?.step;
  if (!h) return;
  if (!now && tut.stepNo > 3 && $('#tut-hand').classList.contains('hidden')) return;
  if (h.action.type === 'cat') showHand(h.action.cells[0], 'double');
  else {
    const next = h.action.cells.find((c) => game.s.marks[c] === MARK_EMPTY);
    if (next !== undefined) showHand(next, 'tap');
  }
  scrollBoardIntoView();
}

function scrollBoardIntoView() {
  const cv = board?.canvas;
  if (!cv) return;
  const r = cv.getBoundingClientRect();
  if (r.top < 0 || r.bottom > window.innerHeight) cv.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

/** Nach jeder Aktion prüfen, ob der aktuelle Tutorial-Schritt erledigt ist. */
function tutorialCheck() {
  const h = tut?.step;
  if (!h) return;
  const g = game;
  if (h.action.type === 'cat') {
    if (g.s.marks[h.action.cells[0]] !== MARK_CAT) { pointHand(); return; }
    tut.step = null;
    hideHand();
    board.clearHint();
    g.sched.after(T.CAT_ANIM_MS + 500, () => {
      if (g.finished || !tut) return;
      if (!tut.shownAutoX) {
        tut.shownAutoX = true;
        showCoach({ ...TUTORIAL_AUTO_X, button: 'Weiter', onButton: tutorialNext });
      } else tutorialNext();
    });
  } else {
    const open = h.action.cells.filter((c) => g.s.marks[c] === MARK_EMPTY);
    if (open.length) { board.trimHint(new Set(open)); pointHand(); return; }
    tut.step = null;
    hideHand();
    board.clearHint();
    g.sched.after(450, () => { if (!g.finished && tut) tutorialNext(); });
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
  if (!game || game.finished || modalOpen() || !board) return false;
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
  const k = key.toLowerCase();
  const action = key === ' ' || k === 'x' || key === 'Enter' || k === 'k' || k === 'n';
  if (!action) return false;
  if (isBusy()) return true;
  if (key === ' ' || k === 'x') {
    lastTap.cell = -1;
    handleTap(i, -1e9);
    return true;
  }
  if (key === 'Enter' || k === 'k') {
    if (!tutorialAllows(i, 'cat')) { tutorialRefuse(i); return true; }
    hideHintBubble();
    const s = game.s;
    if (s.marks[i] === MARK_CAT) {
      s.snapshot();
      removeCat(i);
    } else if (s.marks[i] === MARK_AUTO_X && refuseAutoX(i)) {
      return true;
    } else {
      s.snapshot();
      placeCat(i);
    }
    afterChange();
    return true;
  }
  toggleNote(i);
  return true;
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
  $('#tool-mouse .badge').innerHTML = I.ICON_SEARCH;
  $('#btn-undo').insertAdjacentHTML('afterbegin', I.ICON_UNDO);
  $('#btn-restart').insertAdjacentHTML('afterbegin', I.ICON_RESTART);
  $('#btn-rules').innerHTML = I.ICON_HELP;
  $('#btn-stats').insertAdjacentHTML('afterbegin', `<span class="chip-ico">${I.ICON_CHART}</span>`);
  $('#btn-tutorial').insertAdjacentHTML('afterbegin', `<span class="chip-ico">${I.ICON_CAP}</span>`);
  $('#btn-archive').insertAdjacentHTML('afterbegin', `<span class="chip-ico">${I.ICON_ARCHIVE}</span>`);
  $('#btn-free').insertAdjacentHTML('afterbegin', `<span class="chip-ico">${I.ICON_DICE}</span>`);
  refreshSkinIcons();

  $('#btn-back').addEventListener('click', showMenu);
  document.querySelectorAll('.btn-settings').forEach((b) => b.addEventListener('click', showSettings));
  $('#btn-rules').addEventListener('click', () => showRules());
  $('#btn-skins').addEventListener('click', () => { sfx.pop(); showSkins(); });
  $('#btn-stats').addEventListener('click', () => { sfx.pop(); showStats(); });
  $('#btn-tutorial').addEventListener('click', () => { sfx.pop(); startTutorial(); });
  $('#btn-archive').addEventListener('click', () => { sfx.pop(); showArchive(); });
  $('#btn-free').addEventListener('click', () => { sfx.pop(); showFreePlay(); });
  $('#btn-hints-info').addEventListener('click', () => {
    sfx.pop();
    toast(`Du hast ${plural(data.hints, 'Hinweis', 'Hinweise')}. +1 für das erste 3-Sterne-Ergebnis eines Levels und für jedes Tagesrätsel.`);
  });
  $('#btn-continue').addEventListener('click', () => { const id = nextLevelId(); if (id) startLevel(id); });
  $('#tool-joker').addEventListener('click', useJoker);
  $('#tool-hint').addEventListener('click', useHint);
  $('#tool-mouse').addEventListener('click', useMouse);
  $('#btn-undo').addEventListener('click', undo);
  $('#btn-restart').addEventListener('click', clearBoard);
  $('#hint-close').addEventListener('click', hideHintBubble);
  $('#rules-card').addEventListener('click', () => showRules());
  $('#toast').addEventListener('click', hideToast);
  $('#coach-skip').addEventListener('click', () => {
    data.tutorialSeen = true;
    persist();
    showMenu();
  });

  document.addEventListener('keydown', (ev) => {
    if (handleModalKey(ev)) return;
    if (ev.key === 'Escape') {
      if (!$('#hint-bubble').classList.contains('hidden')) hideHintBubble();
      return;
    }
    if (!game || $('#screen-game').classList.contains('hidden')) return;
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

document.querySelector('#app')?.setAttribute('lang', 'de');
if (!document.documentElement.lang) document.documentElement.lang = 'de';
document.documentElement.classList.toggle('calm', data.settings.calm);
buildStaticUi();
renderMenu(true);
scheduleMidnight();
pregenerate();
if (loaded.recovered) toast('Dein Spielstand war beschädigt. Er wurde gesichert und neu angelegt.');
if (!canSave) toast('Speichern ist hier nicht möglich – dein Fortschritt geht beim Schließen verloren.');
if (!data.tutorialSeen && webglOk) {
  // Erster Start: direkt ins interaktive Tutorial – aber nur, wenn wir uns das merken können
  if (canSave) startTutorial();
  else showRules();
}
