# Katzen-Sudoku 🐱

Ein browserbasiertes Logikrätsel mit **three.js**: **100 Level** in aufsteigender Schwierigkeit,
ein **Tagesrätsel** mit Serie und Archiv, **Freies Spiel**, ein interaktives **Tutorial** und **15 Katzen** zum Sammeln.

## Regeln

| | |
|---|---|
| **1 Katze pro Farbe** | Jede Farbfläche bekommt genau eine Katze. |
| **1 Katze pro Zeile und Spalte** | In jeder Zeile und jeder Spalte sitzt genau eine Katze. |
| **Katzen dürfen sich nicht berühren** | Auch nicht schräg (keine der 8 Nachbarzellen). |

Jedes Rätsel hat **genau eine Lösung** und ist **ohne Raten** rein logisch lösbar.

## Spielen

- **Ohne Installation:** `dist/katzen-sudoku.html` herunterladen und per Doppelklick im Browser öffnen
  (alles ist in dieser einen Datei enthalten – auch die Schrift –, läuft offline und ohne Anfragen an fremde Server).
- **Über einen Webserver / GitHub Pages:** `index.html` ausliefern (lädt `style.css`, `assets/` und `dist/game.js`),
  z. B. lokal mit `npm run serve` und dann <http://localhost:8080> öffnen.
- **Einbetten:** `npm run build` erzeugt zusätzlich `dist/embed.html` – ein Fragment ohne `<html>`/`<head>`/`<body>`.

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
  Nachbarfelder) von selbst ein X – als Welle von der Katze aus. Automatische X sind **hellgrau**, eigene **dunkelgrau**.
  Sie verschwinden mit ihrer Katze; antippt man eines, leuchtet die Katze auf, die es gesetzt hat. Abschaltbar.
- **Ziehen** beginnt erst nach einem kleinen Weg (ein wackelnder Tipp bleibt ein Tipp); schnelles Wischen
  markiert auch die übersprungenen Felder.
- **Notizen:** Ein Pfotenabdruck bedeutet „hier vielleicht eine Katze“. Beim langen Drücken füllt sich ein Ring.

### Fische, Helfer, Sterne

- **Sterne:** 3 ★ ohne Fehler und ohne Helfer, 2 ★ bei höchstens zwei Patzern, sonst 1 ★. Die Kopfzeile zeigt
  **live**, wie viele Sterne noch möglich sind; ein „−★“ an Joker und Hinweis zeigt, wann ein Helfer einen Stern kostet.
- **🐟 Fische (Leben):** Eine Katze an der falschen Stelle kostet einen Fisch (im Spielstand steht sofort ein X).
  Nach drei Fehlern: **neu starten** oder **weiterspielen** mit drei frischen Fischen (dann höchstens 1 ★).
  Ohne Fische (Einstellung) zählen Fehler nur für die Sterne; störende Katzen werden rot umrahmt.
- **Joker (1× pro Rätsel):** setzt eine richtige Katze – bevorzugt dort, wo keine andere Katze stört.
- **Hinweis (Glühbirne):** erklärt den nächsten logischen Schritt, hebt die Felder hervor und kann ihn ausführen.
  Die Hervorhebung bleibt, bis alles erledigt ist; denselben Hinweis noch einmal anzusehen ist gratis.
  Gibt es Fehler auf dem Brett, schickt der Hinweis zuerst zur Maus (kostenlos). Vorrat: Start 5,
  **+1** für das erste 3-Sterne-Ergebnis eines Levels und für jedes Tagesrätsel.
- **Maus (Fehlersuche):** flitzt zu jedem Fehler (falsches eigenes X, ohne Fische auch falsche Katzen) und knabbert
  ihn weg. Findet sie nichts, ist ihr Einsatz **gratis**.

### Tricks

Das Spiel erklärt jeden Trick mit Beispielbild in den Spielregeln – und mit einer Karte, sobald ein Level ihn
zum ersten Mal braucht:

