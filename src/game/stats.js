// Statistik: Kennzahlen und kleine Balkendiagramme (SVG, Farben über CSS-Tokens).

export function formatTime(sec) {
  const s = Math.max(0, Math.floor(sec));
  const m = Math.floor(s / 60);
  return `${String(m).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

/** Durchschnittliche Bestzeit je Brettgröße (nur gelöste Level). */
export function averageBySize(levels, best) {
  const sizes = [...new Set(levels.map((l) => l.n))];
  return sizes.map((n) => {
    const times = levels.filter((l) => l.n === n && best[l.id]?.time != null && Number.isFinite(best[l.id].time)).map((l) => best[l.id].time);
    const total = levels.filter((l) => l.n === n).length;
    return {
      n,
      label: `${n}×${n}`,
      solved: times.length,
      total,
      avg: times.length ? times.reduce((a, b) => a + b, 0) / times.length : null,
      fastest: times.length ? Math.min(...times) : null,
    };
  });
}

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

/**
 * Balkendiagramm. items: [{ label, value (Sekunden) | null, title }]
 * labelAll: Wert über jedem Balken (bei wenigen Balken), sonst nur Bestwert und letzter Wert.
 */
export function barChart(items, { labelAll = false, highlightMin = true, height = 150 } = {}) {
  const W = 320;
  const H = height;
  const top = 20;
  const bottom = 22;
  const left = 4;
  const right = 4;
  const n = items.length;
  const plotH = H - top - bottom;
  const max = Math.max(1, ...items.map((d) => d.value || 0));
  const slot = (W - left - right) / Math.max(n, 1);
  const barW = Math.max(4, Math.min(34, slot - 4));
  const values = items.map((d) => d.value).filter((v) => v != null);
  const minVal = values.length ? Math.min(...values) : null;
  const lastIdx = items.map((d) => d.value != null).lastIndexOf(true);
  let bars = '';
  items.forEach((d, i) => {
    const cx = left + slot * i + slot / 2;
    const x = cx - barW / 2;
    if (d.value == null) {
      bars += `<rect class="bar-empty" x="${x}" y="${top + plotH - 3}" width="${barW}" height="3" rx="1.5"><title>${esc(d.title || d.label)}: noch nicht gelöst</title></rect>`;
    } else {
      const h = Math.max(4, (d.value / max) * plotH);
      const y = top + plotH - h;
      const r = Math.min(4, barW / 2);
      const isMin = highlightMin && d.value === minVal;
      // oben abgerundet, unten flach auf der Grundlinie
      const path = `M${x},${top + plotH}V${y + r}Q${x},${y} ${x + r},${y}H${x + barW - r}Q${x + barW},${y} ${x + barW},${y + r}V${top + plotH}Z`;
      bars += `<path class="bar${isMin ? ' bar-best' : ''}" d="${path}"><title>${esc(d.title || d.label)}: ${formatTime(d.value)}</title></path>`;
      if (labelAll || isMin || i === lastIdx) {
        bars += `<text class="bar-value" x="${cx}" y="${y - 6}" text-anchor="middle">${formatTime(d.value)}</text>`;
      }
    }
    if (d.label) bars += `<text class="bar-label" x="${cx}" y="${H - 6}" text-anchor="middle">${esc(d.label)}</text>`;
  });
  return `<svg class="chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="Balkendiagramm">
    <line class="axis" x1="${left}" x2="${W - right}" y1="${top + plotH + 0.5}" y2="${top + plotH + 0.5}"/>
    ${bars}
  </svg>`;
}
