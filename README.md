# Katzen-Sudoku 🐱

Ein browserbasiertes Logikrätsel mit **three.js**: **100 Level** in aufsteigender Schwierigkeit,
ein **Tägliches Rätsel** mit Serie, ein interaktives **Tutorial** und **8 Katzen** zum Sammeln.

## Regeln

| | |
|---|---|
| **1 Katze pro Farbe** | Jede Farbfläche bekommt genau eine Katze. |
| **1 Katze pro Zeile und Spalte** | In jeder Zeile und jeder Spalte sitzt genau eine Katze. |
| **Katzen dürfen sich nicht berühren** | Auch nicht schräg (keine der 8 Nachbarzellen). |

Jedes Rätsel hat **genau eine Lösung** und ist **ohne Raten** rein logisch lösbar.

## Spielen

- **Ohne Installation:** `dist/katzen-sudoku.html` herunterladen und per Doppelklick im Browser öffnen
  (alles ist in dieser einen Datei enthalten, läuft auch offline).
- **Über einen Webserver / GitHub Pages:** `index.html` ausliefern (lädt `style.css` und `dist/game.js`),
  z. B. lokal mit `npm run serve` und dann <http://localhost:8080> öffnen.

Beim ersten Start beginnt automatisch das **interaktive Tutorial**: Eine Hand zeigt auf dem Spielfeld,
wo getippt wird, und erklärt dabei Einzelfeld, Eingesperrt und Blockade. Es ist jederzeit über das Menü erreichbar.

### Bedienung

| Aktion | Touch | Maus | Tastatur |
|---|---|---|---|
| X setzen / wieder entfernen | 1× tippen | Klick | Leertaste |
| Katze setzen | doppelt tippen | Doppelklick | Enter |
| Katze entfernen | antippen | Klick | Enter |
| Notiz (Pfote) setzen / entfernen | **lange drücken** | Rechtsklick | N |
| Mehrere X | über Felder ziehen | ziehen | – |
| Feld wählen | – | – | Pfeiltasten |
| Rückgängig | Knopf | Knopf | Strg/⌘ + Z |

- **Automatische X:** Sobald eine Katze sitzt, bekommen alle von ihr blockierten Felder (Zeile, Spalte, Farbe,
  Nachbarfelder) von selbst ein X – als Welle, die sich von der Katze aus ausbreitet.
  Automatische X sind **hellgrau**, eigene X **dunkelgrau**.
- **Notizen:** Ein Pfotenabdruck bedeutet „hier vielleicht eine Katze“. Beim langen Drücken füllt sich ein Ring,
  danach wird die Pfote gestempelt. Wird das Feld später durch ein X oder eine Katze belegt, verschwindet die Notiz.

### Fische, Helfer, Sterne

- **🐟 Fische (Leben):** Eine Katze an der falschen Stelle kostet einen Fisch. Nach drei Fehlern beginnt das Rätsel neu.
  In den Einstellungen abschaltbar (dann werden sich störende Katzen rot markiert).
- **Joker (Katze, 1× pro Rätsel):** setzt eine richtige Katze.
- **Hinweis (Glühbirne):** erklärt den nächsten logischen Schritt, hebt die Felder hervor und kann ihn ausführen.
  Hinweise sind ein **Vorrat** (Start: 5). **+1** für jedes Level, das zum ersten Mal 3 Sterne bekommt,
  und **+1** für jedes gelöste Tägliche Rätsel.
- **Maus (Fehlersuche):** Eine kleine 3D-Maus flitzt zu jedem Fehler (falsches X, ohne Fische auch falsche Katzen)
  und knabbert ihn weg. Findet sie nichts, ist ihr Einsatz **gratis**; sonst zählt er als Helfer.
- **Sterne:** 3 ★ ohne Fehler und ohne Helfer, 2 ★ bei höchstens zwei Patzern, sonst 1 ★. Mehr Sterne geben mehr Punkte.

### Tägliches Rätsel und Serie

Jeden Tag gibt es ein neues Rätsel – für alle Spieler am selben Tag gleich (das Datum ist der Zufallsstartwert).
Montags ist es leicht (6 × 6), zum Wochenende hin wird es schwerer, sonntags 9 × 9.
Die **Serie** zählt die Tage in Folge; das Menü zeigt sie mit Flamme und Wochenübersicht.

### Katzen sammeln

| Katze | Freischaltung |
|---|---|
| Smoking | von Anfang an |
| Tiger | 10 Sterne |
| Nebel | 30 Sterne |
| Schneeflocke | 60 Sterne |
| Siam | 100 Sterne |
| Panther | 3 Tage Serie |
| Glückskatze | 7 Tägliche Rätsel |
| Goldkatze | 200 Sterne |

Die ausgewählte Katze sitzt auf dem Spielfeld (3D-Modell mit passendem Fell) und im Joker-Knopf.

### Statistik

Gelöste Level, Sterne, Punkte, Tägliche Rätsel, aktuelle und längste Serie, durchschnittliche Bestzeit je
Brettgröße und die letzten 20 Lösungszeiten. Der Sieg-Dialog meldet **„Neue Bestzeit!“**.

### Farben

Die zehn Kachelfarben (Lila, Hellgrün, Braun, Orange, Gelb, Dunkelblau, Rosa, Rot, Türkis, Himmelblau) sind
so abgestimmt, dass **jedes Paar** gut unterscheidbar ist – gemessen als wahrgenommener Farbabstand (OKLab ΔE):

