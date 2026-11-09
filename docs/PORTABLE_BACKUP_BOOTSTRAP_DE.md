# Offline-Konfigurationssicherung (isolierter CHAT-Draft)

**Vorläufiger Bootstrap ohne produktiven Quellcode.** Kein Merge, Release, signiertes Artefakt, APK-Fork, Cloud-Write oder Lampenbefehl.

Ziel: lokale, portable, versionierte Sicherung der im eigenen Loft-DJ-Controller gespeicherten Geräte-Referenzen, Räume, Gruppen, Zuordnungen, Reihenfolge und benutzerdefinierten Szenen. Nutzerbestätigter Import mit Vorschau, geräte-ID-sicherer Zuordnung, keinem IP-/Credential-/Recovery-Export, null Befehlen an Lampen und null Cloud-Writes. Herstellerkonto-Synchronisierung bleibt bis zum Nachweis einer geeigneten Classic-API deaktiviert.

Vor *jedem* Produktquell-Write muss dieser exakte PR/Branch im privaten Portfolio-Ledger registriert und der legitime CHAT-Claim beziehungsweise die vorgeschriebene eigene Quell-CAS-Verifikation durchgeführt werden. Dieser Draft überschreibt keine bestehenden Yeelight CHAT/AGENT-/RC8-Zweige; Herkunfts-APK und private Datensätze bleiben lokal.

Verbindliche Prüfungen: Android-CI testDebugUnitTest und assembleDebug auf genauem HEAD, unabhängiger GitHub-Readback, tatsächliche Android-UI-Wiring-Tests, weiterhin gesonderte Hardware-Abnahme. Der vorhandene separate private Python-Archivprototyp ist kein integrierter Android-Code und keine Release-Qualifikation.
