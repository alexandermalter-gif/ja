# KalorienKompass

Persönlicher Kalorienzähler für Android (Kotlin, Jetpack Compose, Material 3).

- Tagebuch mit Frühstück, Mittagessen, Abendessen und Snacks, Kalorienring und Makros
- Lebensmittel per Suche (eingebaute Basis-Lebensmittel + Open Food Facts), Barcode-Scan,
  Foto-Erkennung mit Claude (eigener Anthropic-API-Schlüssel), Schnelleintrag, eigene Lebensmittel und Rezepte
- Gewicht und Körpermaße mit Verlaufsgrafiken
- Schritte über Health Connect (z. B. aus Honor Health), Schrittsensor oder manuell
- Alle Daten bleiben lokal auf dem Gerät (Room + DataStore); JSON-Backup und CSV-Export

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
