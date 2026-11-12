# Webalizer Android App

Android Anwendung zur Auswertung von Webalizer‑Logdateien.

## Projektstruktur

`app/` – Android-App
- `src/main/java/` – Kotlin-Quellcode
- `src/main/res/` – Ressourcen

## Funktionen

1. Log-Dateien vom Gerät oder aus Google Drive importieren
2. Webalizer-Format parsen (Apache-Style Log)
3. Statistiken anzeigen (Besucher, Seitenaufrufe, Bandbreite)

Der Import nutzt den Android-Systemdialog (Storage Access Framework). Für Google
Drive muss die Drive-App auf dem Gerät installiert und mit einem Google-Konto
verbunden sein. Im Dateidialog wird Drive über das Seitenmenü ausgewählt. Die App
benötigt dafür keine Speicherberechtigung und keine Google-Zugangsdaten.

## Geplant

- Grafische Auswertung

## Android-Setup

```bash
./gradlew build
```
