// Katzen zum Sammeln: Fellfarben für das 3D-Modell, Gesichter für die Oberfläche und Freischaltregeln.

export const SKINS = [
  {
    id: 'smoking', name: 'Smoking', unlock: null,
    fur: '#2c2b31', muzzle: '#fbf8f4', blaze: true, ear: '#f2a4b5', sclera: '#ffffff', pupil: '#141418',
  },
  {
    id: 'tiger', name: 'Tiger', unlock: { stars: 10 },
    fur: '#e9933c', muzzle: '#fbe6c8', blaze: false, ear: '#f6b3a0', sclera: '#ffffff', pupil: '#2a2420',
    stripes: '#a9561a',
  },
  {
    id: 'nebel', name: 'Nebel', unlock: { stars: 30 },
    fur: '#8e98a6', muzzle: '#c8d0da', blaze: false, ear: '#e9b4c0', sclera: '#c6ee86', pupil: '#1b1d16',
  },
  {
    id: 'schnee', name: 'Schneeflocke', unlock: { stars: 60 },
    fur: '#f1eee9', muzzle: '#ffffff', blaze: false, ear: '#f7b6c4', sclera: '#9fd2ff', pupil: '#1a2433',
  },
  {
    id: 'siam', name: 'Siam', unlock: { stars: 100 },
    fur: '#efe0c6', muzzle: '#5b4033', blaze: false, ear: '#c9a08a', earOuter: '#5b4033', sclera: '#8cc7ff', pupil: '#172030',
  },
  {
    id: 'panther', name: 'Panther', unlock: { streak: 3 },
    fur: '#1b1b20', muzzle: '#26262c', blaze: false, ear: '#3d3138', sclera: '#f6d24a', pupil: '#111111', slit: true,
  },
  {
    id: 'glueck', name: 'Glückskatze', unlock: { daily: 7 },
    fur: '#fbf8f4', muzzle: '#ffffff', blaze: false, ear: '#f2a4b5', sclera: '#ffffff', pupil: '#141418',
    spots: [{ color: '#e9933c', side: -1 }, { color: '#2c2b31', side: 1 }],
  },
  {
    id: 'gold', name: 'Goldkatze', unlock: { stars: 200 },
    fur: '#e3b23c', muzzle: '#fff1c2', blaze: true, ear: '#f6cf73', sclera: '#ffffff', pupil: '#2b2210', metal: true,
  },
];

export const skinById = (id) => SKINS.find((s) => s.id === id) || SKINS[0];

/** progress: { stars, streak (beste Serie), daily (gelöste Tagesrätsel) } */
export function isSkinUnlocked(skin, progress) {
  const u = skin.unlock;
  if (!u) return true;
  if (u.stars) return progress.stars >= u.stars;
  if (u.streak) return progress.streak >= u.streak;
  if (u.daily) return progress.daily >= u.daily;
  return false;
}

export function unlockText(skin) {
  const u = skin.unlock;
  if (!u) return 'Von Anfang an dabei';
  if (u.stars) return `${u.stars} Sterne sammeln`;
  if (u.streak) return `${u.streak} Tage Serie beim Täglichen Rätsel`;
  if (u.daily) return `${u.daily} Tägliche Rätsel lösen`;
  return '';
}

/** Fortschritt 0..1 zur Freischaltung */
export function unlockProgress(skin, progress) {
  const u = skin.unlock;
  if (!u) return 1;
  if (u.stars) return Math.min(1, progress.stars / u.stars);
  if (u.streak) return Math.min(1, progress.streak / u.streak);
  if (u.daily) return Math.min(1, progress.daily / u.daily);
  return 0;
}

let uid = 0;

