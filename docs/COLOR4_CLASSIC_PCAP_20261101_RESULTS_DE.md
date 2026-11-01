# Color4 / Yeelight Classic: erster PCAPdroid-Mitschnitt vom Pixel 6a

Datum: 1. November 2026; **nur aggregierter, bereinigter Befund**. Die ursprüngliche PCAP-Datei ist privat und wird **nicht** in diesem öffentlichen Repository abgelegt.

## Aufnahme- und Zuordnungsbedingungen

Die Aufzeichnung erfolgte laut verwendetem PowerShell-Skript mit PCAPdroid ohne Root, unter Verwendung des Paketfilters `com.yeelight.cherry`, `full_payload=true`, ohne HTTPS-Entschlüsselung. Der Nutzer sollte dabei ausschließlich die funktionierende `color4` „Couchtisch“ in Yeelight Classic mit maximal zwei vorsichtigen Aktionen bedienen; ob die beiden Aktionen tatsächlich innerhalb des Aufzeichnungsfensters stattfanden, ist ohne Ereignisnotiz nicht durch den Mitschnitt selbst bestätigt.

## Aus der Rohdatei belegte Beobachtungen

| Merkmal | Beobachtung |
|---|---|
| Aufzeichnungsdauer | 67,60 s |
| Gesamtzahl IPv4-Pakete | 337 |
| TCP / UDP | 329 / 8 |
| TCP-Verbindungen zu öffentlichen HTTPS-Servern | 16 |
| Zielports TCP 55443 | **0 Pakete** |
| Yeelight-SSDP UDP 1982 | **0 Pakete** |
| Weitere UDP-Broadcasts | 2 Pakete an Port 54321, nicht an Yeelight 1982 |
| TLS-SNI und DNS | `api-de.yeelight.com`, `de.openapp.io.mi.com`, `de.api.home.mi.com` |
| Yeelight-JSON-Kommandos | keine im Mitschnitt sichtbaren Klartextkommandos |

Die Hostnamen sind anhand der im Mitschnitt erkennbaren DNS-/TLS-Handshakes belegt. HTTPS-Nutzdaten sind verschlüsselt: Es ist **nicht** möglich, die Anzahl, Methoden oder den Erfolg der konkreten Lichtsteuerungsbefehle aus ihnen abzulesen.

## Schlussfolgerung und klare Grenze

**Belegt:** Yeelight Classic hat während dieser Capture-Session Cloud-/Internetverbindungen aufgebaut. **Nicht belegt:** dass die beobachteten Lampenaktionen Cloudbefehle waren; dass die Hersteller-App ausschließlich Cloud verwendet; oder dass ein funktionierender Color4-LAN-TCP-Steuerkanal vorhanden war.

Die PCAPdroid-VpnService-Aufzeichnung nutzt synthetische L3/L4-Header und zeigt nur von Android ausgehende Verbindungen, die durch die VPN-Routen und den Appfilter laufen. Eine ausbleibende LAN-Verbindung im Dump darf nicht als absoluter Beweis gelten, dass es überhaupt keine lokale WLAN-Kommunikation gab. Das nächste Experiment muss den tatsächlichen LAN-Verkehr **unabhängig** erfassen (beispielsweise passiver WLAN-Interface-Mitschnitt am Router) und mit einer eindeutig beobachteten Lampenreaktion zeitlich korrelieren.

Dokumentation PCAPdroid:
- https://emanuele-f.github.io/PCAPdroid/quick_start.html
- https://emanuele-f.github.io/PCAPdroid/faq.html
- https://github.com/emanuele-f/PCAPdroid/blob/master/docs/how_it_works.md

## Zweite unabhängige Aufzeichnung: FRITZ!Box 7590 AX

Die Nutzerin/der Nutzer stellte am 1. November 2026 zwei FRITZ!Box-Paketmitschnitte von **LAN** und einer **WLAN-Schnittstelle** bereit. Die privaten Dateien werden weder hier abgelegt noch veröffentlicht. Beide Dateien verwenden das **modifizierte PCAP-Format mit Magic `0xa1b2cd34` und acht zusätzlichen Bytes pro Paketdatensatz**; die Auswertung berücksichtigt diese Zusatzfelder, ansonsten erscheinen die Pakete falsch ausgerichtet.

| Merkmal | LAN-Mitschnitt | WLAN-Mitschnitt |
|---|---:|---:|
| Erfasste Ethernet-Frames | 35.423 | 889 |
| Zeitfenster mit plausiblen Paketzeitstempeln | 105,86 s | 62,69 s |
| Einschränkung Zeitstempel | keine bei diesen Frames festgestellt | 535/889 Frames mit Null-/ungültigem Zeitstempel |
| Yeelight TCP/55443 (Quelle **oder** Ziel) | **0** | **0** |
| Yeelight UDP/1982 (Quelle **oder** Ziel) | **0** | **0** |

