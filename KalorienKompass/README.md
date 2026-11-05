# KalorienKompass

Persönlicher Kalorienzähler für Android (Kotlin, Jetpack Compose, Material 3).

- Tagebuch mit Frühstück, Mittagessen, Abendessen und Snacks, Kalorienring und Makros
- Lebensmittel per Suche (eingebaute Basis-Lebensmittel + Open Food Facts), Barcode-Scan,
  Foto-Erkennung mit Claude (eigener Anthropic-API-Schlüssel), Schnelleintrag, eigene Lebensmittel und Rezepte
- Gewicht und Körpermaße mit Verlaufsgrafiken
- Schritte über Health Connect (z. B. aus Honor Health), Schrittsensor oder manuell
- Unbekannte Produkte: Nährwerttabelle fotografieren (KI liest die Werte ab), KI-Websuche nach
  Barcode/Name oder manuelle Eingabe – die Werte werden vor dem Speichern immer angezeigt
- Alle Daten bleiben lokal auf dem Gerät (Room + DataStore); JSON-Backup und CSV-Export

## Installation auf dem Handy

1. APK aufs Handy laden (z. B. aus dem GitHub-Actions-Artefakt `KalorienKompass-apk`) und öffnen.
2. „Installation aus unbekannten Quellen“ für den Browser/Dateimanager erlauben.
3. Neue Versionen einfach darüber installieren – die Daten bleiben erhalten.

## Einrichtung

- **Honor Health / Schritte:** In Honor Health die Synchronisierung mit Health Connect aktivieren
  (Profil/„Ich“ → Einstellungen bzw. Datenfreigabe → Health Connect; Menünamen können je nach Version
  abweichen). Danach in KalorienKompass unter Profil → Health Connect & Schritte verbinden und die
  Berechtigungen erteilen. Ohne Health Connect: Schrittsensor des Handys oder manuelle Eingabe.
- **KI-Funktionen (Foto der Mahlzeit, Nährwerttabelle, Websuche):** API-Schlüssel auf
  <https://console.anthropic.com> erstellen und unter Profil → Einstellungen eintragen. Standardmodell ist
  Claude Opus 5.5 (ca. 4 ct pro Foto); Sonnet 5.5 und Haiku 5.5 sind günstiger. Fotos werden nur bei
  Nutzung dieser Funktionen an Anthropic gesendet; der Schlüssel bleibt auf dem Gerät.

## Bauen

Voraussetzungen: JDK 17 und Android SDK (compileSdk 35).

```bash
cd KalorienKompass
./gradlew testDebugUnitTest assembleRelease
```

Die APK liegt danach unter `app/build/outputs/apk/release/`. Debug- und Release-Builds werden mit dem
eingecheckten Schlüssel `app/kalorienkompass.keystore` signiert, damit sich Builds von verschiedenen
Rechnern gegenseitig aktualisieren können.

GitHub Actions (`.github/workflows/android-apk.yml`) baut bei jedem Push, der `KalorienKompass/` betrifft,
die Release-APK und stellt sie als Artefakt `KalorienKompass-apk` bereit.

## Lizenzhinweise

Lebensmitteldaten: Open Food Facts (ODbL). KI: Anthropic Claude.
