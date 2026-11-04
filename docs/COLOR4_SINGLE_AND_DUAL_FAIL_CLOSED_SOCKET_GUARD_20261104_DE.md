# Color4: Single/Dual fail-closed + TCP-Initialisierungsressourcen – 2026-11-04

## Belegte Ausgangslage

Alle drei Esstisch-Color4 waren bei funktionierender WLAN-/Cloud-/Google-Home-Steuerung lokal nicht bedienbar, sogar nach Loft-DJ-Force-Stop. Nur einzelne Lampen-Stromresets brachten den Hersteller-LAN-Bedienweg zurück. Ein Music-Preflight lieferte client quota exceeded (wahrscheinlich .136). Quota/Socket/Refill-Root-Cause ist trotz OEM-Firmwareanalyse noch nicht eindeutig.

## Behobene konkrete Produktlücken

PR #50 deaktivierte nach dem Vorfall den Dual-Musiktest, aber NICHT den weiterhin über UI und Controller erlaubten Einzel-Color4-Musiktest. In dieser Quelle sind jetzt BEIDE Live-Akzeptanzgates standardmäßig false (Safety-Pause, ohne dynamischen Override und ohne Bypass durch UI-Callbacks). Frühere Einzel-/Dual-Kompatibilitätsprüfungen bleiben als explizit parametrisierte pure JVM-Simulation testbar, ohne reales Armieren.

YeelightTcpSessions schließt jetzt auch ein Socketobjekt, dessen connect/initiale Socketoptions-Einstellung fehlgeschlagen ist oder dessen Stream-Initialisierung nach erfolgreichem connect scheitert. Dieser Fehler trat bisher vor dem Map-Eintrag und vor dem normalen Fehlerpfad auf und konnte somit ungemanagte Ressourcen hinterlassen. Kein Retry und kein zusätzlicher LAN-Befehl. Je ein synthetischer Test erzwingt defekten Input-/Outputstream und beweist, dass das Socket geschlossen bleibt und kein Map-Eintrag zurückbleibt.

Die bereits in PR #50 implementierte serielle Music-Preflight-Prüfung und sofortige Freigabe genau einer Ziel-IP-TCP-Verbindung bleibt bestehen. Die Firmware-Quarantäne-Journals und Recovery-Logik bleiben unverändert. Kein neuer Live-Hardwaretest, kein Flash, Merge oder signierter Release.

## Qualifikationsgrenze

Grüne CI belegt nur Android-Code/Offline-Regressionsverhalten, nicht dass Color4 2.1.7_0039 nun physisch immun gegen einen LAN-Firmwarelockout wäre. Eine erneute aktive Musiklicht-Freigabe benötigt eine gesonderte bewiesene Ursache beziehungsweise von Nutzer freigegebene risikoarme Realabnahme.