/** Gesicht einer Katze als SVG (gleicher Stil wie das Joker-Symbol). */
export function catFaceSvg(skin, { wink = true, locked = false } = {}) {
  const s = locked ? { ...skin, fur: '#cfc6c1', muzzle: '#e6dfdb', ear: '#ddd3ce', earOuter: null, sclera: '#ece6e2', pupil: '#bdb3ad', stripes: null, spots: null, blaze: false, metal: false } : skin;
  const earL = s.spots?.find((p) => p.side === -1)?.color || s.earOuter || s.fur;
  const earR = s.spots?.find((p) => p.side === 1)?.color || s.earOuter || s.fur;
  // eindeutige ID je Aufruf, sonst greifen versteckte Kopien auf denselben Verlauf zu
  const gid = `gold-${++uid}`;
  const gold = s.metal ? `<defs><linearGradient id="${gid}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#ffe9a3"/><stop offset=".5" stop-color="#e3b23c"/><stop offset="1" stop-color="#b8861b"/></linearGradient></defs>` : '';
  const fur = s.metal ? `url(#${gid})` : s.fur;
  const pupil = s.slit ? '<ellipse cx="34" cy="55" rx="1.8" ry="6.5" fill="PUP"/>' : '<ellipse cx="34" cy="55" rx="5" ry="6" fill="PUP"/>';
  const stripes = s.stripes
    ? `<path d="M50 25v10M42 27l2 9M58 27l-2 9" stroke="${s.stripes}" stroke-width="3.4" stroke-linecap="round"/><path d="M13 58l9 2M13 66l9-1M87 58l-9 2M87 66l-9-1" stroke="${s.stripes}" stroke-width="3" stroke-linecap="round"/>`
    : '';
  const spots = s.spots
    ? s.spots.map((p) => (p.side < 0
      ? `<path d="M15 40c4-14 18-18 28-14 4 8-2 20-14 22-8 1-14-1-14-8z" fill="${p.color}"/>`
      : `<path d="M85 40c-4-14-18-18-28-14-3 7 1 17 12 19 8 1 15 0 16-5z" fill="${p.color}"/>`)).join('')
    : '';
  const muzzle = s.blaze
    ? `<path d="M50 33c-6 0-8 14-13 22-11 3-15 21-2 29 8 5 22 5 30 0 13-8 9-26-2-29-5-8-7-22-13-22z" fill="${s.muzzle}"/>`
    : `<ellipse cx="50" cy="73" rx="19" ry="14" fill="${s.muzzle}"/>`;
  const rightEye = wink
    ? `<path d="M59 54q7.5-6 15 0" fill="none" stroke="${locked ? '#bdb3ad' : s.blaze || s.fur === '#1b1b20' || s.fur === '#2c2b31' ? '#fff' : '#3a2a22'}" stroke-width="3.6" stroke-linecap="round"/>`
    : `<g transform="translate(32 0)"><ellipse cx="34" cy="54" rx="8.5" ry="9" fill="${s.sclera}"/>${pupil.replace('PUP', s.pupil)}</g>`;
  return `<svg viewBox="0 0 100 100" aria-hidden="true">${gold}
  <path d="M12 50 15 9l30 20z" fill="${s.metal ? fur : earL}"/><path d="M88 50 85 9 55 29z" fill="${s.metal ? fur : earR}"/>
  <path d="M20 38 22 19l14 10z" fill="${s.ear}"/><path d="M80 38 78 19 64 29z" fill="${s.ear}"/>
  <ellipse cx="50" cy="57" rx="41" ry="34" fill="${fur}"/>
  ${spots}${stripes}${muzzle}
  <ellipse cx="33" cy="54" rx="8.5" ry="9" fill="${s.sclera}"/>${pupil.replace('PUP', s.pupil)}<circle cx="36" cy="52" r="1.8" fill="#fff"/>
  ${rightEye}
  <path d="M46 63h8l-4 4z" fill="#f08aa0"/>
  <path d="M42 70q8 14 16 0z" fill="#b8435a"/><path d="M45.5 73.5q4.5 6 9 0z" fill="#f58ea3"/>
  <ellipse cx="22" cy="68" rx="6" ry="3.5" fill="#ff9fb0" opacity=".45"/><ellipse cx="78" cy="68" rx="6" ry="3.5" fill="#ff9fb0" opacity=".45"/>
</svg>`;
}
