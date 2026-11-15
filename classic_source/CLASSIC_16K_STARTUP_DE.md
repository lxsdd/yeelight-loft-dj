# Classic-Start: 16-KB-Packaging und sichtbare Navigation

Stand: 15.11.2026. Quellenänderung, **noch kein OEM-Laufzeit-PASS**.

## Nachweisbarer Fehler und Reparatur

Der geschützte Android-17-Runner wählt ausdrücklich
`google_apis_ps16k;x86_64`. Der Testsignierer benutzte dagegen bisher
`zipalign -f -p 4`: `-p` richtet unkomprimierte native Bibliotheken lediglich
auf 4 KiB aus. Jetzt verwendet der echte Signierpfad `-P 16` und prüft die
fertig signierte APK nochmals mit `zipalign -c -P 16 4`, bevor er sie oder
ihren Receipt bereitstellt. Fehler brechen vor dem Kopieren des Outputs ab.

ZIP-Ausrichtung verändert Verpackungsabstände, **keine ELF-Load-Segmente**.
Die unabhängige Signierparität vergleicht jetzt zusätzlich zu Manifest/DEX
sämtliche Ressourcen, Assets und nativen Bibliotheken bytegenau. Nur echte
JAR-Signaturmetadaten sind ausgenommen. Proprietäre Bibliotheken werden
weder umgeschrieben noch als nativ 16-KB-kompatibel ausgegeben.

Der öffentliche Test mit dem echten Android-SDK-Werkzeug reproduziert am
synthetischen APK die unzureichende 4-KB-Ausrichtung, bestätigt 16 KB über
die lokalen ZIP-Header und vergleicht alle Payloads. Im GitHub-Runner ist
dieser Test obligatorisch; lokal ohne SDK wird er ausdrücklich übersprungen.

## Warum der bisherige Overlay-Test nicht genügte

AOSP `PageSizeMismatchDialog` verwendet den App-Titel und setzt **keinen
festen technischen Window-Titel** wie `DeprecatedTargetSdkVersionDialog`.
Ein unbekannter Titel zusammen mit einer ausgewählten Classic-Activity
schließt diese Systemwarnung daher nicht aus. Der letzte private Lauf
43657046656 hat den tatsächlichen Dialog noch nicht identifiziert; die
16-KB-Warnung bleibt bis zum neuen Lauf eine begründete Hypothese.

Die neue, an den bestehenden Eigentümer-/SHA-/Offline-Pfad gebundene
Erkennung bestätigt ausschließlich einen exakt nachgewiesenen Systemdialog:

- laufender Kernel meldet 16384 Byte Seiten;
- genau ein fokussiertes Window, Classic als ausgewählte Activity;
- genau dieses Window gehört UID 1000 / Paket `android`, Typ `PHONE`;
- reine Android-Hierarchie, eindeutige Message und positive Schaltfläche;
- Message entspricht exakt einer der drei zur Laufzeit ausschließlich
  gelesenen `android:string/page_size_compat_*_warning`-Ressourcen;
- `OK` entspricht der Plattformressource, Button ist aktiviert/anklickbar
  und liegt innerhalb des verifizierten Displays;
- Fokus wird unmittelbar vor dem Tap erneut verglichen.

Keine globalen Properties, keine Manifest-Unterdrückung der Warnung,
keine Berechtigungsbestätigung, kein Cloud-Login und keine Geräteaktion.
Unbekannte/mehrdeutige Fenster bleiben unverändert. Je Navigationsschritt
wird jeder bekannte Warnungstyp höchstens einmal behandelt. Das gilt auch
nach dem DJ-Einstieg, weil die Seitenwarnung beim Activity-Wechsel erneut
erscheinen kann. Rohhierarchien und OEM-Texte verlassen den Runner nicht.

## Architektur und tatsächliche Abnahme

Für die nächste Basisqualifikation bleibt `com.yeelight.cherry` mit
unveränderten OEM-DEX-Bytes der bevorzugte bestehende Buildpfad. Der
nachgewiesene Paket-only-A/B-Crash spricht gegen weitere unvollständige
Paketumbenennungen. Die genaue OEM-Schreib-/Initialisierungskette zum
statischen Nullfeld ist weiterhin nicht bewiesen; dieser Packaging-Fix
behauptet nicht, sie repariert zu haben. Der Klon bleibt ein fehlgeschlagener
Vergleich; sein Workflow-Fehler wird nicht in einen Produkt-PASS umgewandelt.

Die Originalkennung ist **keine** Freigabe zum Überschreiben der Play-App:
eine andere Signatur erlaubt kein Update und kein Nebeneinander unter
derselben Paketkennung. Bestehende Daten werden nicht gelöscht/migriert.
Die temporäre Signatur bleibt ausschließlich im isolierten Emulator.

Der qualifizierende Navigationstest verlangt nun Original-Views zusätzlich
zum injizierten Button, tappt den sichtbaren DJ-Einstieg, verifiziert die
eigene DJ-Activity samt Controls und kehrt über Android-Zurück zur sichtbaren
Classic-Oberfläche zurück. Weder Mikrofon noch Lampen werden gestartet.
Der globale QA-Einstieg und `ClassicDjActivity` sind weiterhin eine
Interimsintegration; die originale Classic-Musikoberfläche ist damit nicht
abgenommen. Loginfreiheit, OEM-Basisfunktionen und Hardware bleiben offen.

## Öffentliche Plattformquellen

- https://developer.android.com/tools/zipalign
- https://developer.android.com/guide/practices/page-sizes
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/PageSizeMismatchDialog.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/PackageSetting.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/om/OverlayManagerShellCommand.java
