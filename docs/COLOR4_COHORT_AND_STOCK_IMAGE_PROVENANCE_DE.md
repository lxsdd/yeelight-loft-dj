# Color4 2.1.7_0039: 50-Tage-Kohorte, echte Gegenbelege und OEM-Image-Beschaffung

Stand: 01.11.2026 · Draft-Forschung PR #30 · **keine Geräte-/Cloud-Schreibfreigabe**.

## Der neue Besitzerbefund ändert die Hypothesenlage

**Alle anderen Lampen waren garantiert ebenfalls länger als 50 Tage durchgehend mit Netzstrom versorgt.** Bei „Wellness“ und „Couchtisch“ ist eine echte Geräteantwort \`client quota exceeded\` berichtet; diese beiden Geräte haben die originale Firmware \`2.1.7_0039\`. Bei den weiteren Lampen sind **Modell-/Firmwareidentität, seit Boot gemessene CPU-Uptime und erfolgreiche lokale LAN-Antworten während/ nach dem Quota-Fenster nicht belegt**.

Daraus folgt:

1. **Dauerstromversorgung >50 Tage ist keine hinreichende Ursache.** Sie kann einen 32-bit-Uptime-Überlauf ermöglichen, belegt aber nicht, wann die Firmware zuletzt intern neu gestartet wurde.
2. **Ein noch verfügbarer Quota-Rest und eine spätere Nutzungs-/Befehlslast sind verschiedene Variablen.** Wenn ein Refill-Timer beim Wrap stehen bleibt, kann eine andere Lampe mit bisher unbenutztem Restbudget möglicherweise noch Kommandos beantworten. Dies ist ein plausibles *Modell*, keine Beobachtung der realen color4-Implementierung.
3. **Die offizielle App oder Google Home können Cloud-Kommandos verwenden.** Erfolg dort beweist nicht, dass der lokale TCP-Port 55443 / dessen Quota noch arbeitet. Für die Hersteller-App existiert ein privat erhobener PCAPdroid-Befund mit Cloud-Verbindungen und ohne sicher nachgewiesenen LAN-Control-Flow. Bei anderen Lampen nicht ohne Befund \`LAN_OK\` eintragen.
4. **Lange Uptime ist nur eine gemeinsame Exposition, keine ausreichende Diskriminante** zwischen den Symptomen; ein lokaler Begleitfaktor (ältere/mehrere TCP-Sessions, Anzahl \`get_prop\`/Farbkommandos, andere Steuerprogramme) könnte den Fehler erst sichtbar gemacht haben.
5. **Kein unverändertes System ohne Langzeit-Protokollierung als negative Kontrolle behaupten.** Es gibt zum jetzigen Zeitpunkt nur zwei direkt bestätigte Quota-Fälle und einen gemeinsamen Stromdauerhinweis.

### Neues externes, direkteres color4-Beweismaterial

- **Home Assistant Issue #103677, 01.12.2023:** Eine von \`model=color4\` identifizierte Lampe antwortete auf einen einzelnen \`get_prop\`-Aufruf via TCP 55443 unmittelbar mit \`{"id":1,"error":{"code":-1,"message":"client quota exceeded"}}\`. Das belegt **eine reale Firmware-Quotameldung in der Modellfamilie**, weder Firmwareversion noch CPU-Uptime noch Ursache. https://github.com/home-assistant/core/issues/103677
- **Hersteller 07.06.2022:** Quota-Refill-Timer nach \`2^32\` ms nicht mehr rearmed, bezogen auf ursprünglich andere betroffene Plattform; Fix damals aufgrund Cloud-Service-Hindernis nicht verteilt. https://forum.yeelight.com/t/topic/15756/18
- **YLDP13YL/1S, 07.02.2025:** Exakt \`2.1.7_0039\`, LAN-Fehler nach ca. 1.100–1.200 Betriebsstunden, Power-Cycle als temporäre Lösung; ein Benutzer berichtete, es betreffe bei ihm *alle* seine 1S. https://forum.yeelight.com/t/topic/36416

**Gewichtung:** Zeitmuster und Quota-Meldung stützen die OEM-Bug-Hypothese stark. Weder der 2023-Einzelfall noch der 2025-Kohortenbericht identifiziert die konkrete faulty Routine. Kein funktionierender OEM-Patch darf als bereits vorhanden gelten.

## Implementierte, lokale Kohortenanalyse

\`Color4CohortAnalysis\` mit \`Color4CohortResearchUi\` unter **Geräte & Netzwerk prüfen** analysiert anonymisierte Statusklassen, ohne Lampenanfragen. Unterscheidet:

- \`WALL_POWER_OVER_50_DAYS_ONLY\` von \`VERIFIED_FIRMWARE_UPTIME_OVER_49_DAYS_17_HOURS\` bzw. unbekanntem internem Reboot.
- Exakte \`client quota exceeded\`-Antwort, bestätigten lokalen Befehls-ACK, anderen LAN-Fehler und keine LAN-Beobachtung.
- Optional protokollierte lokale Befehlslast (noch nicht automatisch ermittelt).
- Gleiches bestätigtes \`2.1.7_0039\` vs. Firmware unbekannt/abweichend.

Die UI zeigt **zwei anonymisierte bekannte Fälle**; andere Lampen werden ausdrücklich als **nicht durch LAN bewiesene** Vergleichsgeräte geführt. Der Anwender kann einen wirklich durch vorhandene unabhängige Belege nachgewiesenen LAN-Vergleich ausdrücklich kennzeichnen. Die Eingaben bleiben flüchtig (kein Geräte-, IP- oder MAC-Export, kein Upload, kein Telemetriecode). Auch bei gleichen Versions- und ACK-Daten beweist die Analyse **nie** den Fehler oder seine Abwesenheit im Original-Binary.

Elf JVM-Regressionen schützen insbesondere gegen das Verwechseln von Google-Home/Cloud-Erreichbarkeit mit LAN-ACK, unbekannte Firmware, andere Netzwerkfehler, nicht gemessene CPU-Uptime, übermäßige/duplizierte Pseudo-Slots und falsche Kausalität durch gleiche Stromdauer.

## Konkreter Weg zur Originalfirmware – neuer Xiaomi-Cloud-Befund

Das unabhängige Projekt \`syssi/esphome-yeelight-ceiling-light\` dokumentiert nun **offizielle Xiaomi-Firmwaremetadaten**:

- \`/home/latest_version\` liefert laut Nachweis für **verschiedene andere ESP32-WLAN-Yeelights** auf Eingabe \`{"model":"<model>"}\` aktuelle \`version\`, \`url\`/\`safe_url\`, \`md5\`, Changelog. Der Endpunkt benötigt laut Dokumentation einen eingeloggten Xiaomi-Account, prüft aber für diesen Abruf **nicht** ein bestimmtes Geräte-\`did\`. **Die Verfügbarkeit für \`yeelink.light.color4\` muss noch überprüft werden.**
- **Achtung stiller Versionsfehler:** Bei Anfragen nach älteren Versionen lieferte Xiaomi beim genannten Forscher mehrfach **die aktuelle** Version zurück und meldete keinen Fehler. Der Versionsecho-Vergleich ist deshalb zwingend; MD5 kann eine Datei der *falschen* Version trotzdem korrekt bestätigen.
- Signierte URLs enthalten Geheimnisse/zeitbegrenzte Tokens. **Keine URLs, Xiaomi-Anmeldedaten, Cookies oder Firmware-Binärdateien in öffentliche Repos, CI-Artefakte oder Issues übernehmen.**
- Xiaomi-Endpunkt/Metadaten und Datei-MD5 authentifizieren **nicht** allein OEM-Signaturen, Chip/Platine, Partitionierung, Secure Boot oder einen Restore. Image-Aktualisierungen sind ein **anderer**, explizit nicht freigegebener Cloud-Endpunkt.

Quellbericht mit dokumentierten Kontrollfällen: https://github.com/syssi/esphome-yeelight-ceiling-light/blob/main/docs/xiaomi-cloud-firmware.md

### Neu: offline, fail-closed Originalimage/Metadaten-Validator

\`tools/color4_stock_artifact_audit.py\` führt **keinerlei Netzwerkanfrage** aus. Für ein separat und legitim bezogenes, lokal gespeichertes Xiaomi-Metadaten-JSON und die dazugehörige lokale Originaldatei:

\`\`\`bash
python3 tools/color4_stock_artifact_audit.py PRIVATE_META.json PRIVATE_COLOR4_0039.bin
\`\`\`

Prüft ausschließlich:

- exakte Antwortversion \`2.1.7_0039\` (oder ausdrücklich gesetzter \`--expected-version\`) statt einer Datei mit gewünschtem, aber unpassendem Dateinamen;
- optional vorhandenes \`model\`-Feld nur auf exakt \`yeelink.light.color4\`; **ohne** Feld kann es keine Modellidentität bestätigen;
- MD5 der lokal gespeicherten Datei gegen die empfangene Metadaten-MD5, SHA-256 zur späteren unveränderlichen Inventarisierung;
- höchstens 16 KiB lokale Metadaten und 32 MiB Binärdatei, eindeutig validierte Schlüssel, Fehlertypen ohne Dump von Token/URL;
- Rückgabe ausdrücklich \`installable=false\`, \`oem_signature_verified=false\`, \`proves_quota_fix=false\` **auch bei bestandenem Vergleich**.

Diese Meta-Binär-Prüfung ergänzt den bereits vorhandenen \`color4_fw_image_info.py\`-ESP32-Binärformat-Inspektor; sie bietet **keinen** Code zum Download, Login, OTA-Start, Schreibzugriff oder Geräteflash. Zwölf zusätzliche synthetische Python-Negativ-/Positivtests sind im bestehenden öffentlichen CI-Glob enthalten.

## Verbleibende fachliche Gates (nicht durch Softwaretest ersetzbar)

1. Über einen **autorisierten und gesicherten Hersteller-Kanal** einen tatsächlichen \`color4 2.1.7_0039\`-Binarybestand mit Versionsantwort und Prüfsummen erfassen, ohne Nutzertokens öffentlich oder an fremde Tools zu geben.
2. Firmware-Header, Partition/Schreibregeln, Quota-Refill-Funktion und mögliche Signaturen **offline** nachweisen. \`model=color4\` und ein 1,43-MiB-Indexeintrag sind noch kein OEM-Image.
3. Nur mit separat freigegebenem, vollständig rücksicherbarem Laborgerät – und dokumentierter Google-/Yeelight-/HomeKit-Kompatibilität – einen minimalen Refill-Timer-Fix entwickeln. Vorher keine Images auf eine Lampe schreiben.
4. Das parallele Produkt-PR #31 soll weiterhin die **gesondert beobachtete** Quota-Last schützen, unabhängig von der Root-Cause-Frage. Keine automatische Stromtrennung, Cloud-OTA oder Kommandoflut.

**Fazit:** Die Kohorte macht den einfachen Schluss „jede Lampe mit 50 Tagen Stromversorgung muss sofort ausfallen“ unzulässig. Der originale \`color4\`-Timerdefekt bleibt gut begründet, wird aber erst nach konkreter OEM-Binär-/Uptime-Evidenz belegbar. Das eigentliche Firmwareziel bleibt eine originalkompatible Reparatur, nicht das Abschalten der Quoten.
