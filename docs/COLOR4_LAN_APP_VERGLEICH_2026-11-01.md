# Color4: Yeelight Classic vs. Loft DJ – LAN-Verhalten nachweisen

**Hardwarebefund:** „Wellness“ hat nach manueller Steuerung mit Loft DJ v0.2.7 den Firmwarefehler `client quota exceeded`. Auch Yeelight Classic kann sie anschließend nicht mehr lokal steuern, Google Home funktioniert weiterhin. „Couchtisch“ wurde durch Strom-Neustart wieder bedienbar. **Keine weiteren Testkommandos an Wellness senden.**

## Was wir derzeit sicher wissen

Die offizielle Yeelight WiFi Light Inter-Operation Specification definiert für den normalen lokalen TCP-Kanal `55443`: maximal vier gleichzeitig offene Verbindungen, 60 Befehle/Minute und Verbindung sowie 144 lokale Befehle/Minute insgesamt. Jeder Befehl einschließlich `get_prop` ist ein JSON-Objekt mit ganzzahliger ID, `method`, `params` und `\r\n`-Abschluss. Asynchrone `props`-Meldungen können zwischendurch erscheinen.

Derselbe Standard dokumentiert `set_music` mit vom Gerät aufgebauter Rückverbindung, ohne normale Befehlsquote. Unterstützung muss per `support`-Advertisement für genau die betreffende Color4 verifiziert werden.

**Nicht bewiesen:** Dass die aktuelle Yeelight-Classic-App denselben lokalen Kanal für alle Operationen benutzt, ob sie bei Bedienung zwischen lokalem TCP und Cloud umschaltet oder welchen Verbindungs-/Pacing-Algorithmus sie intern verwendet. Die dokumentierte `ceiling1`-Timerüberlauf-Ursache ist **nicht** auf `color4` übertragen.

## Vergleich – ausschließlich an einer intakten, bewusst freigegebenen Testlampe

1. **Passiv beginnen:** Auf dem Smartphone mit Yeelight Classic eine lokale VPN-basierte Paketaufzeichnung ohne Root oder eine eigene Router-Mitschnittfunktion verwenden. Keine WLAN-/Cloud-Zugangsdaten veröffentlichen. Wenn eine VPN-Aufzeichnung das WLAN-/LAN-Routing verändert, Versuch abbrechen und einen anderen Mitschnittweg nehmen.
2. Genau eine intakte `color4` mit LAN-Steuerung nehmen; **nicht Wellness**. In Yeelight Classic einmal Power, Farbe, Helligkeit betätigen, mit mindestens zehn Sekunden Abstand und ohne Lichtshow. Notieren, ob `TCP/55443`, `UDP/1982` oder ausschließlich Hersteller-Cloud-Verbindungen zum Einsatz kommen. Zählen, wie viele TCP-Verbindungen gleichzeitig offen sind, wie lange sie gehalten werden und wie viele Befehle pro Bedienaktion anfallen.
3. **Erst nach Software- und Hardwarefreigabe** denselben vorsichtigen Ablauf in einer korrigierten Loft-DJ-Version wiederholen. Auf den ersten Fehler reagieren, keine automatische Wiederholung. Identische Befehle, Zeitabstände, ACKs und `props` vergleichen.
4. Ergebnis auf Geräte-ID, `model=color4`, `fw_ver` und `support` beziehen. Nicht von `ceiling1`-Firmware und nicht von anderen Color4-Versionen auf dieses Gerät schließen.
5. Mitschnitt privat halten, nur bereinigte Metadaten (Zeit, Verbindung, Port, Methode, Antwort/Fehler) in Entwicklungsberichte übernehmen. Keine Cloud-Tokens, Schlüssel oder persönlichen Paketdaten in öffentlichen Issues.

## Instrumentierung in Draft-PR #31

`YeelightTcpSessions` schreibt über Android-Logcat-Tag `LoftDjLan` ausschließlich:
- `event=connect`, `event=close` mit lokalem Ziel-IP-Endsegment und Port
- `event=send`, `event=ack`, `event=firmware-error` mit Befehls-ID und Methode
- `event=transport-error` mit Exception-Klasse, ohne Befehlsparameter oder Nutzerinhalte

Diese Protokollierung beweist die **eigene** TCP-Nutzung, aber nicht das Verhalten der Hersteller-App. Ein `client quota exceeded` wird separat als Hardware-Firmwareantwort behandelt, niemals als lokaler Vorab-Schutz.

Auf einem bereits verbundenen Pixel 6a (Windows-PowerShell):
```powershell
adb -s "adb-25231JEGR10182-s506Zv._adb-tls-connect._tcp" logcat -c
adb -s "adb-25231JEGR10182-s506Zv._adb-tls-connect._tcp" logcat -d -v threadtime -s LoftDjLan:I "*:S" | Out-File -Encoding utf8 "$env:USERPROFILE\Downloads\loft-dj-lan-protokoll.txt"
```
**Die zweite Zeile erst nach einem ausdrücklich freigegebenen, vorsichtigen Test der neuen Version verwenden.** Keine zusätzliche Netzwerkaktivität nur zur Protokollgewinnung auslösen.

## Abnahmegrenze
Grüne CI = Software-PASS; kein Hardware-PASS. Nicht mergen, signiert veröffentlichen oder weitere Lampen belasten, bevor das tatsächliche Color4-Verhalten an einem kontrollierten Einzelgerät qualifiziert wurde.
