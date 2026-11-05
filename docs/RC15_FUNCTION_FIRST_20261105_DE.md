# RC15 · Live-DJ wieder starten + direkte Bedienung zuerst

Basis: RC14 PR #66 exact 4bdcf9d35a10841b36f32115d5575caedf9cf8e8. Owner feedback: Live-DJ nicht auswählbar bei 1/4 Lampen; Musikseite zu lang; Geräteübersicht ohne direkte Ein/Aus-Befehle. Owner erweitert den gewünschten DJ-Betrieb auf alle nachweislich geeigneten Lampen statt 4er Limit.

Sicherheit: keine normale LAN-RGB-Alternative für Music-Mode, nur explizit berichtete set_music+set_rgb Fähigkeiten; adaptive Helligkeit nur mit gemeldetem set_bright; niemals unbelegte globale Kompatibilitätsbehauptung für White-only/unklare Lampen. Pro-Lampe eigene Vorprüfung, persistiertes Sicherheitsjournal, getrennte Music-TCP-Sockets, Abbruch bei Fehler/ungeklärtem Stopp, manuelle Wiederherstellung statt Retry. Kein Änderung an AGENT #54, anderen Draft-PRs, main, APK-Geräten oder OEM. Diese Branch dient ausschließlich source-only CI.

UI: Startfunktion oben und gesonderte leicht verständliche Live-DJ-Sperrbegründung/Audio starten; alte Hardwaretests default kollabiert und explizit als Diagnose bezeichnet. Ein-/Aus direkt auf jeder Geräteübersichtskarte mit bestehendem bestätigtem Power-Transport und per-IP Sperre; keine automatische Statusabfrage auf Color4.

Testaufwand: neue deterministische Regressionen für 1/4/N gemischt, ungültige/unbestätigte Fälle und dynamischen Farbkreis; Android unit tests + debug build und Compilerfixes. RC15 versionCode 33 + owner-only sign gate nach exaktem grünen PR CI. Keine Freigabe/Merge/Release.

## Implementierter RC15-Quellstand

- Live-DJ hat in einer Ein-Lampen-Sitzung nicht mehr die strengere alte Acht-Sekunden-Probe als zweite Startbedingung. Startablehnung aus Auswahl, Support-Liste, unbestätigtem music_on und Quota wird separat lesbar angezeigt.
- Keine starre Vierergrenze mehr. Alle ausdrücklich per Yeelight SUPPORT mit set_music und set_rgb bestätigten Musiklampen können einzeln oder als beliebig große ausgewählte Gruppe betrieben werden; Unsupported/Unknown wird nicht optimistisch als kompatibel behandelt. Lokale Taste wählt alle bekannten Music-/RGB-Lampen ohne Lampenbefehle. Die Gruppensitzung prüft vor dem Start jede Lampe auf eingeschalteten Zustand, journalisiert jedes set_music und schließt alle Kanäle auch nach Fehlern mit unabhängiger music_on-Leseprüfung.
- Dynamische RGB-Steuerung mit absoluter RMS-Kalibrierung auf aktuellen gemessenen Pegel (auch ohne Pixel-9-AGC); gleitende Geschwindigkeiten zwischen ruhig/mittel/laut statt zwei harter Zeitstufen; sichtbare RGB-Intensitätsmodulation, falls set_bright im Music-Betrieb keine optische Wirkung zeigt. set_bright wird nur mit ausdrücklich berichteter Fähigkeit übertragen.
- Live-DJ/Audio-Start und Auswahl oben auf der Musikseite. Die separaten Diagnose-/Hardwaretests und ausführlichen Mikrofoneinstellungen werden zunächst eingeklappt und nicht gelöscht. Ungesichertes Music-Ende bleibt auch bei eingeklappter Diagnose sichtbar.
- Direktes Ein/Aus je Gerätekarte mit bestehender Power-Quotakontrolle; Color4 ohne automatisch zusätzliche get_prop-Abfrage. Keine Steuerung bei aktivem Music-Kanal oder offener gerätebezogener Wiederherstellung.
- Mikrofonanalyse mit 50% überlappenden, unveränderten 1024-PCM-FFT-Fenstern und neuen Messpunkten alle 32 statt 64 ms; keine automatische Gain-Aktivierung. Synthetischer 130-BPM-PCM-Regressionsfall. Visuelle Beat-Synchronisation bleibt auf echter Hardware separat zu beurteilen.
- RC15 = versionCode 33 / 0.2.8-rc15. Signierung ausschließlich nach exakter grüner PR #67 CI und manuellem Owner-Dispatch. Keine Release, keine Änderungen an Yeelight Classic, anderen Branches oder AGENT #54.

