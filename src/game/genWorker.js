// Web Worker: erzeugt Rätsel im Hintergrund (Freies Spiel, Tagesrätsel außerhalb der Tabelle).
import { generateFree } from './freeplay.js';
import { generateDailyV3 } from '../logic/dailyGen.js';

self.onmessage = async (ev) => {
  const { id, type, args } = ev.data;
  try {
    const result = type === 'free' ? await generateFree(...args) : await generateDailyV3(...args);
    self.postMessage({ id, result });
  } catch (err) {
    self.postMessage({ id, error: String(err?.message || err) });
  }
};
