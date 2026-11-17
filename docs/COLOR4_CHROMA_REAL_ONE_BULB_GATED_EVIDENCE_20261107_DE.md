# Color4 Chroma: echten Ein-Lampen-Test korrekt in sichere Produktbeweise überführen

**Stand:** 07.11.2026; isolierter Offline-Forschungszweig PR #54, Basis-HEAD `89e0f93b03ca4934af4d2bd342e998fac7fcd5db`.

## Tatsächlicher Nachweis und Grenzen

- Eigentümer meldete **Option 6** der Einmaltest-Konsole: UDP-`udp_chroma_sess_new` an genau einer Color4, Antwort auf die korrekte ID mit tokenförmigem Inhalt. **Kein** Farbframe in Option 6. Der tatsächliche Methodenname aus der Rohantwort ist nicht protokolliert; daher keine unbewiesene Behauptung dazu.
- Eigentümer stellte klar, dass der **sichtbare Farbwechsel in Option 4** aufgetreten ist. Die Konsole sendet dazu im Standardmodus `udp_sess_new` (**V2**) und erst nach gültiger Tokenantwort *einen* `set_scene`-Farbframe. Eine abweichende manuelle `-Protocol V1`-Kommandozeile ist nicht durch die Rückmeldung ausgeschlossen.
- Belegt: mindestens ein funktionierender UDP-Farbpfad auf einer Color4 und ein separater Chroma-Methodenstart. **Nicht** belegt: dauerhafter Betrieb, andere Lampen, aktivierte V1-Variante, erfolgreiche direkte Farbkommandos nach `udp_chroma_sess_new`, Umgehung/Heilung der klassischen Quota.
- Die Original-Firmware `2.1.7_0039` wurde separat SHA-gebunden statisch analysiert. **Neuer Eigentümer-Nachtrag (07.11.2026):** Der Nutzer bestätigt ausdrücklich, dass auf der erfolgreich getesteten Lampe `v2.1.7_0039` installiert ist. Das verknüpft den funktionierenden Ein-Lampen-Farbtest mit dem privat analysierten Original-Firmwarestand. Es handelt sich um eine Eigentümerangabe, nicht um eine unabhängig aus SSDP oder `get_prop` gelesene `fw_ver`-Antwort (Unicast-SSDP blieb bei Option 2 unbeantwortet).

## Neu hinzugefügt: `Color4ChromaEvidenceGate` – ausschließlich JVM/in-memory

Sicherheitsmodell für eine **einmalige** Auswertung je Gerät und expliziter Protokollvariante; keine `DatagramSocket`, kein Android-API-Aufruf, kein IP-/Token-Speichern, kein Recovery-Timer, kein normaler LAN-Fallback.

1. Jeder Versuch erhält eine eigene, monotone Generation; fremde/alte Geräte- oder Versuchs-IDs können keinen Erfolg erben.
2. Ein v1-/v2-Handshake erfordert genaue Antwortmethode, Integer-ID und tokenförmigen Inhalt; das Token wird nicht dauerhaft gehalten. Experimental-`udp_chroma_sess_new` wird getrennt als `EXPERIMENTAL_METHOD_ONLY` erfasst und kann **niemals** einen Farbframe zulassen.
3. Das Senden eines Farbframes ist **nicht** dessen Erfolgsbeweis. Erst die ausdrückliche Beobachtung des Betreibers nach exakt einem Frame erlaubt `COLOR_VISUALLY_CONFIRMED`.
4. Nachträglich korrelierte **echte Gerätefehler** (Anfrage-ID 1 oder nach tatsächlichem Farbframe Anfrage-ID 2) nehmen einen zuvor bestätigten Test auch nach dem Sitzungsende zurück. Bloße Timeout-Rückrufe nach gültigem Handshake oder sichtbarem Farbwechsel dürfen einen bestätigten Erfolg dagegen nicht widerrufen. Unklare Sendefehler/Timeouts wirken nur auf noch offene und nicht entschiedene Anforderungen. Kein blindes Wiederholen; ein neuer Versuch erfordert `clearOnlyByOperator` **nach** geschlossener Sitzung.
5. Pro-Gerät-Isolation auch bei 14 Testobjekten: ein bestätigter Versuch schaltet keine anderen Lampen frei. Das Modell liefert auch nach positivem Einzeltest **keine** globale Hardware-Freigabe oder automatischen Live-Gerätescan.

**Qualifikation:** 17 Kotlin-JVM-Tests mit einem rein lokalen JVM-Testshim, plus alte und neue Python-OEM-Offline-Regressionen. Der Testshim gehört nicht zu PR #54 und ersetzt keinen Android-Gradle-/GitHub-CI-Lauf.

## Einordnung des echten Hardwaretests

- Option 4: sichtbare UDP-Farbänderung über den in der gelieferten Windows-Testdatei voreingestellten `V2`-Pfad `udp_sess_new` + `set_scene`; keine bestätigten Farbframes über die experimentelle Methode aus Option 6.
- Option 6: tokenförmige Antwort auf `udp_chroma_sess_new`, ohne Farbframe.
- Separate klassische TCP-`get_prop(power)`-Leseprobe: `READ_ONLY_GET_PROP_ACCEPTED`, 305 ms, damit gerade **keine** reproduzierte Quota-Störung.
- Die Eigentümer-Firmwareangabe `2.1.7_0039` stimmt zum gehashten OEM-Image. Es gibt keine Autorisierung zu unkontrolliertem Live-Traffic, Protokoll-Fallback, Quota-Bypass oder zusätzlichen Lampenversuchen.

## Quota-Verdachtsraum (neu überprüft)

Im privat SHA-verifizierten `2.1.7_0039`-Abbild wurden alle `CALL0`, `CALL4`, `CALL8`, `CALL12`-Instruktionsbytekandidaten für beide bekannten Error-Formatter-Einstiegspunkte geprüft. Pro Formatter existiert nur **eine** direkte CALL8-Kandidatenstelle. Beide direkten Aufrufer übergeben den konstanten Statusindex **1** (`invalid command`). Zusätzlich sind **keine** direkt als ausgerichtete 32-Bit-Funktionszeiger gespeicherten Adressen dieser zwei Formatter im gemappten Image vorhanden. Das verengt den Suchraum, widerlegt jedoch *nicht* eine andere Fehlerausgabeschicht oder dynamisch aufgebaute `CALLX`-Ziele.

**Erforderliche neue Evidenz für eine tatsächliche Firmware-Root-Cause:** live `fw_ver` der Fehlerlampe, Zeitpunkt/Verbindungskontext eines echten `client quota exceeded` und ein konkreter Pfad im Control-/Timer-Code, der den Fehlerstatus erzeugt. Keine künstlich herbeigeführte Quota durch hohe Last.

## GitHub-/Produktgrenze

Alle neuen Module sind **offline-only und UNCOMMITTED**. Aktuelles GitHub-PR54-HEAD und alte CI sind lediglich Basis, nicht neues Testergebnis. Der Aufgabenvertrag erlaubt keinen interaktiven Remote-Write unter einer fremden/abgeschlossenen Scheduler-Run-ID. Der bestehende Original-Pfad für produktiven Music Mode bleibt getrennt; diese Modelle bauen noch keine installierbare Classic-App, keinen Chroma-Live-Transport, keinen Firmwarepatch und keine Quota-Lösung.