**Konkretes Zielgerät nachgewiesen:** Die WLAN-Lampe mit dem lokalen mDNS-Namen `YLBulbColor1s-…` und einer zu `yeelink.light.color4` passenden, im FRITZ!Box-Screenshot wiedererkennbaren Geräteidentifikation wurde im PCAP anhand ihrer **MAC-Endung** sowie der vom mDNS-Record bestätigten privaten IP identifiziert. Vollständige MAC/IP bleiben privat.

**An der Lampe selbst beobachtet:** Die Color4 hatte eine bereits vor Aufnahmebeginn bestehende verschlüsselte TCP-Verbindung zu einem externen Server auf Port 443. Im LAN-Mitschnitt waren **17 Pakete von der Lampe zum Server und 15 zurück** auf diesem TCP-Flow sichtbar, einschließlich TLS-Application-Data in beiden Richtungen. Server-zu-Lampe-Daten und direkte Lampenantworten treten zeitlich gehäuft auf (u. a. um 17:50:35–17:50:49 Uhr Ortszeit). Es existiert **kein** sichtbarer Klartext-`set_rgb`-, `set_bright`- oder `get_prop`-Befehl über TCP/55443. Der externe Server-IP allein wird **kein unbestätigter Hostname** zugeschrieben.

**Zusammenschau mit PCAPdroid:** Erstes Smartphone-Capture zeigt `com.yeelight.cherry` zu Cloud-API-Hosts per HTTPS; zweites, unabhängiges Router-Capture zeigt zugleich die **Color4 selbst** in bidirektionalem verschlüsseltem TCP-Verkehr zu einem externen Server. Beide Captures zeigen jeweils **keine** lokale Yeelight-TCP-Kommandositzung im beobachteten Pfad. Das ist deutlich stärkere Evidenz für eine tatsächliche Cloud-**Steuerungsmöglichkeit** der Lampe als die alleinige App-Aufzeichnung.

### Noch nicht bewiesen
- Dass die Bedienhandlungen tatsächlich genau innerhalb des 17:49–17:51-Intervalls ausgeführt wurden und *welche* TLS-Datensätze den Änderungen entsprechen. Ohne Zeitmarker/visuell bestätigte Reaktion bleibt das eine plausible, aber nicht beweisbare Zuordnung.
- Dass WLAN-to-WLAN-Unicast im betreffenden FRITZ!Box-Interface vollständig erfasst wurde (WLAN-Offload/Interfaceauswahl können das verhindern).
- Dass die Yeelight-Classic-App ausschließlich über Cloud bedient oder den klassischen LAN-55443-Kanal grundsätzlich **niemals** nutzt.
- Dass der `color4`-`client quota exceeded`-Fehler auf einen bestimmten Firmware-Timer zurückzuführen ist.

### Technische Folgerung
**Nicht** versuchen, eine offensichtlich stabile Hersteller-App durch bloßes Wiederholen von `get_prop` und `set_rgb` auf 55443 zu „kopieren“. Einen Cloudweg nur auf Basis **autorisierter, offiziell dokumentierter Authentifizierungs- und Befehls-APIs** einführen. Die gegenwärtige direkte LAN-Steuerung in Loft DJ benötigt weiterhin geräteweise Serialisierung, Pacing und permanente Firmware-Quota-Quarantäne; Draft-PR #31 bleibt ein Sicherheitsfix, kein als hardwarebeständig bewiesener Root-Cause-Fix. Für die endgültige Differenzierung eine Zeitmarke zur **wirklich beobachteten Lampenreaktion** oder einen vollständigen WLAN-Unicast-Mitschnitt als nächsten Beleg einsetzen.

## Auswirkungen auf die Entwicklung

1. Keine Behauptung, Loft DJ müsse nur den lokalen TCP-Kanal „genau wie die offizielle App“ nachbilden: dieser Übertragungsweg ist für die getestete Yeelight-Classic-Session nicht nachgewiesen.
2. Kein unnötiges Wiederholen des bekannten `client quota exceeded`-Fehlers an Wellness.
3. Draft-PR #31 bleibt Quarantäne-/Pacing-Schutz, **kein** nachgewiesener Root-Cause-Fix.
4. Hersteller-App-Netzwerkmodus und lokale Color4-Fähigkeiten separat qualifizieren; keine ungeprüfte Umstellung auf Cloud oder Nutzung privater Hersteller-APIs.
5. Keine belastende oder automatisch wiederholte LAN-Suche zum Nachweis der App-Strategie.

## Nächste notwendige Evidence

Eine *eindeutig bestätigte* Farb- oder Helligkeitsänderung der intakten Color4 während eines passiven Router-/WLAN-Mitschnitts oder – falls kein LAN-Mitschnitt möglich ist – mit einer überprüften Android-17-PCAPdroid-Konfiguration inklusive lokaler Netzwerkberechtigung. Erst danach kann ein direkter Vergleich von TCP-Sitzungsanzahl, auf dem Draht übertragenen Einzelbefehlen und ACK-Abständen mit Loft DJ erfolgen.