| Trick | Idee |
|---|---|
| Einzelfeld | Eine Zeile/Spalte/Farbe hat nur noch ein mögliches Feld. |
| Eingesperrt | Alle freien Felder einer Farbe liegen in einer Zeile (o. ä.) → Rest der Zeile fällt weg. |
| Blockade | Eine Katze auf Feld X würde eine ganze Farbe/Zeile/Spalte blockieren → X fällt weg. |
| Gruppen | k Farben liegen komplett in k Zeilen → diese Zeilen sind für sie reserviert. |
| Zweierblock | Die freien Felder zweier Zeilen liegen in zwei Bereichen mit Platz für je eine Katze (z. B. 2×2-Block). |
| Wenn-dann | Annahme „Katze auf X“ führt zu einer leeren Farbe/Zeile/Spalte; der Hinweis zeigt die Kette als blasse Katzen. |

### Tagesrätsel, Archiv, Freies Spiel

- **Tagesrätsel:** jeden Tag ein neues, für alle gleich. Montags leicht (6 × 6), zum Wochenende hin schwerer,
  samstags mit Gruppen, sonntags 9 × 9 mit Wenn-dann. Die **Serie** zählt die Tage in Folge.
  Die Rätsel sind für zwei Jahre **vorberechnet** (`src/logic/dailies.js`) – kein Warten, auf allen Geräten gleich.
- **Archiv:** die Tagesrätsel der letzten 30 Tage nachholen (zählt für Punkte, nicht für die Serie).
- **Freies Spiel:** Zufallsrätsel mit wählbarer Größe (6–10) und Stufe; wird im Hintergrund (Web Worker) erzeugt.

Jedes Rätsel hat einen **eigenen Spielstand** – ein Tagesrätsel zwischendurch löscht das angefangene Level nicht.

### Katzen sammeln

| Katze | Freischaltung |
|---|---|
| Smoking | von Anfang an |
| Tiger | 10 Sterne |
| Nebel | 30 Sterne |
| Schneeflocke | 60 Sterne |
| Siam | 100 Sterne |
| Panther | 3 Tage Serie |
| Glückskatze | 7 Tagesrätsel |
| Zimt | 7 Tage Serie |
| Karamell | 150 Sterne |
| Goldkatze | 200 Sterne |
| Rotfuchs | 30 Tagesrätsel |
| Mitternacht | 250 Sterne |
| Mondkatze | 30 Tage Serie |
| Kristall | 300 Sterne |
| Lavendel | 100 Tagesrätsel |

Der Menü-Knopf „Katzen“ zeigt den nächsten Meilenstein (z. B. „Noch 7★ bis Tiger“) und ein „Neu“-Abzeichen.

### Statistik, Spielstand sichern

Gelöste Level, Sterne, Punkte, Tagesrätsel, Serien, Ø-Bestzeit je Größe, die letzten 20 Lösungen und die gesamte
Rätselzeit. Unter **Einstellungen → Spielstand sichern** gibt es den Fortschritt als Code zum Kopieren;
**Spielstand laden** führt ihn mit dem vorhandenen zusammen (auch auf einem anderen Gerät).

### Barrierefreiheit

- **Muster auf Farben** und **Farbgrenzen** (Einstellungen), auch in der Katzenleiste
- **Tastatursteuerung** mit zweifarbigem Rahmen auf dem Spielfeld; Dialoge mit Fokusfalle
- **Screenreader:** Feldposition, Farbe, jede Aktion, Hinweistexte und Zähler werden angesagt
- Textkontraste nach WCAG AA, Tippflächen mindestens 44 px
- `prefers-reduced-motion` und die Einstellung **Ruhige Animationen** dämpfen Wackeln, Partikel und Konfetti

### Farben

