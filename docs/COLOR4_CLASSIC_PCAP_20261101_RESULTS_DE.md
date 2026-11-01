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

## Auswirkungen auf die Entwicklung

1. Keine Behauptung, Loft DJ müsse nur den lokalen TCP-Kanal „genau wie die offizielle App“ nachbilden: dieser Übertragungsweg ist für die getestete Yeelight-Classic-Session nicht nachgewiesen.
2. Kein unnötiges Wiederholen des bekannten `client quota exceeded`-Fehlers an Wellness.
3. Draft-PR #31 bleibt Quarantäne-/Pacing-Schutz, **kein** nachgewiesener Root-Cause-Fix.
4. Hersteller-App-Netzwerkmodus und lokale Color4-Fähigkeiten separat qualifizieren; keine ungeprüfte Umstellung auf Cloud oder Nutzung privater Hersteller-APIs.
5. Keine belastende oder automatisch wiederholte LAN-Suche zum Nachweis der App-Strategie.

## Nächste notwendige Evidence

Eine *eindeutig bestätigte* Farb- oder Helligkeitsänderung der intakten Color4 während eines passiven Router-/WLAN-Mitschnitts oder – falls kein LAN-Mitschnitt möglich ist – mit einer überprüften Android-17-PCAPdroid-Konfiguration inklusive lokaler Netzwerkberechtigung. Erst danach kann ein direkter Vergleich von TCP-Sitzungsanzahl, auf dem Draht übertragenen Einzelbefehlen und ACK-Abständen mit Loft DJ erfolgen.
