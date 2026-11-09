# Lokale Firmware- und ceiling1-Diagnose in der Android-App

Stand: 2026-11-01 · Forschungs-Draft-PR #30 · **keine Firmware- oder LAN-Steuerungsfreigabe**.

## In die App integrierte, vollständig passive Oberfläche

Die Geräte-Detailansicht zeigt unter **„Geräteverwaltung & Diagnose“** eine **Firmware-&-Chroma-Forschungskarte**. Sie verwendet nur `YeelightDevice.model` und `YeelightDevice.methods` aus der schon vorhandenen lokal gespeicherten Discovery. Sie führt **kein** M-SEARCH, `get_prop`, TCP/UDP-Connect, kein OTA und keinen Cloud-Zugriff aus.

- Bei fehlender SUPPORT-Liste: „unbekannt“ statt „nicht unterstützt“.
- Bei beworbener UDP-Methode: „v1/v2 **gemeldet, nicht an der Lampe getestet**“.
- Modellbezogene ESPHome-/MediaTek-Forschungsinformation erscheint getrennt.
- **Nie** eine direkte Flash-Freigabe, kein Befehl auf `color4` allein aus der Modellkennung.
- Die Anzeige nennt gespeicherte Beobachtungen ausdrücklich **nicht live**; veraltete SSDP-Einträge können kein Gerät oder Firmwarestand bestätigen.
- Der bestehende produktive Color4-LAN-Quota-Schutz aus PR #31 wird nicht verändert.

In **„Geräte & Netzwerk prüfen“** ist außerdem eine aufklappbare **ceiling1-WLAN-Zeitanalyse** verfügbar. Sie verwendet ausschließlich freiwillig eingetippte, anonymisierte **Abmelde-Zeitpunkte** (ein Zeitpunkt pro Zeile im Format `01.11.2026 10:00:00`). Eine zweite Lampe kann zum Vergleich eingetragen werden. Es gibt keinerlei Routerauthentifizierung, Netzwerkzugriffe, periodische Probes, Persistenz oder Serveruploads. Bei Geräte- oder Netzwerknamen, IPs, MACs, kompletten Ereigniszeilen, Zeitduplikaten und übergroßen Daten verweigert die Auswertung die Analyse.

## Zeit- und Evidenzregeln

- Vier oder mehr **wirkliche** WLAN-Abmelde-Ereignisse mit ungefähr 60 ± 5 Minuten Abstand ergeben nur den Hinweis `HOURLY_PATTERN`, niemals einen bestätigten Firmwarefehler.
- Pro Lampe werden ausschließlich Datum/Uhrzeit angenommen, auch in umgekehrter (Router-)Sortierung; Intervalle werden nach Europa/Berlin-Zone in absoluten Zeitstempeln berechnet.
- Am Wechsel zur Sommer-/Winterzeit werden **mehrdeutige** oder **nicht existente** Ortszeiten als nicht auswertbar erkannt, statt Periodizität zu erfinden.
- Drei oder mehr Abmeldungen zweier Lampen innerhalb von 60 s liefern nur einen Hinweis auf gemeinsamen zeitlichen Ausfall; weder Router noch Firmware werden dadurch als Ursache festgelegt.
- Wo FRITZ!Box-WLAN-An-/Abmeldungen nicht protokolliert werden, darf das Fehlen von Ereignissen **nicht** als Beweis ständiger Funkverbindung erscheinen. AVM-Dokumentation: https://fritz.com/apps/knowledge-base/FRITZ-Box-7490/277_WLAN-Funknetz-schaltet-sich-eigenstandig-an-und-oder-aus/ und https://lu.fritz.com/service/wissensdatenbank/dok/FRITZ-Box-7590/27_Haufige-Abbruche-der-WLAN-Verbindung/ .
- Der Benutzer muss die Abmeldezeitpunkte bewusst in der FRITZ!Box nachlesen und **nur die anonymisierten Zeitstempel** eingeben. Keine Routeranmeldeinformationen in die App.
- Live-BLE-Kontrolle, Google-Cloud-Status und die aktive Router-Assoziation werden von diesem Eingabewerkzeug **nicht** ermittelt.

## Wichtiger Befund des parallelen Color4-PR #31

Die erste PCAPdroid-Aufzeichnung von Yeelight Classic zeigt Cloud-HTTPS-Verbindungen, **aber keinen nachgewiesenen LAN-TCP/55443-Kommandostrom**. Daraus darf weder die Behauptung „Hersteller-App benutzt immer LAN“ noch „Hersteller-App steuert immer Cloud“ abgeleitet werden. Die Verkehrsmitschrift war nicht mit einer sicher bestätigten individuellen Lampenaktion korreliert. Quelle (separater PR): `docs/COLOR4_CLASSIC_PCAP_20261101_RESULTS_DE.md`.

Unsere passive Forschungsanzeige setzt folglich **keine** unbewiesene Kommunikationsgleichheit mit Yeelight Classic voraus. Ein zukünftiger aktiver Chroma-UDP-Transport benötigt zuvor den beobachteten Chroma-`support`-Beleg und eine ausdrücklich abgenommene Einzelgeräteprobe. Der Vorhandensein eines Original-Chroma-Connectors ist nicht der Nachweis, dass alle `color4` UDP-Streaming akzeptieren.

## Automatisierte Abnahme

