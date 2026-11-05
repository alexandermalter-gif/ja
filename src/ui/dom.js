// Kleine Oberflächen-Helfer: Toast, Screenreader-Ansage, Dialoge (mit Fokusfalle), Zahlen-Animation.
import { TOAST_MIN_MS, TOAST_MAX_MS } from '../game/timing.js';

export const $ = (sel) => document.querySelector(sel);

/** Emojis und Zierzeichen für die Sprachausgabe entfernen. */
export const plain = (s) => String(s).replace(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B50}\u{FE0F}]/gu, '').replace(/\s+/g, ' ').trim();

let liveTimer = 0;
/** Ansage für Screenreader (ohne Emojis). */
export function announce(msg) {
  const el = $('#sr-live');
  if (!el) return;
  el.textContent = '';
  clearTimeout(liveTimer);
  liveTimer = setTimeout(() => { el.textContent = plain(msg); }, 30);
}

let toastTimer = 0;
/** Kurze Meldung oben; Dauer nach Textlänge, Antippen schließt. Wird zugleich angesagt. */
export function toast(msg, { say = true } = {}) {
  const el = $('#toast');
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toastTimer);
  const ms = Math.min(TOAST_MAX_MS, Math.max(TOAST_MIN_MS, 1200 + msg.length * 45));
  toastTimer = setTimeout(hideToast, ms);
  if (say) announce(msg);
}

export function hideToast() {
  clearTimeout(toastTimer);
  $('#toast')?.classList.remove('show');
}

/** CSS-Animation neu starten. */
export function retrigger(el, cls) {
  if (!el) return;
  el.classList.remove(cls);
  void el.offsetWidth;
  el.classList.add(cls);
}

/** Zahl weich hochzählen; der letzte Schritt setzt immer den Endtext (finalText). */
export function animateNumber(el, from, to, dur = 900, { onStep, fmt = (v) => Math.round(v).toLocaleString('de-DE'), finalText } = {}) {
  const t0 = performance.now();
  let last = null;
  const stepFn = (now) => {
    const p = Math.max(0, Math.min(1, (now - t0) / dur));
    const e = 1 - Math.pow(1 - p, 3);
    const v = from + (to - from) * e;
    if (p >= 1) {
      el.textContent = finalText ?? fmt(to);
      onStep?.(true);
      return;
    }
    el.textContent = fmt(v);
    const shown = Math.round(v);
    if (shown !== last) { last = shown; onStep?.(false); }
    requestAnimationFrame(stepFn);
  };
  requestAnimationFrame(stepFn);
}

/** Schrift so weit verkleinern, dass der Text in seine Spalte passt. */
export function fitText(el, maxPx, minPx = 12) {
  if (!el || !el.clientWidth) return;
  let size = maxPx;
  el.style.fontSize = `${size}px`;
  while (el.scrollWidth > el.clientWidth + 0.5 && size > minPx) {
    size -= 1;
    el.style.fontSize = `${size}px`;
  }
}

export const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

// ---------------------------------------------------------------------------
// Dialoge
// ---------------------------------------------------------------------------

const hooks = { onOpen: null, onClose: null };
let current = null; // { wrap, dismissible, opener, onClose }
let lastPointer = false;
document.addEventListener('pointerdown', () => { lastPointer = true; }, true);
document.addEventListener('keydown', () => { lastPointer = false; }, true);

/** Wurde zuletzt mit der Tastatur bedient? */
export const usingKeyboard = () => !lastPointer;

/** Fokus setzen, ohne zu scrollen; Ring nur bei Tastaturbedienung. */
export function focusQuiet(el) {
  focusEl(el);
}

/** main.js meldet sich hier an (Uhr anhalten/fortsetzen). */
export function setModalHooks(h) {
  Object.assign(hooks, h);
}

export const modalOpen = () => !!current;

const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

function focusables() {
  return current ? [...current.wrap.querySelectorAll(FOCUSABLE)].filter((el) => el.offsetParent !== null || el === document.activeElement) : [];
}

function primaryButton() {
  return current?.wrap.querySelector('.modal-buttons .btn-primary') || current?.wrap.querySelector('.modal-buttons .btn') || null;
}

function focusEl(el) {
  if (!el) return;
  try {
    el.focus({ preventScroll: true, focusVisible: !lastPointer });
  } catch {
    el.focus();
  }
}

/**
 * Öffnet einen Dialog. buttons: [{ label, primary, onClick, keepOpen }]
 * dismissible: Escape und Klick daneben schließen; sonst nur die Knöpfe.
 */
export function openModal({ title, body, buttons = [], dismissible = true, className = '', onClose = null }) {
  const opener = current ? current.opener : document.activeElement;
  closeModal({ silent: true });
  const root = $('#modal-root');
  const wrap = document.createElement('div');
  wrap.className = 'modal-backdrop';
  const tid = `modal-title-${Date.now()}`;
  wrap.innerHTML = `
    <div class="modal ${className}" role="dialog" aria-modal="true" aria-labelledby="${tid}">
      <h2 id="${tid}">${title}</h2>
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
  current = { wrap, dismissible, opener, onClose };
  for (const s of document.querySelectorAll('#app > .screen')) s.inert = true;
  requestAnimationFrame(() => wrap.classList.add('open'));
  focusEl(primaryButton() || focusables()[0]);
  hooks.onOpen?.();
  return wrap;
}

export function closeModal({ silent = false } = {}) {
  if (!current) return;
  const { opener, onClose } = current;
  current = null;
  $('#modal-root').innerHTML = '';
  for (const s of document.querySelectorAll('#app > .screen')) s.inert = false;
  if (silent) return;
  if (opener && opener.isConnected && typeof opener.focus === 'function') focusEl(opener);
  onClose?.();
  hooks.onClose?.();
}

/** Tastatur im Dialog: Tab bleibt im Dialog, Escape schließt nur, wenn erlaubt. true = verarbeitet. */
export function handleModalKey(ev) {
  if (!current) return false;
  if (ev.key === 'Escape') {
    ev.preventDefault();
    if (current.dismissible) closeModal();
    else focusEl(primaryButton());
    return true;
  }
  if (ev.key === 'Tab') {
    const list = focusables();
    if (!list.length) { ev.preventDefault(); return true; }
    const first = list[0];
    const last = list[list.length - 1];
    const inside = current.wrap.contains(document.activeElement);
    if (ev.shiftKey && (document.activeElement === first || !inside)) { ev.preventDefault(); focusEl(last); }
    else if (!ev.shiftKey && (document.activeElement === last || !inside)) { ev.preventDefault(); focusEl(first); }
    return true;
  }
  return false;
}
