# Color4 Chroma – Offline-Nachweis gegen gefälschte Tickets und Replay

**Stand:** 2026-11-08 · **Scope:** `lxsdd/yeelight-loft-dj`, PR #54, Offline-Modell. Keine Netzwerkschnittstelle, keine APK-Bedienung, kein LAN-/Gerätetest.

## Bestätigte Hardware-Evidenz (vom Betreiber berichtet)

Auf **einer** Color4 mit Original-Firmware **2.1.7_0039** lösten `udp_sess_new` + ein einzelnes `set_scene` (PowerShell-Option 4) einen sichtbaren Farbwechsel aus. Der experimentelle Einzelaufruf `udp_chroma_sess_new` (Option 6) erhielt separat eine tokenförmige Antwort, **ohne** ein Farbframe zu senden. Eine gerichtete SSDP-Unicast-Abfrage wurde nicht beantwortet. Klassisches `get_prop(power)` funktionierte bei einem separaten einmaligen Leseversuch. Das belegt weder vollständige Chroma-Methodenparität, Quota-Umgehung noch die Eignung anderer Lampen.

## Reproduzierbare Integritätslücke in der bisherigen Offline-Gate-Implementierung

Die ursprüngliche öffentliche `data class Attempt(deviceKey, generation, variant)` war ein **Werteobjekt**. Damit konnte ein anderer Aufrufer einen *gleichen* Ticketwert erstellen, ohne das vom Gate ausgegebene Objekt zu besitzen. `matching()` verwendete ebenfalls Wertgleichheit (`==`). Ein mit identischen Feldern nachgebautes Ticket wurde deshalb für Tokenreply, Farbframe, Sichtbestätigung und sogar für korrelierte Fehler/Abschluss akzeptiert. Die Regression `forgedMatchingTicketCannotMintOrRevokeHardwareProof` schlug vor der Korrektur tatsächlich fehl.

**Abhilfe:** `Attempt` ist nun ein normales, final geschlossenes Kotlin-Objekt (`class` statt `data class`), ohne `copy`/Destrukturierung/Wertgleichheit. `matching()` verlangt Referenzidentität (`===`). Ein künstlich erzeugtes Objekt – auch mit gleichen Feldern oder aus einem zweiten Gate mit derselben Generation – kann keine Beweislage verändern. Das Ticket wird weiterhin nur im Arbeitsspeicher verwendet; eine Serialisierung wäre keine gültige Wiederherstellung. Die Identität stellt **keine kryptografische Authentisierung** gegenüber untrusted IPC/Plugins dar: Der Transport muss Sender-IP, Sender-Port und ganzzahlige JSON-ID unabhängig prüfen und das echte Ticket schützen.

**Zusätzliche Fail-Closed-Regel:** Eine Antwort mit fremder/nicht korrelierender Request-ID wird weiterhin ignoriert. Bei nachweislich korrelierter Antwort (Handshake-ID 1), aber falschem Methodennamen oder ungültigem 32-stelligem Hex-Token, schließt die Evidenzspur jetzt terminal als `REJECTED_OR_UNCERTAIN`. Ein späteres korrektes Token kann den gleichen Versuch nicht rehabilitieren. Neues Testen erfordert `clearOnlyByOperator()` nach geschlossenem Versuch; es erfolgt kein automatischer Retry.

## Regressionen und Grenzen

- Vorher reproduzierbar: der gezielte gefälschte-Ticket-Test scheiterte auf der alten Implementierung.
- Danach: 27/27 Kotlin-JVM-Tests inklusive identischem Ticketwert, anderem Gate mit gleicher Generation, 5.000 Fälschungsversuchen, korreliertem Fehl-Handshake, separatem Operatorreset, 14 isolierten Lampen und einem sichtbaren V2-Einmaltestpfad bestanden.
- Vorhandene offline-only Zulassung bleibt unverändert: **keine** automatische Freigabe für Live-LAN-Sockets, Music-Channel, App-Produktivverdrahtung oder Quota-Bypass.
- Neue JUnit-Tests werden über die bestehende Android-`verify-and-build`-CI ausgeführt. Der lokale Kotlin-Testlauf ist **kein** Ersatz für die GitHub-CI am endgültigen Commit.

**Sicherheitsgrenze:** Keine verbundene Hardware, kein Flash/OTA, keine normalisierten Tokenlogs, keine proprietären Firmware-Dateien, keine Freigabe für andere Geräte und keine Klassik-/Google-Home-Quelländerungen.
