# RC15 · Live-DJ wieder starten + direkte Bedienung zuerst

Basis: RC14 PR #66 exact 4bdcf9d35a10841b36f32115d5575caedf9cf8e8. Owner feedback: Live-DJ nicht auswählbar bei 1/4 Lampen; Musikseite zu lang; Geräteübersicht ohne direkte Ein/Aus-Befehle. Owner erweitert den gewünschten DJ-Betrieb auf alle nachweislich geeigneten Lampen statt 4er Limit.

Sicherheit: keine normale LAN-RGB-Alternative für Music-Mode, nur explizit berichtete set_music+set_rgb Fähigkeiten; adaptive Helligkeit nur mit gemeldetem set_bright; niemals unbelegte globale Kompatibilitätsbehauptung für White-only/unklare Lampen. Pro-Lampe eigene Vorprüfung, persistiertes Sicherheitsjournal, getrennte Music-TCP-Sockets, Abbruch bei Fehler/ungeklärtem Stopp, manuelle Wiederherstellung statt Retry. Kein Änderung an AGENT #54, anderen Draft-PRs, main, APK-Geräten oder OEM. Diese Branch dient ausschließlich source-only CI.

UI: Startfunktion oben und gesonderte leicht verständliche Live-DJ-Sperrbegründung/Audio starten; alte Hardwaretests default kollabiert und explizit als Diagnose bezeichnet. Ein-/Aus direkt auf jeder Geräteübersichtskarte mit bestehendem bestätigtem Power-Transport und per-IP Sperre; keine automatische Statusabfrage auf Color4.

Testaufwand: neue deterministische Regressionen für 1/4/N gemischt, ungültige/unbestätigte Fälle und dynamischen Farbkreis; Android unit tests + debug build und Compilerfixes. RC15 versionCode 33 + owner-only sign gate nach exaktem grünen PR CI. Keine Freigabe/Merge/Release.
