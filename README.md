# Katzen-Sudoku 🐱

Ein browserbasiertes Logikrätsel mit **three.js** und **100 Leveln** in aufsteigender Schwierigkeit.

## Regeln

| | |
|---|---|
| **1 Katze pro Farbe** | Jede Farbfläche bekommt genau eine Katze. |
| **1 Katze pro Zeile und Spalte** | In jeder Zeile und jeder Spalte sitzt genau eine Katze. |
| **Katzen dürfen sich nicht berühren** | Auch nicht schräg (keine der 8 Nachbarzellen). |

Jedes Level hat **genau eine Lösung** und ist **ohne Raten** rein logisch lösbar.

## Spielen

- **Ohne Installation:** `dist/katzen-sudoku.html` herunterladen und per Doppelklick im Browser öffnen
  (alles ist in dieser einen Datei enthalten, läuft auch offline).
- **Über einen Webserver / GitHub Pages:** `index.html` ausliefern (lädt `style.css` und `dist/game.js`),
  z. B. lokal mit `npm run serve` und dann <http://localhost:8080> öffnen.

### Bedienung

- **1× tippen** → X (hier ist sicher keine Katze), **nochmal tippen** → X wieder weg
- **Doppelt tippen** → Katze; ein Tippen auf eine Katze nimmt sie wieder weg
- **Automatische X:** Sobald eine Katze sitzt, bekommen alle von ihr blockierten Felder (Zeile, Spalte, Farbe,
  Nachbarfelder) von selbst ein X – als Welle, die sich von der Katze aus ausbreitet.
  Wird die Katze entfernt, verschwinden diese X wieder.
- **Über Felder ziehen** → mehrere X auf einmal setzen (bzw. löschen, wenn man auf einem X beginnt)
- **Strg/⌘ + Z** → Rückgängig

### Fische, Helfer, Sterne

- **🐟 Fische (Leben):** Eine Katze an der falschen Stelle kostet einen Fisch. Nach drei Fehlern beginnt das Level neu.
  In den Einstellungen abschaltbar (dann werden sich störende Katzen rot markiert).
- **Joker (Katze, 1× pro Level):** setzt eine richtige Katze.
- **Hinweis (Glühbirne, 3× pro Level):** erklärt den nächsten logischen Schritt in Worten, hebt die betroffenen Felder hervor
  und kann ihn auf Wunsch ausführen. Erkennt auch falsch gesetzte X und Katzen.
- **Maus (unbegrenzt):** markiert alle Felder, die von deinen Katzen blockiert werden (z. B. nachdem du automatische X gelöscht hast).
- **Sterne:** 3 ★ ohne Fehler und ohne Helfer, 2 ★ bei höchstens zwei Patzern, sonst 1 ★. Mehr Sterne geben mehr Punkte.

Fortschritt, Punkte und das gerade laufende Level werden im Browser gespeichert (localStorage).

### Einstellungen

Töne · Fische an/aus · Konflikte anzeigen ·
Farbgrenzen (dunkle Linien zwischen Farbflächen, hilfreich bei Farbschwäche) · Alle Level freischalten · Fortschritt löschen

## Level und Schwierigkeit

| Level | Größe | Schwierigkeit |
|---|---|---|
| 1–8 | 5 × 5 | Leicht |
| 9–20 | 6 × 6 | Leicht – Mittel |
| 21–35 | 7 × 7 | Mittel |
| 36–55 | 8 × 8 | Mittel – Schwer |
| 56–77 | 9 × 9 | Schwer – Sehr schwer |
| 78–100 | 10 × 10 | Sehr schwer – Experte |

Die Level werden von `tools/generate-levels.mjs` erzeugt (fester Zufallsstartwert, also reproduzierbar):

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

4. Je Größe viele Rätsel erzeugen, nach Punktzahl sortieren und aufsteigend auswählen.

`npm test` prüft alle 100 Level (eindeutig, korrekt, zusammenhängende Farbflächen, ohne Raten lösbar)
und löst jedes Level einmal ausschließlich mit den Hinweisen des Spiels.

## Entwicklung

```bash
npm install
npm run build      # dist/game.js + dist/katzen-sudoku.html
npm test           # Level und Hinweise prüfen
npm run generate   # Level neu erzeugen (dauert einige Minuten)
npm run serve      # lokaler Webserver auf Port 8080
```

```
index.html, style.css     Oberfläche
src/main.js               Spielsteuerung, Menüs, Dialoge
src/game/board3d.js       three.js-Spielfeld (Kacheln, X, Katzen, Hinweise, Konfetti)
src/game/catModel.js      3D-Katzenkopf aus Grundkörpern
src/game/hints.js         Hinweise in verständlichem Deutsch
src/game/icons.js         SVG-Symbole
src/game/audio.js         Soundeffekte (Web Audio, ohne Dateien)
src/game/storage.js       Spielstand im Browser
src/logic/solver.js       Regeln, exakter und logischer Löser
src/logic/levels.js       die 100 Level (generiert)
tools/                    Generator, Build, Tests
```
