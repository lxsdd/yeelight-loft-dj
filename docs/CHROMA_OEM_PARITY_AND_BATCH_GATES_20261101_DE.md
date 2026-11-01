# Chroma v1/v2: OEM-Protokollvertrag und gruppenweites Pacing (nur offline)

Stand: 01.11.2026. Isolierter Forschungs-Nachfolger auf PR #46 → #40 → #30.

## Nachweisbare OEM-Referenz

Öffentlicher Originalcode: https://github.com/Yeelight/Yeelight-Chroma-Connector/blob/769a616b9012877baf7fa3df426b38ca6697a167/ChromaBroadcastSampleApplication/CUdpLight.cpp

Im Herstellerbeispiel sind v1 udp_new/udp_token/udp_keep_alive und v2 udp_sess_new/udp_sess_token/udp_sess_keep_alive getrennt. Der Handshake hat eine leere params-Liste und noch keinen Token. Keepalive enthält das bewusst originale, ungewöhnlich geschriebene keeplive_interval und den Stringwert 10. Für Standardgeräte wird set_scene mit color, RGB-Integer, Helligkeit, Dauer, Effekt und Token benutzt. Die Tests prüfen diese semantischen Felder für beide Versionen gegen ein unabhängiges In-Memory-Fake; JSON-Whitespace und die genaue Reihenfolge von Objektfeldern sind nicht normativ.

Die OEM-Implementierung verwendet teilweise permissives Substring-Parsing. Unser Simulator prüft absichtlich strenger Sender-IP/Port, echte Integer-IDs, korrelierte Antworten und Token, ohne automatische Wiederholung. Das ist eine dokumentierte Sicherheitsabweichung, keine Behauptung bitgenauer OEM-Parität.

Der OEM-Code kennt außerdem Hintergrundgeräte mit bg_set_scene oder bg_set_rgb. Die aktuelle Forschungsstrecke ist ausschließlich auf explizit beworbenes normales set_scene beschränkt. Hintergrundmethoden allein sind kein Standard-RGB-Freigabebeleg. Es existiert weiterhin kein Live-UDP-Transport und keine Chroma-Hardwareabnahme für color4 2.1.7_0039.

## Geschlossene Offline-Sicherheitslücken

1. Zwei verschiedene Sessionobjekte mit derselben simulierten Zieladresse konnten bisher unter unterschiedlichen Lampenschlüsseln geführt werden. Dadurch wären pro physischer Lampe mehrere synthetische Quota-Budgets möglich. Der Fleet-Konstruktor lehnt doppelte normalisierte Endpunktidentitäten jetzt vor dem ersten Handshake ab. Fehlertexte und Status enthalten keine IPs oder Tokens.
2. Bei unterschiedlich vorbelasteten Lampen konnte ein gemeinsamer räumlicher Tick einen Teil der Farbframes ausgeben, während eine andere Lane noch im 100-ms-Ratefenster lag. Ein passives, gruppenweites canIssueColor-Gate verzögert jetzt den gesamten Preview-Tick; der letzte Sollwert bleibt ohne Queue bis höchstens 250 ms erhalten. Nur der explizite nächste virtuelle Tick kann erneut prüfen. Unveränderte Sollwerte können weiterhin absichtlich dedupliziert werden.
3. Ein bereits tokenisierter, aber inzwischen abgelaufener oder zeitlich zurückgerollter Kandidat konnte bisher arm() positiv passieren und erst beim ersten Tick scheitern. arm() prüft jetzt die aktuelle monotone 15-s-Lane-Frist vor der Gruppenzusage.

## Tests und Grenzen

Neue JVM-Regressionsfälle decken doppelte Zielidentität, redigierte Status, nicht konsumierende Rate-Preflights, 14-Lampen-Asymmetrie, abgelaufene/zeitlich zurückgerollte Starts, v1/v2-OEM-Methoden und -Parameter, Hintergrundgeräte-Fail-Closed sowie Replay/Tokenverwechslung ab. Der gesamte Test ist ohne Router, Lampen, Firmwarebytes, Benutzer-IP, Cloud und Android-Netzwerksender ausführbar.

Die 100 ms, 250 ms, 15 s und 45 RGB-Versuche sind synthetische Sicherheitsgrenzen, KEINE gemessenen Color4-Quotas oder zulässige Hardware-Framerates. Ein partieller realer Netzwerkversand wäre selbst bei perfekter Offline-Vorprüfung nicht atomar; die Simulation beweist keine physische Synchronität oder Restore-Eigenschaften. Der echte client quota exceeded-Root-Cause bleibt Gegenstand separater privater Xtensa-Analyse (#48); keine Quota-Umgehung, keine Firmwareänderung, keine Freigabe zum Live-Test, Merge, Release oder Flash.
