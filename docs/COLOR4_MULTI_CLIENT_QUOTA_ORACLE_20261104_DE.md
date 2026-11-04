# Color4: Mehrclient-Quota-Differentialdiagnose (nur Offline, 04.11.2026)

## Problem und Befundgrenze

Ein konservatives App-internes Limit von 24 klassischen LAN-Versuchen pro 60 Sekunden mit mindestens 1500 ms Abstand ist **kein** Nachweis, dass die echte Firmware-LAN-Quota nicht erreicht werden kann: Drittanbieter wie Yeelight Classic, andere gleichzeitige TCP-Verbindungen, bereits verbrauchte Firmwarebudgets und Firmwarezustandsfehler sind fuer die App nicht beobachtbar. Die in der offiziellen Inter-Operation-Spezifikation beschriebenen 4 Verbindungen, 60 Kommandos/Minute pro TCP-Verbindung und 144 gesamt sind hier lediglich **synthetische Modellannahmen**, keine aus Color4 2.1.7_0039 abgeleiteten Zaehler.

Neues Offline-Modul `tools/color4_multi_client_quota_oracle.py` erfasst pro simuliertem Geraet eine lokale App-Quota *ueber alle eigenen Verbindungen*, gleichzeitig externe Clients, globale Modellgrenzen, Handshake-freies Reconnect-Verhalten, `get_prop` und `set_music` als klassische Steuerkommandos. Es gibt **keinen** TCP-/UDP-Zugriff, keine Scans, kein echtes Device, keine Zugangsdaten und keine Firmwarebytes. Nicht modellierte schnelle `music_frame` und Chroma-UDP-Frames werden explizit abgelehnt, statt in den klassischen Steuerweg zurueckzufallen.

## Warum die Alternative wichtig ist

Die reproduzierbare Negativprobe benutzt drei externe klassische TCP-Sitzungen mit 60 + 60 + 24 erfolgreichen modellierten Anfragen innerhalb eines Modellfensters. Ein vierter, neuer App-Client kann trotz **nur eines eigenen** klassischen Versuchs eine modellierte globale Quota-Ablehnung erhalten. Dies ist ein **Gegenbeispiel zur Aussage, dass eine niedrige App-Rate die Firmwarelimit-Ablehnung ausschliesst**, aber kein Beweis fuer die konkrete Realursache oder die tatsaechlichen Quotas der Color4.

Weitere positive/negative Tests:
- pro-Verbindung- und globales Budget voneinander unabhaengig; hoechstens vier Modell-Sitzungen
- Verbindungsschliessen oeffnet einen Slot, setzt aber globale oder lokale App-Zaehlung nicht zurueck
- monotone virtuelle Zeit und Multi-Wrap 64-bit-sichere **Referenz** (kein Firmware-Timernachweis)
- lokale Mindestabstaende, max. 24 Appversuche, 5000 UI-Aufrufe ohne versteckte Warteschlange
- eingespielte eindeutige Quota-Rejection erzwingt lokale terminale Quarantaene
- eingespielter *ambiguous-send* Timeout verbraucht einen Versuch und sperrt automatische Wiederholung auch nach Reconnect
- 14 voneinander unabhaengige simulierte Lampen: eine Quarantaene sperrt die restlichen 13 nicht
- keine Annahme, dass Google-Home-Cloud-Erreichbarkeit die lokale LAN-Verbindung bestaetigt

Die Statuswerte `MODELED_*` sind Annahmen des Gegenmodells und **keine** realen Geraeteantworten. `INJECTED_*` sind nur ausdruecklich eingespeiste negative Testsignale, **keine** echten Mitschnitte. Weder eine alleinige Quellenanalyse noch ein positiver CI-Lauf erlaubt Chroma-Live-Traffic, Quota-Bypass oder Firmwaremodifikation.

## Entscheidung fuer die naechste Forschungsphase

