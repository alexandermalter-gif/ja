// Farbpalette der Regionen (angelehnt an die Vorlage) – Name, Feldfarbe, Pastellton für die Katzenleiste.
export const PALETTE = [
  { name: 'Lila', hex: '#9580d0', pastel: '#cbbfe9' },
  { name: 'Hellgrün', hex: '#b6d99c', pastel: '#dcecd0' },
  { name: 'Graublau', hex: '#adbdd5', pastel: '#d5dce9' },
  { name: 'Orange', hex: '#f2ad73', pastel: '#f9d6b8' },
  { name: 'Gelb', hex: '#ddbd5e', pastel: '#efdfae' },
  { name: 'Dunkelblau', hex: '#6276ae', pastel: '#aab5d4' },
  { name: 'Rosa', hex: '#f0b7cf', pastel: '#f8dbe7' },
  { name: 'Rot', hex: '#c97674', pastel: '#e5b8b7' },
  { name: 'Türkis', hex: '#68b5b0', pastel: '#b2dad7' },
  { name: 'Himmelblau', hex: '#80b9e2', pastel: '#bfdcf0' },
];

// Farbpaare, die nicht direkt nebeneinander liegen sollen (zu ähnlich).
export const SIMILAR_PAIRS = [
  [2, 9], [0, 5], [3, 4], [6, 7], [1, 8], [8, 9], [2, 5], [5, 9],
];