## RC15 signierter Owner-Build: Guard-Korrektur

Der RC15-Workflow enthielt im ausdrücklich signierten Owner-Dispatch eine falsch kopierte Branch-ID in der bedingten RC15-Prüfung **und** im PR-HEAD-Check. Damit wäre für den echten RC15-Branch die Prüfung übersprungen worden. Beides ist auf `chat/rc15-live-dj-reliability-controls-20261105` korrigiert; die existierenden Guards auf Owner, Draft-PR #67, Base, exakten HEAD, Version 33, mindestens einen grünen exakten Pull-Request-CI-Lauf bleiben unverändert. Ein signiertes Artefakt kommt nur nach manueller Owner-Freigabe und erfolgreichem Workflow, nicht durch diesen Quellcode-Commit. Der konkrete Couchtisch-Sperrstatus ist App-lokal und kann aus einem Screenshots von RC7 nicht festgestellt werden; keine Quarantäne oder music_on-Recovery-Markierung wurde gelöscht.

## Nachtrag: Music-Sperren sicher diagnostizieren

Eine alte gespeicherte `music-light-session` kann die Live-DJ-Zulassung trotz gesunder aktuell gewählter Lampen global sperren. Neue lokale Live-DJ-Blockerdiagnose nennt jetzt diese globale Sitzung ausdrücklich und zeigt daneben jede betroffene Lampe mit Quota, `music_on`-Stop-Journal, Recovery oder fehlendem `set_music`/`set_rgb`. Keine automatische Freigabe.

Bei mehreren Lampen gibt es für jede noch offene `minimal-color4-music-unconfirmed-ips`-Vormerkung eine eigene Aktion `music_on nur lesen`. Sie macht EINEN budgetierten `get_prop`-Read pro ausdrücklich gewählter Lampe, ohne normale LAN-Schreibaktionen und ohne Wiederholung innerhalb von 10 s. Nur exakt `music_on=0` entfernt ausschließlich diese lokale Stop-Journal-Vormerkung. Globale Altsitzungs-Marker, andere Lampen und Firmware-Quarantäne werden niemals durch diesen Schritt gelöscht. Bei Quota, aktivem Music-Kanal oder unbekannter Antwort bleibt alles unverändert. Legacy-Einzeltest-Einstieg bleibt bestehen.

Wichtige Hardware-Abgrenzung: aktuelle gerätespezifische Android-SharedPreferences und reale `music_on`-Werte liegen nicht in GitHub. Ein grüner Offline-Test bestätigt nicht, welche Lampe derzeit gesperrt ist. Die RC15-Signierung setzt weiterhin Owner-Dispatch auf exakten grünen PR-HEAD voraus.

## RC15.1 — ausdrücklich bestätigte Neuübernahme einer hängenden Music-Sitzung

Die dokumentierte Yeelight-Music-Verbindung ist ein von der Birne geöffneter TCP-Rückkanal zum bisherigen Controller, keine übertragbare Cast-Sitzung. Der Anwender kann im Live-DJ-Bereich eine einzelne bereits gewählte Music-/RGB-Lampe ausdrücklich zur **Neuübernahme** auswählen und den Abbruch einer möglicherweise von Yeelight Classic/anderen Apps gehaltenen Sitzung bestätigen. Der Vorgang führt einen begrenzten get_prop-Read aus: bei `music_on=0` wird kein Stop-Befehl versendet; bei bestätigtem `music_on=1` wird vor dem ersten Schreibbefehl ein per-IP-Journal plus persistierter 120-s-Cooldown gespeichert, dann EIN `set_music(0)` über die vorhandene quota-bewusste TCP-Gate-Routine versendet und music_on erneut gelesen. Nur ACK + `music_on=0` entfernen die gerätespezifische lokale Stop-Vormerkung. Fehlt ein eindeutiger Wert, bei Quota, Socket-Aktivität oder einem Fehler: Abbruch ohne Auto-Retry, ohne Not-Fallback oder globales Sperrmarkierungs-Löschen. Anschließend muss Live-DJ bewusst erneut gestartet werden. Dieses Feature ist ohne echten Gerätetest nicht als Firmware-/Classic-Lösung abgenommen.

Build 34 / 0.2.8-rc15.1; bestehendes PR #67 Draft und Owner-only signierte GitHub-Test-Artefakt-Gates nutzen weiterhin den exakten PR-HEAD-Nachweis.