Die zehn Kachelfarben sind so abgestimmt, dass **jedes Paar** gut unterscheidbar ist (OKLab ΔE ≥ 15, bei
simulierter Rot-Grün- und Blau-Gelb-Schwäche ≥ 8). Der Generator legt angrenzende Flächen möglichst
unterschiedlich und dunkle Farben bevorzugt auf kleine Flächen (`tools/check-palette.mjs`).

## Level und Schwierigkeit

| Level | Größe |
|---|---|
| 1–8 | 5 × 5 |
| 9–20 | 6 × 6 |
| 21–35 | 7 × 7 |
| 36–55 | 8 × 8 |
| 56–77 | 9 × 9 |
| 78–100 | 10 × 10 |

Die angezeigte Stufe (Leicht … Experte) folgt der Bewertung des logischen Lösers relativ zur Brettgröße;
gelöste Level schalten die nächsten **drei** frei.

## Entwicklung

```bash
npm install
npm run build              # dist/game.js, dist/katzen-sudoku.html, dist/embed.html (prüft jede Ersetzung)
npm test                   # Unit-Tests (3 Zeitzonen), Level, Hinweise, Tagesrätsel, Farben
npm run test:e2e           # Browser-Durchlauf mit Playwright (nach dem Build)
npm run check:dist         # passt das eingecheckte dist/ zum Quellcode?
npm run serve              # lokaler Webserver auf Port 8080 (esbuild, ohne Internet)
npm run generate:dailies   # Tagesrätsel-Tabelle neu berechnen (alte Tage bleiben unverändert)
npm run recolor            # nur die Farben der Level neu vergeben
npm run generate           # neuen Level-Satz erzeugen (schreibt levels.new.js; --force zum Übernehmen)
npm run typecheck          # optional: TypeScript-Prüfung der JS-Dateien
```

GitHub Actions (`.github/workflows/ci.yml`) führt `npm test` und `check:dist` bei jedem Push aus.

```
index.html, style.css      Oberfläche
assets/                    Schrift Nunito (SIL Open Font License)
src/main.js                Spielsteuerung, Menü, Dialoge, Tutorial-Ablauf, Tastatur
src/ui/dom.js              Toast, Ansagen, Dialoge mit Fokusfalle
src/game/session.js        Spielsitzung ohne Grafik (Markierungen, Rückgängig, Fehler, Sieg)
src/game/scoring.js        Sterne, Punkte, Stufen
src/game/timing.js         Animationszeiten und Planer je Rätsel
src/game/board3d.js        three.js-Spielfeld (zeichnet nur bei Bedarf)
src/game/catModel.js       3D-Katzenkopf, je Fell
src/game/skins.js          Katzen-Sammlung: Felle, Gesichter, Freischaltregeln, Meilensteine
src/game/daily.js          Tagesrätsel, Serie, Wochenansicht
src/game/freeplay.js       Freies Spiel
src/game/genWorker.js      Web Worker für die Rätsel-Erzeugung (+ workerClient.js)
src/game/tutorial.js       Tutorial-Rätsel und -Texte
src/game/tricks.js         Tricks mit Beispielbildern
src/game/stats.js          Statistik und Balkendiagramme
src/game/hints.js          Hinweise in verständlichem Deutsch
src/game/icons.js          SVG-Symbole
src/game/audio.js          Soundeffekte (Web Audio, ohne Dateien)
src/game/storage.js        Spielstand: Schema, Prüfung, Zusammenführen, Export/Import
src/logic/solver.js        Regeln, exakter und logischer Löser
src/logic/generator.js     Rätsel-Generator inkl. Farbvergabe
src/logic/dailyGen.js      Erzeugung der Tagesrätsel (Version 3)
src/logic/dailies.js       vorberechnete Tagesrätsel (generiert)
src/logic/palette.js       Farbpalette und Farbabstände
src/logic/colorMath.js     Farbabstand (OKLab, Farbschwäche-Simulation)
src/logic/levels.js        die 100 Level (generiert, festgeschrieben)
tools/                     Build, Generatoren, Tests
```
