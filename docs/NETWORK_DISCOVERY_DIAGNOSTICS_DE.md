# Multicast vs. direkte LAN-Suche – Diagnose

## Was sich aus den Nutzerbeobachtungen sicher ableiten lässt

- Bei der ursprünglichen SSDP-ähnlichen Suche erscheinen nur etwa fünf Yeelight-Geräte.
- Ein direkter Scan über TCP-Port 55443 findet offenbar weitere Geräte.
- Viele Geräte melden das gleiche Modell `color4`. Identischer Gerätetyp garantiert jedoch **keine** identische Multicast-Erreichbarkeit.
- Die Ursache für die ausbleibenden UDP-Antworten ist **nicht** nachgewiesen. Denkbar sind WLAN-Multicast-Filter, unterschiedliche Anbindung an WLAN-Bänder/APs, Paketverlust oder Geräte-Firmware. Die Antwort-Parser-Funktion unserer App ist ebenfalls als mögliche Fehlerquelle zu berücksichtigen.

## Messen statt Vermuten

Die App zeigt nach **Alle Lampen suchen** getrennte Zahlen:
1. **Multicast**: eindeutige IPs mit gültiger Antwort auf UDP-M-SEARCH (239.255.255.250:1982).
2. **Direkt-LAN**: eindeutige IPs mit gültigem TCP-`get_prop` (Port 55443).
3. **Beide**: Schnittmenge; beweist, dass ein Gerät über beide Wege erreichbar war.
4. **Nur direkt**: Geräte, deren TCP-Status geprüft wurde, die aber in diesem Suchlauf keine gültige Multicast-Antwort lieferten.

An jeder Gerätekarte wird die aktuelle Suchmethode angezeigt. Die IP-Adressen werden ausschließlich lokal behandelt. Kein automatisches Hochladen privater Netzwerkdetails in GitHub.

## Gezielte Interpretation

- **Immer dieselben fünf** nur per Multicast: Stabile, geräte- oder netzsegmentspezifische Ursache prüfen. Firmwarestände, WLAN-Anbindung, Stromversorgung und FRITZ!Box-Topologie vergleichen.
- **Mal diese, mal jene fünf**: Eher Timing, WLAN-Paketverlust, Broadcast-/Multicast-Verkehr, Energieverwaltung oder ungeeignete Antwort-Timeouts.
- **Alle direkt per TCP erreichbar**: Keine erneute LAN-Aktivierung nötig und kein zwingender Hardwaredefekt. Szenen müssen über das einheitliche Geräteinventar steuerbar sein.
- **Nicht per TCP erreichbar**: Individuelle Netzverbindung und Geräte-IP in der FRITZ!Box prüfen.

## Icon
Android adaptive launcher icon in `res/mipmap-anydpi-v26`, with separate gradient background, color foreground and monochrome shape. Artwork remains within the mask safe area. The original icon design is a neon audio-spectrum motif (cyan, violet, pink).

## Hinweis zur Signierung

PR-CI prüft Quellcode, erzeugt aber bewusst **keinen** installierbaren Artefakt-Download mehr mit wechselnden Signaturen. Der installierbare, dauerhaft signierte Build folgt erst nach Einrichtung des privaten Schlüssels und dem regulären vertrauenswürdigen Build. Den Schlüssel nie im öffentlichen Repository veröffentlichen.
