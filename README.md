# AccessLog Insight

Android-App zur lokalen Auswertung und Sicherheitsanalyse von
Webserver-Zugriffslogs.

## Projektstruktur

`app/` – Android-App
- `src/main/java/` – Kotlin-Quellcode
- `src/main/res/` – Ressourcen

## Funktionen

1. Eine oder mehrere Log-Dateien vom Gerät oder aus Google Drive importieren
2. ZIP-Archive mit mehreren Logs sowie einzelne GZIP-Logs sicher entpacken und auswerten
3. Webserver-Zugriffslogs im Apache-/Nginx-Format parsen
4. Statistiken anzeigen (Besucher, Seitenaufrufe, Bandbreite)
5. Auffällige Requests und Adressen regelbasiert hervorheben
6. IP-Adressen antippen und Regions-, ASN- sowie RDAP/WHOIS-Netzdaten anzeigen
7. Vollständige Rohlog-Zeilen auffälliger IPs anzeigen, kopieren oder zur KI-Analyse teilen
8. Ergebnisbereiche über einklappbare Abschnittsüberschriften übersichtlich steuern
9. Zentrales Anwendungsmenü für Import, Tagesdiagramm, Hilfe und App-Informationen
10. Horizontales Tagesdiagramm für Hits, Files, Pages und Visits; auffällige Requests sind als roter Anteil innerhalb der Hits markiert
11. Rote Hit-Anteile antippen und die auffälligen Ereignisse des jeweiligen Tages mit verlinkten IP-Adressen öffnen

Die Sicherheitsauswertung markiert unter anderem sensible Scan-Ziele,
Traversal-/Injection-Muster, bekannte Scanner-User-Agents, ungewöhnliche
HTTP-Methoden und Adressen mit vielen 4xx-Antworten. Die Hinweise sind
heuristisch und stellen keinen automatischen Nachweis eines Angriffs dar.
Die Top-20-Liste führt auffällige IPs über alle importierten Dateien zusammen
und zeigt Treffer, betroffene Dateien, Zeitraum sowie eine automatisch geladene,
ungefähre Orts-/Regionszuordnung.

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

## Urheber und Support

Copyright © 2026 Herbert Sablotny

Feedback und Support: [support@ways4.biz](mailto:support@ways4.biz)

Repository: [smartDevel/webalizer-android-app](https://github.com/smartDevel/webalizer-android-app)

## Lizenz

Der aktuelle Stand ab Version 1.7 steht unter der
[PolyForm Noncommercial License 1.0.0](LICENSE).
Dritte dürfen die Software nur für nichtkommerzielle Zwecke nutzen, ändern und
weitergeben. Die kommerzielle Nutzung durch Dritte ist nicht gestattet; die
kommerziellen Rechte verbleiben ausschließlich beim Urheber Herbert Sablotny.

Diese Lizenz ist eine Source-available-Lizenz und keine Open-Source-Lizenz im
Sinne der Open Source Initiative.

Hinweis zur Lizenzhistorie: Bereits unter einer früheren Lizenz wirksam
erteilte Rechte bleiben für den jeweils damaligen Softwarestand bestehen.

## Haftungsausschluss

Die Software wird im gesetzlich zulässigen Umfang ohne Gewährleistung für
Richtigkeit, Vollständigkeit, Verfügbarkeit oder Eignung für einen bestimmten
Zweck bereitgestellt. Der Urheber haftet im gesetzlich zulässigen Umfang nicht
für unmittelbare oder mittelbare Schäden aus der Nutzung oder Nichtverfügbarkeit
der Software.

Sicherheitsbewertungen, Herkunftsangaben und Handlungsempfehlungen sind
automatisierte beziehungsweise heuristische Hinweise. Sie sind kein Beweis für
einen Angriff und ersetzen keine fachkundige Prüfung. Maßnahmen wie IP-Sperren,
Firewall-Regeln oder rechtliche Schritte müssen vor ihrer Umsetzung
eigenverantwortlich geprüft werden. Zwingende gesetzliche Haftungsansprüche
bleiben unberührt.

## Geplant

- Grafische Auswertung

## Android-Setup

```bash
./gradlew build
```