- `FirmwareObservationTest` validiert gespeicherte SUPPORT-Nachweise und die strikten Forschungs-/Flashgrenzen, stündliche vs. unregelmäßige Ereignisse, fehlende Daten, verbotene Router-Logzeilen, Inputlimits, DST-Mehrdeutigkeiten und Kreuzkorrelation.
- Die zusätzlichen 10 JVM-Testmethoden laufen mit den bereits bestehenden 53 Forschungstests im selben öffentlichen `testDebugUnitTest assembleDebug`-Job.
- Neue UI-Komponenten in `FirmwareResearchUi.kt` werden in den vorhandenen Material-3-Android-Build eingebunden. Keine Debug-Menüs/Hidden-Flows und keine neuen Android-Berechtigungen.
- Eine erfolgreiche CI ist nur eine **Softwarequalifikation**, keine `ceiling1`-Ursachenermittlung und kein `color4`-UDP-Hardwaretest.

## Ergebnis und verbleibende Hardwaregrenze

Mit diesem Entwicklungsblock wird die Firmware-Forschung **erstmals aus der Android-App erreichbar**, ohne Geräte zu belasten. Die beiden manuellen Diagnoseflächen sind rein lesend/analytisch. Die echte Chroma-Sitzung, echte Color4-Hardware-ID/Firmware und tatsächliche ceiling1-Routerereignisse bleiben bis zur entsprechenden getrennten Geräteabnahme offen.


## Ergänzung: Anmeldung sichtbar, Abmeldung aus Ereignisprotokoll verschwunden

**Neue Beobachtungsklasse:** Die FRITZ!Box zeigt eine historische WLAN-Anmeldung einer \`ceiling1\`; der dazugehörige Abmeldeeintrag ist im aktuellen Ausschnitt der Ereignisliste nicht mehr sichtbar. **Kein** Rückschluss auf kontinuierliche WLAN-Verbindung, auf einen FW-Timer oder darauf, ob das Gerät zum Zeitpunkt der Prüfung online ist. Ereignisse können im angezeigten Zeitfenster fehlen. Das **Vorhandensein** einer protokollierten Anmeldung belegt nur eine Anmeldung zu diesem Zeitpunkt.

Stattdessen unterscheidet der neue \`CeilingCurrentDiagnostic\` explizit zwischen
\`CURRENT_WLAN_UNKNOWN\`, \`CURRENT_WLAN_ABSENT\`, aktuell WLAN-assoziiert mit funktionsfähiger Hersteller-App, aktuell WLAN-assoziiert mit nicht funktionierender App und unbekanntem lokalen/Cloud-Pfad, einem **nur separat belegten** lokalen TCP-/Dienstproblem, einer ausdrücklich erkannten LAN-Quota und widersprüchlichen bzw. nicht zeitgleich gewonnenen Beobachtungen. Kein Vorgang klassifiziert allein aus der Historie als aktuell online/offline; \`firmwareFailureProven\` bleibt immer \`false\`.

Die eingeblendete, rein lokale **Momentaufnahme** lässt Anwender die aktuelle WLAN-Assoziation und die Yeelight-App-Bedienbarkeit aus eigener Prüfung auswählen; zeitliche Vergleichbarkeit ist gesondert zu bestätigen. Der neue Diagnosekern besitzt **keine** Routerintegration, führt keine TCP-/UDP-Kommandos aus und erfordert keine neuen Berechtigungen. Die Wahl wird nur flüchtig in Compose gehalten und nicht in einem öffentlichen Log/Repository gespeichert. Alle UI-Labels kennzeichnen diese Einschätzungen als Beobachtungen, nicht als Gerätebefund des Programms.

**Wichtige externe Herstellerinformation:** AVM beschreibt unter WLAN > Funknetz einen Unterschied zwischen **bekannten WLAN-Geräten** (auch in der Vergangenheit verbunden) und gerade verbundenen Clients. Selbst ein RSSI-Symbol kann nach einer Abmeldung noch eine Weile eine Verbindung anzeigen. Die reine Geräteliste oder eine noch vorhandene DHCP-Adresse darf deshalb nicht als zwingender Beleg aktueller aktiver Assoziation behandelt werden.

Quellen:
- https://help.avm.de/fritzbox.php?deviceFeatures=100010011101110100101111010010&hardware=106&language=de&oem=avm&set=002&topic=hilfe_wlan_monitor&userSettings=11
- https://help.avm.de/fritzbox.php?deviceFeatures=101000111111010101101111101111110001111&hardware=139&language=de&oem=1und1&set=005&topic=hilfe_syslog&userSettings=011

### Software-Abnahme

Zusätzliche **12 JVM-Negativ-/Positivtests** für (a) sichtbare Anmeldung ohne sichtbare Abmeldung bei unvollständigem oder vollständig angenommenem Ereignisfenster, (b) unbekannten aktuellen Routerzustand, (c) zeitlich versetzte Messungen, (d) WLAN-assoziiert aber lokaler Dienst nicht nachgewiesen, (e) tatsächlich beobachtete Nichtassoziation, (f) App-Erreichbarkeit bei bestehender Assoziation, (g) ausdrücklich bestätigte Firmware-Quota, (h) widersprüchliche Router-/LAN-Befunde. Sie werden zusätzlich zu den bisherigen Forschungsregressionen mit dem Android-Pflichtbuild ausgeführt.

### Nicht ohne realen Beleg feststellbar

Die wirkliche Verbindungsart der Hersteller-App (erste Android-PCAP zeigte Cloud, aber keinen nachgewiesenen lokalen LAN-Steuerungsfluss), aktuelle lokale IP-Connectivity, aktive WLAN-Assoziation, LAN-Dienstzustand und Hersteller-Cloud-Erreichbarkeit lassen sich **nicht** aus einer historischen Router-Anmeldung ableiten. Diese Daten müssen gegebenenfalls im Fehlerfall durch zeitgleiche, ausdrücklich kontrollierte, nicht-invasive Beobachtungen ermittelt werden. Keinen Neustart/Flash/Factory Reset oder zusätzliche Lampen-Kommandos daraus auslösen.
