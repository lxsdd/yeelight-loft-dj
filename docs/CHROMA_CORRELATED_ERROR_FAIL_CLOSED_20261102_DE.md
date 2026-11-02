# Chroma-v1/v2: korrelierte Farbfehler und terminale Offline-Sitzungen

Stand: 02.11.2026. Eigenständiger, gestapelter Research-Nachfolger von PR #51 → #49 → #46 → #40 → #30. **Ausschließlich In-Memory-Simulation; keine echte UDP-/TCP-Verbindung, kein Scan, kein Firmwarepatch, keine Lampen-/Routeraktion.**

## Konkret behobene Host-Sicherheitslücke

Vorher korrelierte ChromaUdpOfflineSession.acceptReply() eine Antwort-ID nur mit dem Handshake oder einer ausstehenden Keepalive-Anfrage. Für set_scene wurde keine Request-ID gespeichert, weil der gepinnte Yeelight-Chroma-Connector Farbframes ohne erforderliches Erfolgs-ACK versendet. Ein vom simulierten Gerät zurückgegebenes **echtes, zur gesendeten Farbe passendes JSON-Fehlerpaket** (id + error) wurde deshalb als unerwartet verworfen. Selbst ein Hinweis auf eine Geräte-/Quota-Ablehnung hätte die virtuelle Sitzung nicht geschlossen. Das widersprach dem beanspruchten Fail-Closed-Verhalten.

Die neue Implementierung registriert nur IDs **tatsächlich erzeugter** set_scene-Previewframes in einem begrenzten, privaten Ledger. Ein Error-Reply muss gleichzeitig die korrekte kanonische Sender-IP, den Port 55444, eine **echte Integer-ID** und eine aktuell ausstehende Handshake-, Keepalive- oder Farb-ID erfüllen. Erst dann wird die gesamte betreffende Session terminal geschlossen, der Token gelöscht und das Ledger geleert. Kein JSON-message-Text und kein unbewiesener Color4-Fehlercode wird als vermeintlicher Firmware-Quota-Beweis interpretiert: **jede korrelierte Geräteablehnung** wird konservativ terminal behandelt.

Die herstellerspezifische [Yeelight Chroma Connector Referenz CUdpLight.cpp (Commit 769a616)](https://github.com/Yeelight/Yeelight-Chroma-Connector/blob/769a616b9012877baf7fa3df426b38ca6697a167/ChromaBroadcastSampleApplication/CUdpLight.cpp) verarbeitet Token-/Keepalive-Antworten, aber keinen vollständigen Farb-ACK-Vertrag. Der neue Farbfehlerpfad ist eine **zusätzliche Host-Sicherheitsregel**, keine behauptete OEM-Protokollgarantie.

## Begrenzte Semantik statt Replay

- Unbekannte, veraltete, als String oder gebrochene Zahl kodierte IDs und fremde IP-/Port-Antworten bleiben wirkungslos. Es gibt keinen Token-Refresh durch Farb-Erfolgsantworten, keinen impliziten ACK-Zwang, keinen Auto-Reconnect und keinen TCP-Fallback.
- Auch ein späteres korreliertes Fehlerpaket darf nach einer optionalen Erfolgsantwort die Session noch stoppen. Die IDs werden **nicht** bei jedem beliebigen ACK entfernt; das würde eine verzögerte Ablehnung verdecken.
- Maximal **45** erzeugte Farb-IDs je Session, auch bei direkter Nutzung ohne Fleet. Der **46. Versuch** schließt die Simulation terminal, statt alte IDs unbemerkt aus dem Ledger zu werfen. Das ist ein synthetischer Sicherheitsdeckel, **keine gemessene Color4-Quota**. Die bestehende Fleet begrenzt ebenfalls auf 45 Versuche und 15 Sekunden.
- Die ChromaUdpOfflineFleet schließt nach einem korrelierten Fehler ausschließlich die betroffene Lampen-Lane. Eine aktive ChromaSpatialOfflineShow stoppt beim nächsten expliziten Tick dagegen ihre **gesamte** simulierte Gruppe, ohne weitere Ausgabeframes oder Wiederholungen.
- Keine echte UDP-Quelle ist allein durch ihre IP/Port-Angabe kryptografisch authentifiziert. Ein möglicher realer Transport bedarf einer separaten Bedrohungsanalyse und einer ausdrücklichen Freigabe; aus diesen Tests folgt **keine** Berechtigung zur Live-Kommunikation.

## Reproduzierbare Offline-Qualifikation

14 neue Kotlin-JVM-Tests in ChromaCorrelatedColorErrorTest.kt: v1/v2-Fake-Device-Ablehnung, nackte Farbfehler, fremde/unbekannte/typfalsche IDs, fremder Sender/Port, Replay der alten Handshake-ID, optionale Farb-Erfolgsantwort und verzögerter Fehler, gleichzeitiges Keepalive, fehlendes set_scene, terminaler Ledger-Überlauf, explizites Stoppen, unabhängige Zwei-Lampen-Quarantäne, kompletter Gruppenabbruch und **1000 simulierte fremde Fehlermeldungen in 14 getrennten Lampen-Lanes** ohne zusätzlichen Frameverbrauch.

Die Tests verwenden ausschließlich synthetische IPv4-Literale und generierte JSON-Pakete. Weder echte Gerätetoken noch private Firmwarebytes, Original-PCAPs, echte Lampenadressen, Netzwerksockets oder OEM-Binärdateien sind Teil der öffentlichen Regressionen.

## Unveränderte Beweisgrenzen

Die statisch verifizierten OEM-Funde in der originalen yeelink.light.color4 2.1.7_0039 (Fehlerstring client quota exceeded, v2-Chroma-Methodenmarker und Pointerreferenzen) sind **kein** Nachweis eines ausgeführten Chroma-Handlers oder einer funktionierenden Quota-Umgehung. Die tatsächliche Quota-Dispatch-/Refill-Ursache und das Verhalten einer realen Lampe bei Farb-Fehlerantworten bleiben unbestimmt. Die vollständige Xtensa-Analyse wird in der isolierten Forschungs-PR #48 geführt.

Alle Produkt-PRs #31–#50, die Forschung #40 und deren Vorfahren bleiben unverändert. Dieser Block erzeugt keinen installierbaren Release, keine Signierung, keinen Merge und keine Geräteaktion.
