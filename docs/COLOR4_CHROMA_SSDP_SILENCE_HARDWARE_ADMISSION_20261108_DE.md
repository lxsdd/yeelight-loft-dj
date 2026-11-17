# Color4 2.1.7_0039: unicast-SSDP-Stille und bestätigte V2-Einzelbeobachtung

**Offline-Entwicklungsnachweis, kein Live-Transport.** Dieser Zusatz gehört zum
kumulativen, bisher nicht eingecheckten Patch für den bereits offenen PR #54
(Basis `89e0f93b03ca4934af4d2bd342e998fac7fcd5db`).

## Tatsächliche Beobachtungen und ihre Bedeutung

Der Eigentümer bestätigte für die eine getestete Color4 die Firmware
`v2.1.7_0039`. Der gezielte UDP-Test mit `udp_sess_new` (Windows-Standard
V2) plus einem `set_scene` wurde als sichtbar wirksam gemeldet. Eine separate
Anfrage `udp_chroma_sess_new` erhielt eine tokenartige Antwort, aber **ohne**
Farbframe. Eine einzelne gerichtete SSDP-SUPPORT-Abfrage blieb ohne Antwort.
Das ist **kein** Beweis für fehlende V2-Unterstützung, da gerichtete Unicast-
Discovery nicht von jeder Lampe beantwortet werden muss.

Bei allen Beobachtungen gilt: Hardware-Erfolg stammt vom Eigentümer, nicht aus
Android-CI. Die tatsächliche Option-4-Kommandozeile ist nicht als vollständige
Transkriptdatei vorhanden, daher kann ein explizites `-Protocol V1` nicht aus
Rohdaten ausgeschlossen werden. Die Produktvalidierung muss das später
gegebenenfalls gesondert bestätigen.

## Quelle und Sicherheitsgrenzen

- `Color4ChromaEvidenceGate.confirmedClosedVariant` kann ausschließlich nach
  korrekter Tokenzuordnung, genau einem aufgezeichneten Farbframe,
  ausdrücklicher visueller Bestätigung und geschlossenem Versuch einen
  pro-Gerät-Protokollnachweis zurückgeben. Ein korrelierter später Gerätefehler
  widerruft ihn unmittelbar. Ein reiner Handshake oder offener Versuch genügt
  nicht.
- `Color4ChromaOfflineAdmission.assessV2` trennt eine **gerätespezifische**
  V2-Offline-Vorschau bei beobachteter Firmware `2.1.7_0039` von einer bloß
  beworbenen V2-Unterstützung. Selbst wenn ein anderer Lampentyp `SUPPORT`
  meldet, gibt dies keine Hardware-Freigabe für ihn. Umgekehrt macht eine
  fehlende Unicast-Antwort den realen Einzelversuch nicht unsichtbar.
- `deviceKey` muss durch den Aufrufer nachweislich dieselbe physische Lampe
  bezeichnen; die Vertrauensbindung und die Firmwareangabe werden von dieser
  offline-only Klasse nicht selbst aus dem Netzwerk verifiziert.
- Der Rückgabewert `permitsOfflineV2Preview` gestattet **nur eine offline
  Simulation**. `grantsLiveControl` und `demonstratesQuotaBypass` sind
  ausnahmslos `false`. Insbesondere wird der produktive Chroma-/LAN-Pfad
  dadurch **nicht** frei- oder umgeschaltet. Es gibt keine Geräte-IP,
  Netzwerksocket-, Timer-, Firmware- oder Token-Persistenz in dem Zusatz.
- Keine Übertragung auf andere 13 Lampen, keine automatische Quota-Umgehung,
  kein Retry und kein stilles Hochskalieren auf 14 Geräte.

## Geprüfte Fälle

17 ursprüngliche EvidenceGate-Kotlin-JVM-Tests und 6 neue
`Color4ChromaOfflineAdmissionTest`-Fälle: Unicast-SSDP-Stille, ausdrücklich
beobachtete Einzellampe, Firmware-Mismatch, getrennte V1/experimentelle
Methoden, spätes Fehler-Revoking, 14-Lampen-Isolation, 5.000 wiederholte
Offline-Abfragen. Tests laufen mit lokalem JUnit-Minimal-Shim, nicht mit
behaupteter Android-Gradle-CI. Zusätzlich bleiben 25 Python-Firmwaretests
mit der privaten Originalfirmware bestanden.

Eine produktive Integration in Yeelight Classic, ein Nachweis tatsächlicher
Quotagrenzen und Hardwaretest mehrerer Lampen sind damit ausdrücklich
**nicht abgeschlossen**.