- normales Sehen: ΔE ≥ 15 für alle 45 Paare
- Rot-Grün- und Blau-Gelb-Schwäche (simuliert): ΔE ≥ 8 für alle Paare
- helle und dunkle X bleiben auf jeder Farbe sichtbar

Zusätzlich verteilt der Generator die Farben je Rätsel so, dass sich angrenzende Flächen möglichst stark
unterscheiden, und legt dunkle Farben bevorzugt auf kleine Flächen. `npm test` prüft das alles
(`tools/check-palette.mjs`).

### Barrierefreiheit

- **Muster auf Farben** (Einstellung): jede Farbe hat ein eigenes Muster – Punkte, Streifen, Karos, Ringe …
- **Farbgrenzen** (Einstellung): dunkle Linien zwischen den Farbflächen
- **Tastatursteuerung** mit sichtbarem Rahmen auf dem Spielfeld
- **Screenreader:** Feldposition, Farbe und jede Aktion werden angesagt
- `prefers-reduced-motion` wird beachtet (weniger Wackeln, Partikel und Konfetti)

Fortschritt, Punkte, Serie, Katzen und das laufende Rätsel werden im Browser gespeichert (localStorage).

## Level und Schwierigkeit

| Level | Größe | Schwierigkeit |
|---|---|---|
| 1–8 | 5 × 5 | Leicht |
| 9–20 | 6 × 6 | Leicht – Mittel |
| 21–35 | 7 × 7 | Mittel |
| 36–55 | 8 × 8 | Mittel – Schwer |
| 56–77 | 9 × 9 | Schwer – Sehr schwer |
| 78–100 | 10 × 10 | Sehr schwer – Experte |

Die Level erzeugt `tools/generate-levels.mjs`, das Tägliche Rätsel erzeugt der Browser mit demselben Generator
(`src/logic/generator.js`):

1. Katzen zufällig so platzieren, dass alle Regeln erfüllt sind.
2. Farbflächen Feld für Feld von den Katzen aus wachsen lassen – nur so, dass die Lösung eindeutig bleibt
   (falls das nicht mehr geht: Rest auffüllen und gierig reparieren).
3. Mit einem **logischen Löser** bewerten, der nur Techniken verwendet, die auch Menschen anwenden:

   | Technik | Idee |
   |---|---|
   | Einzelfeld | Eine Zeile/Spalte/Farbe hat nur noch ein mögliches Feld. |
   | Eingesperrt | Alle freien Felder einer Farbe liegen in einer Zeile (o. ä.) → Rest der Zeile fällt weg. |
   | Blockade | Eine Katze auf Feld X würde eine ganze Farbe/Zeile/Spalte blockieren → X fällt weg. |
   | Gruppen | k Farben liegen komplett in k Zeilen → diese Zeilen sind für sie reserviert. |
   | Widerspruch | Annahme „Katze auf X“ führt zu einer leeren Farbe/Zeile/Spalte. |

4. Level: je Größe viele Rätsel erzeugen, nach Punktzahl sortieren und aufsteigend auswählen.
   Tägliches Rätsel: so lange erzeugen, bis die Punktzahl im Zielbereich des Wochentags liegt.

`npm test` prüft alle 100 Level (eindeutig, korrekt, zusammenhängende Farbflächen, ohne Raten lösbar),
löst jedes Level einmal ausschließlich mit den Hinweisen des Spiels, prüft Tutorial-Rätsel,
8 Wochen Tagesrätsel (eindeutig, lösbar, reproduzierbar), die Serien-Berechnung und die Farben.

## Entwicklung

```bash
npm install
npm run build      # dist/game.js + dist/katzen-sudoku.html
npm test           # Level, Hinweise, Tagesrätsel prüfen
npm run generate   # Level neu erzeugen (dauert einige Minuten)
npm run serve      # lokaler Webserver auf Port 8080
```

```
index.html, style.css     Oberfläche
src/main.js               Spielsteuerung, Menü, Dialoge, Tutorial-Ablauf, Tastatur
src/game/board3d.js       three.js-Spielfeld (Kacheln, X, Katzen, Notizen, Maus, Muster, Effekte)
src/game/catModel.js      3D-Katzenkopf aus Grundkörpern, je Fell
src/game/skins.js         Katzen-Sammlung: Felle, Gesichter, Freischaltregeln
src/game/daily.js         Tägliches Rätsel, Serie, Wochenansicht
src/game/tutorial.js      Tutorial-Rätsel und -Texte
src/game/stats.js         Statistik und Balkendiagramme
src/game/hints.js         Hinweise in verständlichem Deutsch
src/game/icons.js         SVG-Symbole
src/game/audio.js         Soundeffekte (Web Audio, ohne Dateien)
src/game/storage.js       Spielstand im Browser
src/logic/solver.js       Regeln, exakter und logischer Löser
src/logic/generator.js    Rätsel-Generator (Node und Browser) inkl. Farbvergabe
src/logic/palette.js      Farbpalette und Farbabstände
src/logic/colorMath.js    Farbabstand (OKLab, Farbschwäche-Simulation)
src/logic/levels.js       die 100 Level (generiert)
tools/                    Level-Skript, Build, Tests
```
