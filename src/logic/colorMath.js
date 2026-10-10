// Wahrgenommener Farbabstand: OKLab-Abstand ×100, für normales Sehen und simulierte Farbschwächen
// (Machado, Oliveira & Fernandes 2009, Schweregrad 1.0).

const MACHADO = {
  protan: [[0.152286, 1.052583, -0.204868], [0.114503, 0.786281, 0.099216], [-0.003882, -0.048116, 1.051998]],
  deutan: [[0.367322, 0.860646, -0.227968], [0.280085, 0.672501, 0.047413], [-0.011820, 0.042940, 0.968881]],
  tritan: [[1.255528, -0.076749, -0.178779], [-0.078411, 0.930809, 0.147602], [0.004733, 0.691367, 0.303900]],
};

const hexToLinear = (hex) => [0, 2, 4].map((i) => {
  const c = parseInt(hex.replace('#', '').slice(i, i + 2), 16) / 255;
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
});

function linearToOklab([r, g, b]) {
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
}

export function oklab(hex, vision = 'normal') {
  let lin = hexToLinear(hex);
  if (vision !== 'normal') {
    const t = MACHADO[vision];
    lin = t.map((row) => Math.min(1, Math.max(0, row[0] * lin[0] + row[1] * lin[1] + row[2] * lin[2])));
  }
  return linearToOklab(lin);
}

/** Farbabstand (OKLab ΔE ×100). */
export function deltaE(a, b, vision = 'normal') {
  const A = oklab(a, vision);
  const B = oklab(b, vision);
  return 100 * Math.hypot(A[0] - B[0], A[1] - B[1], A[2] - B[2]);
}

/** Schlechtester Abstand über normales Sehen und alle drei Farbschwächen. */
export function pairDistance(a, b) {
  return {
    normal: deltaE(a, b),
    cvd: Math.min(deltaE(a, b, 'protan'), deltaE(a, b, 'deutan'), deltaE(a, b, 'tritan')),
  };
}

/** Mischt eine Farbe mit Weiß (t = Anteil Weiß). */
export function tint(hex, t) {
  return `#${[0, 2, 4].map((i) => {
    const c = parseInt(hex.replace('#', '').slice(i, i + 2), 16);
    return Math.round(c + (255 - c) * t).toString(16).padStart(2, '0');
  }).join('')}`;
}
