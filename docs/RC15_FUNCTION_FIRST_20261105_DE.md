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