Erst eine privat zugreifbare, SHA-256-verifizierte Originalfirmware kann den OEM-Fehlervektor an konkrete Xtensa-Callsites und die Refill-Zustandsmaschine anbinden. Alternativ wuerde ein spaeter einzeln genehmigter, minimaler LAN-Beobachtungsversuch die konkurrierenden Ursachen anhand zeitlicher Reaktion und authentischer Fehler-IDs eingrenzen. Bis dahin bleiben der Quota-Root-Cause und die echte Hardwareperformance offen. Der produktive PR #31 und alle Musik-/UX-Zweige sind unveraendert.


## Nachtrag: Dreifach-LAN-Lockout als getrenntes Offline-Gegenmodell

Ein injizierter geraeteinterner LAN-Dienst-Ausfall wird mit
inject_lan_service_stall() simuliert und **nicht** aus niedrigem oder hohem
Befehlszaehler behauptet. modeled_cloud_path() bleibt davon unabhaengig:
Cloud/Google-Home/Voice-Erfolg war beim echten Vorfall schon bekannt, waehrend
Yeelight Classic die drei Esstischlampen nicht steuern konnte.
simulate_app_force_stop() entfernt nur modellierte lokale App-Sitzungen und
heilt keinen eingeschlossenen Firmwarezustand.
simulate_power_cycle() loescht nur volatile modellierte Geraetezustaende;
eine bereits bestehende App-Quarantaene darf dadurch nicht automatisch
verschwinden. Eine gueltige gerätespezifische Recovery-Antwort bleibt Pflicht.

Die neuen fuenf-Lampen-/14-Lampen-Regressionen stellen drei simulierte
LAN-Ausfaelle, zwei gesunde Kontrolllampen, weiterhin unabhaengigen
Cloud-Zugriff, erfolglosen App-Stopp und die drei einzelnen
Power-Cycle-Wiederherstellungen nebeneinander. Es werden weder
Netzwerkpakete gesendet noch echte Lampen angesprochen.
**Diese Simulation identifiziert keine reale OEM-Quota-Root-Cause:**
Die gesehenen 8502/1707/8502 Betriebsstunden belegen insbesondere
keinen gemeinsamen Firmware-Timerfehler.

Das separate klassische Steuerlast-Oracle weist nun booleansche,
nichtganzzahlige und ausserhalb 64-bit liegende
Zeit-/Farbeingaben sowie nichtbooleansche SUPPORT-Gates ab.
Kein Quota-Bypass, Release, Firmware-Patch, Live-Chroma- oder LAN-Test.


## Folgekorrektur: nur explizite UI-Ereignisse erzeugen Steuerungsversuche

Das klassische Steuerlast-Oracle hatte bislang nach dem letzten UI-Ereignis noch
die offenen Farb-/Helligkeitswerte bei einem *zukuenftigen* Zeitpunkt als
gesendete synthetische Befehle gezaehlt. Bei groesseren Zeitluecken wurden
historische Sendezeitpunkte erfunden, obwohl es dort keinen simulierten
Dispatch-/Timer-Aufruf gab. Das widersprach den geforderten Eigenschaften
kein unsichtbarer Timer, kein Retry und keine nachlaufende Farbsteuerung.

Jetzt wird hoechstens **ein** versuchter klassischer LAN-Befehl pro
ausdruecklich vorhandenem UI-Ereignis mit dessen **tatsaechlichem Zeitstempel**
vorgeschlagen. Der Rest am Ende ist keine Sendequeue:
unsent_tail_control_kinds meldet nur die Typen ungesendeter Werte;
last_value_forwarded_by_kind ist in diesem Fall false.
Die Daten werden nicht automatisch nach einem Idle-Intervall ausgesendet.
Vier neue Regressionen pruefen Stille nach dem letzten Input, grosse
Zeitluecken, gemeinsam anstehende RGB-/Helligkeitswerte und
no-implicit-background-dispatch. Dies ist weiterhin nur eine
synthetische Produktanforderung, noch keine Android-Hardwareabnahme.
