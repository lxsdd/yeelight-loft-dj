# Color4: überprüfte Steuerwege und sichere Entscheidung für Loft DJ
Stand: 1. November 2026 · Software-Entwicklung auf Draft-PR #31

## 1. Belege aus tatsächlicher Hardwarebeobachtung

**Yeelight Classic auf Pixel 6a:** Während der beiden ausdrücklich bestätigten Farb-/Helligkeitsänderungen der funktionsfähigen Lampe „Couchtisch“ enthielt der Android-PCAPdroid-Mitschnitt Yeelight-/Xiaomi-HTTPS-Verbindungen. Ein unabhängiger FRITZ!Box-LAN-/WLAN-Mitschnitt zeigte bidirektionale HTTPS-Kommunikation **der Lampe selbst** zu einem externen Server; keine sichtbare TCP-55443-Sitzung in den aufgenommenen Pfaden. Die User-Hardwarebeobachtung bestätigte beide Reaktionen.

**Einordnung:** Die Beobachtung stützt die Cloud-Steuerung bei genau dieser Sitzung sehr stark. Sie beweist weder die konkrete Bedeutung verschlüsselter Pakete noch, dass jeder WLAN-Unicast im Router-Mitschnitt sichtbar war oder Yeelight Classic in anderen Zuständen niemals lokal steuert. Vollständige Mitschnitte bleiben privat.

**Loft DJ v0.2.7:** Nach wenigen LAN-Bedienhandlungen meldete die Color4 „Wellness“ `client quota exceeded` und war anschließend auch in Yeelight Classic vorerst nicht steuerbar; „Couchtisch“ war erst nach Strom-Neustart wieder intakt. Ursache in exakt diesem Color4-Firmwarestand nicht durch Quellcode/Hardwaretrace bestätigt. Keine Gleichsetzung mit öffentlich dokumentierten Ceiling1-Timerbugs.

## 2. Offizielle Protokolle und Integrationsvoraussetzungen

| Weg | Dokumentiert | Für Loft DJ tatsächlich implementiert | Risiko-/Eignungsgrenze |
|---|---|---|---|
| Classic LAN Control | TCP `55443`, JSON + CRLF, SSDP-like UDP `1982`; max. 4 TCP-Verbindungen, 60 Befehle/min pro Verbindung, 144/min global | Ja, quota-geschützt, geordnete Sessions; in PR #31 **normale Geräteerkennung ohne intensive TCP-Scans** | Firmware kann LAN-Quota ablehnen; kein Echtzeitkanal |
| Classic Music Mode | `set_music`, Rückverbindung vom Gerät auf das Smartphone, ohne normale LAN-Kommandobeschränkung | Für Geräte mit **explizitem `support`-Advertisement** implementiert; Abnahme an echter Color4 offen | Verbindungsaufbau/-abbau regulär quotiert; keine bestätigte Hardwaresynchronisation |
| Yeelight Open Developer Platform | Registrierung/Prüfung durch Yeelight und Herstellerintegration | **Nicht integriert und nicht als verfügbar darstellen** | Ohne eigene genehmigte App, Authentisierungsdaten und API-Vertrag kein seriöser Cloud-Client |
| Yeelight Chroma-UDP | Separater modellabhängiger Spezialtransport; unabhängiger Forschungszweig PR #30 | Nur Offline-Protokolluntersuchung, **nicht** als Color4-Steuerweg nachgewiesen | Port/Format/Hardware-Support nicht vom Modellnamen ableitbar |

Offizielle Links:
- LAN-Spezifikation: https://home.yeelight.de/site/templates/downloads/yeelight_inter-operation-spec.pdf
- Hersteller-Developer-Seite: https://www.yeelight.com/en_US/developer
- Offizielle Developer-Plattform: https://home.yeelight.de/en/cloud-api/

## 3. Verbindliche Routing-Regeln

1. Normale Lampensuche: **UDP-Multicast + maximal 24 gezielte UDP-Abfragen an bekannte, noch nicht antwortende IPs**; keine automatischen TCP-`get_prop`-Anfragen und kein /24-Scan.
2. Intensiver TCP-Suchlauf: nur separate, ausdrücklich angeforderte Diagnose; nicht als normale Geräteaktualisierung.
3. Manuelle Color4-Steuerung: derzeit nur über offiziellen Classic-LAN-Kanal; jeder tatsächlich gesendete Befehl wird getrennt seriell getaktet. Ein `client quota exceeded` der **Firmware** quarantänisiert ausschließlich diese Geräte-IP.
4. Laufende Lichtshows und Musik: Firmware-gesperrte Geräte vor jeder Statusabfrage, Sessionmarkierung oder Zustandsänderung aus der Zielmenge entfernen. Nicht automatisch wiederholen und nicht im Hintergrund mit `get_prop` „gesund prüfen“.
5. Music Mode: nur bei echtem `support` von `set_music` und `set_rgb`. Nie als Ersatz einen nicht verifizierten Chroma-/Cloud-Kanal ausprobieren.
6. Cloud: explizit **nicht verfügbar**, solange kein von Yeelight legitimierter Integrationszugang mit überprüften Endpunkten und Nutzungsbedingungen vorliegt. Kein Nachbau aus privaten App-Tokens, keine Umgehung von Herstellerauthentisierung. Wenn autorisiert, Cloud-Transport getrennt testen und nur für dafür geeignete Alltagssteuerung priorisieren.

## 4. Qualifikation vor neuer APK

- Vollständige SHA-genaue Android-CI: `testDebugUnitTest assembleDebug`.
- Negative Tests: Quarantäne, keine TCP-Normalsuche, Discovery ohne gesperrte IPs, deduplizierte IP-Ziele, gemischte Szenengruppen, Music-Mode nur bei bestätigten Methoden, keine unbemerkte Cloud-Annahme.
- Hardware: zunächst **nur eine intakte und freigegebene Color4**, mit dokumentierter Ruhezeit, wenigen manuellen Einzelbefehlen und anschließender Hersteller-App-Kontrolle. Bei erster Firmwareablehnung sofort beenden.
- Keine signierte neue Veröffentlichung oder automatischer Merge nur aufgrund grüner Software-CI. Kein Test an Wellness, bevor ausdrücklich freigegeben und Geräte-Zustand geklärt.
