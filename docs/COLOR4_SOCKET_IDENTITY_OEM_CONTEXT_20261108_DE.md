# Color4 – identitätsgebundene TCP-Sitzungen (Offline-Regression)

**Stand:** 2026-11-08. **Scope:** PR #54, reine Kotlin-Offline-Sicherheitslogik. Keine Android-/Music-/Chroma-Live-Verkabelung, keine Socket-Öffnung, keine Lampen- oder Accountaktion.

## Hersteller-Referenz vor einer eigenen Sitzungsübergabe

Die privat vom Eigentümer bereitgestellte Yeelight-Classic-3.5.4-APK wurde ausschließlich lesend untersucht (SHA-256 `abdcffbcf118e5d5c48088a478c8ea49f4c3fa911db1f1bc5013fed1d61fb7f2`). Statisch nachgewiesen sind getrennte Wege für normale LAN-Eigenschaftsrückmeldungen, Geräteabfragen und die Music-Rückverbindung: der OEM-Music-Manager öffnet einen lokalen Server-Socket, übermittelt per `Color4Service.enableMusic` `MusicEnable`, `MusicIPAddress` und `MusicPort`, akzeptiert eingehende Verbindungen und verwaltet gerätebezogene Kommando-Warteschlangen. `getMusicEnable` liest den Music-Zustand. Das ist **kein** belegter Eigentumsnachweis oder eine nachgewiesene Übergabe einer laufenden Music-Sitzung zwischen zwei Telefonen. Der vorliegende ältere PCAP enthält keine Pakete der lokalen Yeelight-Ports 1982, 55443 oder 55444; HTTPS-Verkehr darf nicht automatisch Yeelight zugeordnet werden.

## Reproduzierter Fehler in unserem bisherigen Offline-Modell

`Color4TcpResponseLedger.Session` war eine öffentliche Kotlin-`data class` mit alleiniger `generation`. Sowohl der Ledger als auch `Color4OfflineCoordinator` verglichen die Sitzung über **Wertgleichheit** (`==` / `!=`). Ein anderes Objekt mit derselben Generationsnummer (oder mit `copy()`) konnte daher einen fremden Socket nachbilden. Die ursprüngliche Regression schlug nachweislich fehl: `FORGED_SESSION_ACCEPTED_LEDGER_REPLY`. Mit identischem `protocolId` ließ sich der offene Befehl als erfolgreich bestätigen. Dasselbe Risiko bestand für Timeout, Close und einen ausdrücklich zugelassenen UI-Dispatch.

## Umsetzung und Nachweise

`Session` ist jetzt eine normale, nicht kopierbare Klasse mit internem Konstruktor. Die Vergleiche innerhalb des Ledgers und des Koordinators erfolgen ausschließlich über Kotlin-**Objektidentität** (`===` / `!==`). Auch innerhalb desselben Moduls manuell erzeugte Doppelobjekte oder eine gleichnummerierte Sitzung eines zweiten Ledgers können damit keine Antworten bestätigen, einen Befehl eröffnen oder eine andere Sitzung schließen. Das beim echten Ledger-`open` ausgegebene Objekt bleibt gültig. Fehlerhafte Antworten verbrauchen weder Budgets noch führen sie zu einer Quarantäne des legitimen Geräts.

Die fünf neuen JUnit-Regressionen prüfen geklonte Generationswerte, ACK/Timeout/Close/Begin, getrennte Ledger, den echten Koordinator und 5.600 gefälschte Ereignisse gegen 14 gleichzeitig aktive Simulationslampen. Die Regression war vor der Korrektur rot und nachher grün (5/5 lokal; exakte Quellen-CI ist zusätzlich erforderlich). Ergänzend wurden 9/9 lokale, **rein lesende** OEM-APK-/PCAP-Analyseprüfungen bestanden; diese prüfen keine echte App-Laufzeit.

**Grenze:** Objektidentität im selben Prozess ist **keine kryptografische Authentisierung** gegenüber externem IPC, Netzwerk-Absendern oder bösartigem Code mit Zugriff auf die Originalreferenz. Ein realer Netzwerkadapter muss die tatsächliche Socket-Instanz sowie Ursprung/Port, numerische JSON-ID, Generation und Timeouts selbst binden. Der OEM-Befund rechtfertigt **keine** automatische Übernahme fremder Music- oder Chroma-Sitzungen; `music_on=1` signalisiert einen Modus, keinen Besitzer. Keine Änderung an Quota-Grenzen und keine stillen Wiederholungsversuche.
