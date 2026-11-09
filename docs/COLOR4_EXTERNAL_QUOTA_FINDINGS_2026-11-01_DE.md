# Color4 LAN „client quota exceeded“ – externe Evidenz, 1. November 2026

**Quellenrecherche, keine Aussage über exakt unsere Firmwareversion.** Hier dokumentierte Fälle betreffen unterschiedliche Modelle und Zeitpunkte.

## Eindeutige Color4-Nachweise
- [Home Assistant Issue #103677](https://github.com/home-assistant/core/issues/103677): Log vom 1.12.2023 zeigt `AsyncBulb ... model=color4`, eine `get_prop`-Anfrage und sofort `{"code":-1,"message":"client quota exceeded"}`. Der betreffende Nutzer konnte ein anderes LAN-Verfügbarkeitsproblem lösen, indem er nach Änderungen in Mi Home die LAN-Steuerung in Yeelight wieder aktivierte. Dies ist **nicht** als allgemeiner Fix für alle Quota-Sperren bestätigt.
- [Yeelight Forum / Color4 Stabilität, Seite 6](https://forum.yeelight.com/t/topic/21010?page=6): Ein Entwickler berichtet, dass bestimmte Color4-Beta-Firmwares `get_prop` fehlerhaft behandeln und dass offene LAN-Sockets (max. vier) zu dauerhaften Verbindungsproblemen führen können. Er verwendet weniger automatisches Property-Polling, SSDP als Alternative und schließt Verbindungen kontrolliert.
- [Yeelight Forum / Color4 Stabilität, Seite 5](https://forum.yeelight.com/t/topic/21010?page=5): Nach überarbeiteter Socketverwaltung seien mehrere Lampen stabiler; Color4 hatte firmwareabhängige Sonderprobleme.
- [Home Assistant Issue #140033](https://github.com/home-assistant/core/issues/140033): Ein Color4-Nutzer berichtet nach einem Hersteller-Firmwareupdate und erneutem Hinzufügen in HA von wiederhergestellter Funktion. Diese Einzelfallerfahrung belegt weder die genaue Firmwareversion noch einen universellen Fix.

## Andere Modelle: kein vorschneller Analogieschluss
- [Yeelight Forum „client quota exceeded“](https://forum.yeelight.com/t/topic/15756): Yeelight bestätigte 2022 für die dort untersuchten Lampen einen 32-Bit-Timerüberlauf nach 2^32 ms, der das Auffüllen der LAN-Quota verhindert. Ein Stromneustart umging den Fehler, ein Firmware-Fix war damals wegen Updateproblemen nicht verfügbar. **Betroffenes Modell im Beleg: ceiling1, nicht color4.**
- [Yeelight Support Music Mode](https://forum.yeelight.com/t/topic/29145): `set_music` erlaubt einen Rückkanal ohne normale LAN-Befehlsquote, jedoch können tatsächliche Reaktionsraten modellabhängig sein.
- [Home Assistant Yeelight Dokumentation](https://www.home-assistant.io/integrations/yeelight/): beschreibt das 60/min-Limit, Streaming-Musikmodus und bekannte Schwierigkeiten nach Verbindungsverlust.

## Konkrete Konsequenz für Loft DJ
1. Nur begrenzte manuelle `set_*`-Befehle; keine RGB-Bursts, keine automatische /24-TCP-Suche.
2. **Keine obligatorische automatische `get_prop`-Abfrage nach jedem manuellen Color4-Ein/Aus-Befehl.** Gültiges `set_power`-ACK nur als `ACK_ONLY`, nie als bestätigte physische Zustandsmessung ausgeben. Explizite Statusprüfung bleibt separat.
3. Ausgelöste Firmware-Sperre je Gerät persistieren, ohne sie durch automatische Reconnect-/Statuswiederholungen zu verschlimmern.
4. Schnelle Lichtläufe über modellbestätigte `set_music`- oder native `start_cf`-Methoden. Kein stiller Normal-LAN-Beat-Fallback auf Color4.
5. In der Test-APK Version 0.2.8-rc1 (Build 19) zunächst nur eine intakte Color4 vorsichtig prüfen. Kein Test an Wellness, kein Firmwareflash.

## Was nicht belegt ist
- Eine veröffentlichte universelle Color4-Firmware, die *alle* LAN-Quotaprobleme behebt.
- Dass Color4 die ceiling1-Timerüberlaufursache besitzt.
- Dass eine einzelne Color4 bei der Nutzerin/dem Nutzer die konkrete `get_prop`-Beta-Firmwarestörung besitzt.

Software-CI und APK-Signatur sind keine Hardware-Abnahme. Auf `main` existiert weiterhin das bereits gemergte v0.2.7-Release; diese Erprobung findet ausschließlich im freigegebenen PR #31 statt.
