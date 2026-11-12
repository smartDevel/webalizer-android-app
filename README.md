# Webalizer Android App

Android Anwendung zur Auswertung von Webalizer‑Logdateien.

## Projektstruktur

`app/` – Android-App
- `src/main/java/` – Kotlin-Quellcode
- `src/main/res/` – Ressourcen

## Funktionen

1. Eine oder mehrere Log-Dateien vom Gerät oder aus Google Drive importieren
2. ZIP-Archive mit mehreren Logs sowie einzelne GZIP-Logs sicher entpacken und auswerten
3. Webalizer-Format parsen (Apache-Style Log)
4. Statistiken anzeigen (Besucher, Seitenaufrufe, Bandbreite)
5. Auffällige Requests und Adressen regelbasiert hervorheben
6. IP-Adressen antippen und Regions-, ASN- sowie RDAP/WHOIS-Netzdaten anzeigen

Die Sicherheitsauswertung markiert unter anderem sensible Scan-Ziele,
Traversal-/Injection-Muster, bekannte Scanner-User-Agents, ungewöhnliche
HTTP-Methoden und Adressen mit vielen 4xx-Antworten. Die Hinweise sind
heuristisch und stellen keinen automatischen Nachweis eines Angriffs dar.

Der Import nutzt den Android-Systemdialog (Storage Access Framework). Für Google
Drive muss die Drive-App auf dem Gerät installiert und mit einem Google-Konto
verbunden sein. Im Dateidialog wird Drive über das Seitenmenü ausgewählt. Die App
benötigt dafür keine Speicherberechtigung und keine Google-Zugangsdaten.
Mehrere ausgewählte Dateien, alle unterstützten Einträge aus ZIP-Archiven und
einzeln komprimierte GZIP-Logs werden zu einer gemeinsamen Statistik
zusammengeführt. Der Import ist auf 20 Quellen, 100 ZIP-Einträge, 100 MB je
Logdatei und 250 MB entpackte Daten insgesamt begrenzt.

IP-Detailabfragen erfolgen ausschließlich nach dem Antippen einer öffentlichen
IP-Adresse. Dazu fragt die App Regions-/ASN-Daten bei `ipwho.is` und
RDAP-Netzdaten bei `rdap.org` ab. Private, lokale und reservierte Adressen werden
nicht an diese Dienste übertragen. Standortdaten sind technisch bedingt nur
ungefähre Zuordnungen.

## Geplant

- Grafische Auswertung

## Android-Setup

```bash
./gradlew build
```
