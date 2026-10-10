// Kleine Soundeffekte, direkt mit der Web Audio API erzeugt (keine Audiodateien nötig).
let ctx = null;
let enabled = true;

export function setSoundEnabled(v) {
  enabled = !!v;
}

function audio() {
  if (!enabled) return null;
  try {
    if (!ctx) {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return null;
      ctx = new AC();
    }
    if (ctx.state === 'suspended') ctx.resume();
    return ctx;
  } catch {
    return null;
  }
}

function tone({ f1, f2 = f1, dur = 0.12, type = 'sine', gain = 0.12, delay = 0 }) {
  const ac = audio();
  if (!ac) return;
  const t = ac.currentTime + delay;
  const osc = ac.createOscillator();
  const g = ac.createGain();
  osc.type = type;
  osc.frequency.setValueAtTime(f1, t);
  osc.frequency.exponentialRampToValueAtTime(f2, t + dur);
  g.gain.setValueAtTime(0.0001, t);
  g.gain.exponentialRampToValueAtTime(gain, t + 0.015);
  g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  osc.connect(g).connect(ac.destination);
  osc.start(t);
  osc.stop(t + dur + 0.02);
}

function noise({ dur = 0.08, gain = 0.05, delay = 0, freq = 900 }) {
  const ac = audio();
  if (!ac) return;
  const t = ac.currentTime + delay;
  const len = Math.floor(ac.sampleRate * dur);
  const buf = ac.createBuffer(1, len, ac.sampleRate);
  const d = buf.getChannelData(0);
  for (let i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / len);
  const src = ac.createBufferSource();
  src.buffer = buf;
  const f = ac.createBiquadFilter();
  f.type = 'lowpass';
  f.frequency.value = freq;
  const g = ac.createGain();
  g.gain.setValueAtTime(gain, t);
  g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  src.connect(f).connect(g).connect(ac.destination);
  src.start(t);
}

const vary = (f, amount = 0.06) => f * (1 + (Math.random() * 2 - 1) * amount);
// Dur-Pentatonik für steigende Ketten (Ziehen, Welle)
const SCALE = [0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24];
const step = (base, k) => base * Math.pow(2, SCALE[Math.min(k, SCALE.length - 1)] / 12);

export const sfx = {
  /** X setzen – `chain` > 0 beim Ziehen: jede weitere Markierung klingt höher. */
  mark: (chain = 0) => {
    const f = chain ? step(520, chain) : vary(560);
    tone({ f1: f, f2: f * 0.72, dur: 0.07, type: 'triangle', gain: 0.075 });
    noise({ dur: 0.03, gain: 0.025, freq: 2400 });
  },
  clear: () => tone({ f1: vary(420), f2: 260, dur: 0.07, type: 'triangle', gain: 0.055 }),
  cat: () => {
    // „Miau“: Gleiten nach oben und wieder nach unten
    const b = vary(640, 0.05);
    tone({ f1: b, f2: b * 1.55, dur: 0.12, type: 'sine', gain: 0.1 });
    tone({ f1: b * 1.55, f2: b * 1.1, dur: 0.16, type: 'sine', gain: 0.08, delay: 0.11 });
  },
  /** Aufprall der Katze */
  land: () => {
    tone({ f1: 180, f2: 60, dur: 0.16, type: 'sine', gain: 0.16 });
    noise({ dur: 0.09, gain: 0.05, freq: 700 });
    tone({ f1: 1568, f2: 2093, dur: 0.12, type: 'triangle', gain: 0.025, delay: 0.04 });
  },
  /** Ticks im Takt der X-Welle (Verzögerungen in ms) */
  wave: (delays) => {
    const uniq = [...new Set(delays.map((d) => Math.round(d / 40) * 40))].sort((a, b) => a - b).slice(0, 9);
    uniq.forEach((d, k) => tone({ f1: step(880, k), f2: step(880, k) * 0.9, dur: 0.05, type: 'triangle', gain: 0.022, delay: (d + 230) / 1000 }));
  },
  error: () => {
    tone({ f1: 220, f2: 150, dur: 0.22, type: 'square', gain: 0.05 });
    tone({ f1: 165, f2: 110, dur: 0.26, type: 'square', gain: 0.04, delay: 0.12 });
  },
  poof: () => noise({ dur: 0.25, gain: 0.07, freq: 1400 }),
  hint: () => {
    tone({ f1: 880, dur: 0.1, gain: 0.07 });
    tone({ f1: 1320, dur: 0.16, gain: 0.06, delay: 0.08 });
    tone({ f1: 1760, dur: 0.2, gain: 0.04, delay: 0.16 });
  },
  mouse: () => {
    for (let i = 0; i < 4; i++) tone({ f1: 1500 + i * 120, f2: 1800 + i * 120, dur: 0.05, gain: 0.04, delay: i * 0.05 });
  },
  pop: () => tone({ f1: vary(900), f2: 1300, dur: 0.06, type: 'sine', gain: 0.05 }),
  star: (k) => {
    const f = [1047, 1319, 1568][k] || 1568;
    tone({ f1: f, dur: 0.25, type: 'triangle', gain: 0.09 });
    tone({ f1: f * 2, dur: 0.18, type: 'sine', gain: 0.03, delay: 0.03 });
  },
  tick: () => tone({ f1: 1800, dur: 0.02, type: 'square', gain: 0.012 }),
  /** Notiz (Pfote) gesetzt / entfernt */
  note: (on = true) => {
    tone({ f1: on ? 700 : 900, f2: on ? 950 : 600, dur: 0.09, type: 'sine', gain: 0.06 });
    noise({ dur: 0.04, gain: 0.02, freq: 1800 });
  },
  /** Maus quiekt */
  squeak: () => {
    tone({ f1: vary(2200), f2: 2900, dur: 0.06, type: 'sine', gain: 0.05 });
    tone({ f1: 2600, f2: 2100, dur: 0.07, type: 'sine', gain: 0.04, delay: 0.07 });
  },
  scurry: () => {
    for (let i = 0; i < 6; i++) noise({ dur: 0.025, gain: 0.025, freq: 3000, delay: i * 0.045 });
  },
  /** neue Katze freigeschaltet */
  unlock: () => {
    [784, 988, 1175, 1568].forEach((f, i) => tone({ f1: f, dur: 0.18, type: 'triangle', gain: 0.07, delay: i * 0.08 }));
    tone({ f1: 2093, dur: 0.4, type: 'sine', gain: 0.04, delay: 0.32 });
  },
  /** Serien-Flamme */
  streak: () => {
    noise({ dur: 0.3, gain: 0.05, freq: 900 });
    tone({ f1: 330, f2: 660, dur: 0.3, type: 'triangle', gain: 0.07 });
  },
  win: () => {
    [523, 659, 784, 1047].forEach((f, i) => tone({ f1: f, dur: 0.22, type: 'triangle', gain: 0.1, delay: i * 0.11 }));
    tone({ f1: 1047, f2: 1568, dur: 0.4, gain: 0.07, delay: 0.5 });
    noise({ dur: 0.35, gain: 0.05, freq: 3000, delay: 0.3 });
  },
  lose: () => {
    [392, 330, 262].forEach((f, i) => tone({ f1: f, f2: f * 0.97, dur: 0.28, type: 'triangle', gain: 0.09, delay: i * 0.18 }));
  },
};
