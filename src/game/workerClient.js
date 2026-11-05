// Rätsel im Web Worker erzeugen; geht das nicht (alter Browser, gesperrte blob:-Worker), im Hauptthread.
import WORKER_SRC from 'inline-worker:./genWorker.js';
import { generateFree } from './freeplay.js';
import { generateDailyV3 } from '../logic/dailyGen.js';

let worker = null;
let broken = false;
let nextId = 1;
const pending = new Map();

function getWorker() {
  if (worker || broken) return worker;
  try {
    const url = URL.createObjectURL(new Blob([WORKER_SRC], { type: 'text/javascript' }));
    worker = new Worker(url);
    worker.onmessage = (ev) => {
      const p = pending.get(ev.data.id);
      if (!p) return;
      pending.delete(ev.data.id);
      if (ev.data.error) p.reject(new Error(ev.data.error));
      else p.resolve(ev.data.result);
    };
    worker.onerror = (ev) => {
      ev.preventDefault?.();
      broken = true;
      worker = null;
      for (const p of pending.values()) p.fallback();
      pending.clear();
    };
  } catch {
    broken = true;
    worker = null;
  }
  return worker;
}

const pause = () => new Promise((r) => setTimeout(r, 0));
const local = { free: (args) => generateFree(...args, pause), daily: (args) => generateDailyV3(...args, pause) };

function run(type, args) {
  const w = getWorker();
  if (!w) return local[type](args);
  return new Promise((resolve, reject) => {
    const id = nextId++;
    pending.set(id, { resolve, reject, fallback: () => local[type](args).then(resolve, reject) });
    w.postMessage({ id, type, args });
  });
}

/** Zufallsrätsel (Größe n, Stufe tier, Startwert seed). */
export const generateFreeAsync = (n, tier, seed) => run('free', [n, tier, seed]);

/** Tagesrätsel live erzeugen (nur für Tage außerhalb der Tabelle). */
export const generateDailyAsync = (key) => run('daily', [key]);
