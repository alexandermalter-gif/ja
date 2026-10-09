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

export const sfx = {
  mark: () => tone({ f1: 520, f2: 380, dur: 0.07, type: 'triangle', gain: 0.07 }),
  clear: () => tone({ f1: 300, f2: 220, dur: 0.06, type: 'triangle', gain: 0.05 }),
  cat: () => {
    // "Miau": kurzes Gleiten nach oben und wieder nach unten
    tone({ f1: 620, f2: 980, dur: 0.12, type: 'sine', gain: 0.1 });
    tone({ f1: 980, f2: 700, dur: 0.16, type: 'sine', gain: 0.08, delay: 0.11 });
  },
  error: () => {
    tone({ f1: 200, f2: 140, dur: 0.22, type: 'square', gain: 0.05 });
    tone({ f1: 150, f2: 110, dur: 0.25, type: 'square', gain: 0.04, delay: 0.12 });
  },
  hint: () => {
    tone({ f1: 880, dur: 0.1, gain: 0.07 });
    tone({ f1: 1320, dur: 0.16, gain: 0.06, delay: 0.08 });
  },
  mouse: () => {
    for (let i = 0; i < 4; i++) tone({ f1: 1500 + i * 120, f2: 1800 + i * 120, dur: 0.05, gain: 0.04, delay: i * 0.05 });
  },
  win: () => {
    [523, 659, 784, 1047].forEach((f, i) => tone({ f1: f, dur: 0.22, type: 'triangle', gain: 0.1, delay: i * 0.11 }));
    tone({ f1: 1047, f2: 1568, dur: 0.4, gain: 0.07, delay: 0.5 });
  },
  lose: () => {
    [392, 330, 262].forEach((f, i) => tone({ f1: f, f2: f * 0.97, dur: 0.28, type: 'triangle', gain: 0.09, delay: i * 0.18 }));
  },
};
