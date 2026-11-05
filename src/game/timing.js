// Alle Animations- und Ablaufzeiten an einer Stelle, dazu ein Planer pro Spielsitzung,
// der seine Zeitgeber beim Levelwechsel geschlossen abräumt.
export { CAT_LAND_MS, CAT_ANIM_MS, LONG_PRESS_MS } from './board3d.js';

export const DOUBLE_TAP_MS = 380;
export const WAVE_MS = 45; // Verzögerung der X-Welle pro Feld Abstand
export const WAVE_START_MS = 30; // nach dem Aufprall der Katze
export const ERROR_HOLD_MS = 820; // so lange bleibt eine falsche Katze sichtbar
export const START_LOCK_MS = 350; // Brett nach dem Levelstart kurz sperren (Doppeltipp auf Menüknöpfe)
export const WIN_DIALOG_MS = 1900; // nach der Landung der letzten Katze
export const TOAST_MIN_MS = 2600;
export const TOAST_MAX_MS = 7000;
export const RECENT_AUTO_X_MS = 900; // so lange gilt ein Auto-X als „gerade erst gesetzt“

export class Scheduler {
  constructor() {
    this.ids = new Set();
  }

  after(ms, fn) {
    const id = setTimeout(() => {
      this.ids.delete(id);
      fn();
    }, ms);
    this.ids.add(id);
    return id;
  }

  clear() {
    for (const id of this.ids) clearTimeout(id);
    this.ids.clear();
  }
}
