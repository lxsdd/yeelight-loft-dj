# Classic 3.5.4 – Startaudit statt weiterer Provider-Vermutungen

## Anlass / belegter Fehler
Die vom Eigentümer auf dem Pixel 6a nach *jeweils vollständiger Deinstallation*
frisch installierten privaten APKs starteten wiederholt nicht. Die Crash-Kette
ging von FirebaseInitProvider/fehlender DataTransport-Klasse über den originalen
DeviceInteractionProvider zum jüngsten fehlenden DeviceBrowserProvider (privater
owner-run 42425006964 auf main e27ad3a9). Der Benutzer hat mehrfach ausdrücklich
bestätigt, dass **keine alte APK weiterinstalliert war**. Dies nicht nochmals
als plausible Hauptursache behandeln.

Der bisherige signierte private Job war trotz dieser Befunde grün. Eine konkrete
Qualifikationslücke: In seinem ersten Quellcode-Testschritt lief
test_real_classic_apk.py **vor** dem privaten OEM-Download und ohne
CLASSIC_ORIGINAL_APK. Log des Laufs 42425006964: 72 Tests, 8 skipped.
Außerdem prüfte der Final-APK-Gate nur fünf Provider und die Application auf
DEX-class_def-Vorkommen – nicht die 185 Activities, Services, Receiver, Aliases
oder die unmittelbare Superklasse-/Interface-Vererbung.

## Neue zweistufige, rein private Qualifikation
- Der neue, separat owner-dispatched GitHub Workflow
  classic-private-startup-audit.yml kann ausschließlich mit geschütztem
  classic-private-build-Environment auf main ausgeführt werden. Er holt die
  exakt SHA-256-gepinnte originale 3.5.4-APK privat, **führt die vier realen
  OEM-Tests ohne erlaubte Skips aus**, baut danach lokal eine UNSIGNED
  Clone-Kandidaten-APK, und vergleicht ORIGINAL und Clone.
- private_startup_audit.py liest die **echten** class_def-Tabellen aller
  classes*.dex, direkte Oberklassen sowie interface-Liste und vergleicht
  sie mit den Manifest-Komponenten inklusive 185 Activities und den
  Einstiegskomponenten. Die erwartete Clone-Differenz ist ausschließlich:
  zwei historisch ausgeschlossene Provider und die bekannte DJ-Activity/
  DJ-Service. Ein weiterer unautorisierter Providerentfall ist kein Fix.
- Derselbe Real-OEM-Gate und Manifest-/DEX-Audit laufen künftig **auch vor
  dem Signieren** im bisherigen owner-only Test-Signing-Workflow. Nach dem
  Signieren vergleicht private_payload_parity.py alle DEX und das binäre
  Manifest payload-byteweise zwischen unsigned und signed. Die signierte
  APK durchläuft danach den Klassen-/Manifest-Audit nochmals, bevor sie
  privat veröffentlicht werden darf.
- Die Tests des neuen Audits erzeugen **nur eigene synthetische DEX und
  Beispiel-Manifeste**. Sie enthalten keine Herstellerbytes, Zugangsdaten
  oder Live-Geräteinformationen. Der reale Prüfpfad darf nicht in PR-
  oder Push-CI mit Secrets laufen und veröffentlicht keine OEM-Artefakte.

## Interpretationsgrenzen
Eine gültige class_def und eine vollständige lokale Vererbungskette
beweisen **keine** Android-Startfähigkeit. Nicht abgedeckt sind etwa
statische Initialisierer, Methodenauflösung, native Libraries,
System-/Shared-Library-Loader oder Android-17-spezifisches Verhalten.
Der Workflow berichtet ausdrücklich RUNTIME_NOT_TESTED. Findet die neue
Prüfung eine fehlende OEM-Komponente, soll die private Audit-Route
**abbrechen und den Fehlercode melden**, statt einzelne Provider
auf Verdacht zu löschen oder weitere unbenutzbare APKs zu signieren.

## Grenzen und nächster Hardware-Gate
Zuerst den owner-only Audit-Workflow einmal erfolgreich ausführen und
dessen Ergebnis auswerten. Falls erfolgreich, vor einem neuen Besitzer-Test
einen isolierten Android-Emulator-Starttest ohne Yeelight-Konto, LAN-Befehle,
Cloud-Login und lebende Lampen prüfen. Derzeit wurde **kein** Emulator-
oder Pixel-6a-Starttest für den neuen Quellstand durchgeführt.
Private Original-APK, signierte Test-APK, Geheimnisse oder App-Sitzungsdaten
dürfen niemals in öffentliche Repositories, Actions-Artefakte oder Logs
gelangen. Originalpaket com.yeelight.cherry bleibt unangetastet.

## Privater Audit #42493590593: Original-Baseline und Klon unterscheiden

Auf Original-Main `a2e80c8` waren der geschützte Download, vier echte
OEM-Tests ohne Skips und der unsignierte Build erfolgreich. Die
Original-Startgraf-Prüfung meldete
`RETAINED_ORIGINAL_CLASS_OR_SUPER_MISSING`, bevor die Klon-Prüfung lief.

Der neue Quellcode macht **keine** automatische Ausnahme für fehlende
Manifest-Startklassen. Diese bleiben im Original und im Klon ein harter Fehler.
Eine geerbte, im unveränderten Original nicht definierte Klasse kann aus einer
getrennten Laufzeitbibliothek stammen: Eine solche *Elternkante* wird nur bei
exakter Gleichheit im Klon als **unaufgelöstes Baseline-Risiko** ausgegeben,
niemals als Nachweis, dass die Klasse auf Android vorhanden ist.
Zusätzliche Klon-Elternkanten, Vererbungsbezüge auf absichtlich entfernte
Provider sowie jede ungeklärte Kette aus der neuen DJ-Activity oder
dem DJ-Service bleiben harte Fehler.

Die Original-/Klon-DEX-Klassen samt direkten Eltern/Interfaces müssen
weiterhin vollständig übereinstimmen; der Manifest-Vergleich bleibt strikt.
Die öffentliche Diagnose zeigt nur einen Risikozähler und bereinigte Codes,
keine Originalklassennamen oder proprietäre Herstellerdaten. Der Status
`PRIVATE_STATIC_PARITY_WITH_UNRESOLVED_OEM_BASELINE_RUNTIME_NOT_TESTED`
ist keine Android-Startfreigabe. Vor einer privaten signierten
Besitzerprüfung bleiben reale OEM-Qualifikation und ein isolierter
Offline-Android-17-Emulatorstart erforderlich.
