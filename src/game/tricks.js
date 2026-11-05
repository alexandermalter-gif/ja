// Lösungstricks: kurze Erklärungen mit Beispielbild – für den Regeln-Dialog und als
// einmalige Karte, wenn ein Level zum ersten Mal einen neuen Trick braucht.

const COLORS = { a: '#c9a3e6', b: '#9fd77f', c: '#f2b36b', d: '#8fc8ee', e: '#f5d76e' };

const CAT = '<path d="M6 15 5 2.5l10.5 7.2q4.5-1.4 9 0L35 2.5 34 15q3 4.5 2.4 9.6C35.3 32 28.5 34.5 20 34.5S4.7 32 3.6 24.6Q3 19.5 6 15z"/>';

/**
 * Kleines Beispielbrett. rows: Zeichenketten aus Paaren „Farbe + Zeichen“:
 * . leer · x X · r rotes X (fällt weg) · c Katze · g angenommene Katze · ! hervorgehoben
 */
export function trickSvg(rows, label) {
  const n = rows.length;
  const S = 12;
  let out = `<svg viewBox="0 0 ${n * S} ${n * S}" class="trick-grid" role="img" aria-label="${label}">`;
  rows.forEach((row, r) => {
    for (let c = 0; c < n; c++) {
      const col = COLORS[row[c * 2]] || '#ddd';
      const mk = row[c * 2 + 1];
      const x = c * S;
      const y = r * S;
      out += `<rect x="${x + 0.6}" y="${y + 0.6}" width="${S - 1.2}" height="${S - 1.2}" rx="2" fill="${col}"${mk === '!' ? ' stroke="#2e2c31" stroke-width="1.2"' : ''}/>`;
      if (mk === 'x' || mk === 'r') {
        const stroke = mk === 'r' ? '#d4343a' : '#2e2c31';
        out += `<path d="M${x + 3.6} ${y + 3.6}l4.8 4.8m0-4.8-4.8 4.8" stroke="${stroke}" stroke-width="1.6" stroke-linecap="round"/>`;
      }
      if (mk === 'c' || mk === 'g') {
        out += `<g transform="translate(${x + 1.6} ${y + 2}) scale(.22)" fill="#2c2b31"${mk === 'g' ? ' opacity=".42"' : ''}>${CAT}</g>`;
      }
    }
  });
  return `${out}</svg>`;
}

export const TRICKS = [
  {
    tech: 1,
    name: 'Einzelfeld',
    text: 'Hat eine Zeile, Spalte oder Farbe nur noch ein freies Feld, muss die Katze genau dorthin.',
    rows: ['axaxa!axax', 'b.b.bxb.c.', 'b.c.cxc.c.', 'd.d.dxe.e.', 'd.d.dxe.e.'],
  },
  {
    tech: 2,
    name: 'Eingesperrt',
    text: 'Liegen alle freien Felder einer Farbe in einer Zeile, gehört die Katze dieser Zeile zu dieser Farbe – die übrigen Felder der Zeile fallen weg.',
    rows: ['a!a!brbrbr', 'axb.b.c.c.', 'b.b.c.c.c.', 'd.d.d.e.e.', 'd.d.e.e.e.'],
  },
  {
    tech: 3,
    name: 'Blockade',
    text: 'Würde eine Katze auf einem Feld alle freien Felder einer anderen Farbe, Zeile oder Spalte blockieren, kann sie dort nicht sitzen.',
    rows: ['a.a.a.b.b.', 'a.arb.b.b.', 'c!c!c!b.b.', 'd.d.d.e.e.', 'd.d.e.e.e.'],
  },
  {
    tech: 4,
    name: 'Gruppen',
    text: 'Liegen zwei Farben komplett in denselben zwei Zeilen, sind diese Zeilen für sie reserviert. Alle anderen Felder darin fallen weg – genauso mit drei Farben in drei Zeilen.',
    rows: ['a!a!b!crcr', 'a!b!b!crcr', 'c.c.c.c.d.', 'c.e.e.d.d.', 'e.e.e.d.d.'],
  },
  {
    tech: 5,
    name: 'Wenn-dann',
    text: 'Nimm an, auf einem Feld säße eine Katze, und denke die Folgen weiter. Bleibt dann für eine Farbe, Zeile oder Spalte kein Feld übrig, war die Annahme falsch – das Feld bekommt ein X.',
    rows: ['axagaxbxbx', 'crcrcrb.b.', 'b.b.b.b.d.', 'd.d.e.e.d.', 'd.e.e.e.e.'],
  },
];

/** Trick-Nummer für eine Löser-Technik (Widerspruch mit langer Kette zählt als Wenn-dann). */
export const trickFor = (tech) => TRICKS.find((t) => t.tech === Math.min(5, tech)) || null;

export function trickHtml(t) {
  return `<div class="trick">${trickSvg(t.rows, `Beispiel: ${t.name}`)}<p><b>${t.name}.</b> ${t.text}</p></div>`;
}
