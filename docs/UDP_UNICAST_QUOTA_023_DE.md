# v0.2.3 (Build 14): Direkt-UDP-Discovery und Quota-Schutz

Datum: 1. November 2026. Basis: v0.2.2, main
`6fbce44cded7bc5acdbf7a17069ecb71f998960d`.

## Originalmessung aus Nutzergerät

- Android WLAN-Adresse `192.168.0.103`, `Wi-Fi-Netzbindung: true`
- 12 Multicast-Runden: **6** antwortende Birnen, **130**
  Antwortpakete, **0** unlesbar, **0** Absender/Location-Abweichungen.
  Einige Birnen antworteten in 9/12 oder 10/12, andere in 12/12.
- **13** Lampen mit erfolgreichem TCP-Probe (Port 55443).
- Die **7** nur per TCP erkannten Birnen haben ausnahmslos auf
  gezielte UDP-Suchpakete geantwortet (**7/7**).
- Couchtisch (IP-Endung 134): `stop_cf abgelehnt: client quota exceeded`;
  `flowing` nicht lesbar. Statusjournal weiterhin offen.

## Schlussfolgerung

Alle sieben Nichtantworter **verstehen und beantworten** das Yeelight
UDP-Suchformat auf dem gleichen Smartphone. Die gesonderte
Multicast-Zustellung (M-SEARCH an `239.255.255.250:1982`) oder deren
Verarbeitung unterscheidet sich jedoch von dem erfolgreichen
UDP-Unicast an dieselben Geräte. Der Rückweg für UDP-Antworten funktioniert.
Ein Parser-, genereller UDP- oder genereller TCP-Defekt scheidet als
Hauptursache aus. Welche Funkstrecke / welcher Access Point / welche
Firmware die Multicast-Pakete unterdrückt, ist ohne Mesh-/Paketpfaddaten
**nicht abschließend bewiesen**.

Die fehlende Multicast-Antwort sagt nichts über RGB-Kompatibilität oder
die Geschwindigkeit des Yeelight-TCP-Schaltbefehls aus.

## Änderungen

1. **Echte gerichtete UDP-Discovery statt Ja/Nein-Abfrage**:
   zu den TCP-bestätigten, multicast-unauffindbaren Geräten wird
   höchstens zweimal pro Gerät Yeelights M-SEARCH an dessen direkte
   IP gesendet; Antworten werden auf Absender/IP und lokale Adressen
   überprüft. Die echte Antwort enthält `Location`, `id`,
   `model`, `name`, `support`. Diese Daten fließen durch die
   bestehende Geräte-ID-/Gruppenmigration in die App.
   **Kein zweites Gerät**, wenn dieselbe IP bereits gespeichert war.
2. Der Standard-Gerätesuchlauf kombiniert nun **Multicast + TCP +
   Direkt-UDP**. Die Diagnosedaten führen alle Verfahren getrennt;
   die Multicast-Statistik wird nicht geschönt.
3. Netzwerkansicht zeigt kompakt pro Gerät, ob es über **Multicast**,
   **gerichtetes UDP** oder nur TCP bestätigt ist, einschließlich
   gesonderter `0/12`-Multicast-Rundenzahl.
4. **Quota-Failsafe**: Wenn die Lampe ausdrücklich
   `client quota exceeded` auf `stop_cf` meldet, unterbleibt der
   bisherige zusätzliche `get_prop(flowing)`-Verbindungsaufbau.
   60-Sekunden-Cooldown verhindert wiederholte manuelle
   Verbindungsversuche an genau dieser Lampe. Andere Lampen bleiben
   steuerbar. Bestehende private Recovery-Snapshots werden niemals
   als Erfolg gelöscht.
5. Ein neuer Lichtlauf versucht **keinen automatischen erneuten
   Stopp** aller offenen Recovery-Einträge; er überspringt betroffene
   Lampen und belässt deren Journaleinträge unverändert.
6. Regressionstests für genaue Quota-Erkennung, Cooldown,
   6-Multicast/13-TCP/7-Direkt-UDP, Geräte-ID-/Support-Migration und
   doppelte Geräte.

## Mögliche externe Mitverursacher

Yeelight dokumentiert maximal vier gleichzeitige TCP-Clients sowie
60 Befehle/Minute pro Verbindung und 144 Befehle/Minute insgesamt.
`client quota exceeded` ist eine **Firmware-Ablehnung**; ohne
Messung weiterer gleichzeitiger Steuer-Clients ist nicht entscheidbar,
ob andere Apps/Automationen, zu viele Befehle oder eine
Firmware-Fehlfunktion die konkrete Lampe gerade überlasten.

Für Netzwerkursache bei Multicast: An der FRITZ!Box 7590 AX die
Mesh-Übersicht und die WLAN-Anbindung jeweils einer
Multicast-antwortenden und einer ausschließlich per Direkt-UDP
antwortenden Birne vergleichen. Gleicher AP oder unterschiedliche
Repeater? Auch mögliche Client-Isolation prüfen. Kein Router-Reset,
kein Deaktivieren von Sicherheitsmechanismen auf Verdacht.

## Hardwareabnahme

- App v0.2.3 installieren (keine App-Daten löschen) und
  **Alle Lampen suchen**: wie viele Multicast/TCP/Direkt-UDP,
  wie viele Color4 besitzen jetzt bekannte `support`-Methoden?
- Erneut 12-Runden-Suche: Multicast-Nichtantworter sollten auch
  weiterhin **als Multicast-Nichtantworter** sichtbar bleiben,
  jedoch bei erfolgreichem Direkt-UDP alle Fähigkeiten bekommen.
- Die 134-Lampe bei `client quota exceeded` nicht wiederholt
  innerhalb 60 Sekunden stoppen. Parallelsteuerungen vor
  Wiederherstellungsversuch möglichst vorübergehend beenden.
- Kein Aus- und Einschalten der physischen Lampe oder Löschen
  gespeicherter Zustände ohne Nutzerentscheidung.
- CI: Android `testDebugUnitTest` + `assembleDebug`;
  kein Zugriff auf privates WLAN/Lampen in GitHub Actions.

Referenzen:
- Yeelight Inter-Operation Spec, Kapitel 3.1 und 4:
  https://home.yeelight.de/site/templates/downloads/yeelight_inter-operation-spec.pdf
- AVM FRITZ!Box 7590 AX Multicast-Hinweise:
  https://uk.fritz.com/service/knowledge-base/dok/FRITZ-Box-7590-AX/592_Reception-interference-when-watching-TV-over-IPTV-connection/
