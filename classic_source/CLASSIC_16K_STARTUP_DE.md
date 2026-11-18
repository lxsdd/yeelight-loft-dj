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
# Nachtrag: geschuetzter Lauf 43780547994, 16. November 2026

**Im echten öffentlichen Emulator reproduzierter Parserfehler:** Lauf
`43837733921` auf `cad378428f79b4bfcc23093dd0d4b4abc874a200` bestätigte
16-KiB-Seiten und die eindeutige Bindung des aktiven Fensters, scheiterte aber
mit `window_type=UNPARSEABLE`. AOSP `LayoutParams.toString` schreibt
`sim={...}` vor `ty=...`; der bisherige reguläre Ausdruck endete an der inneren
schließenden Klammer. Der Parser liest nun den vollständig ausbalancierten,
größenbegrenzten Attributblock des eindeutig gebundenen Fensters. Tests decken
verschachtelte Attribute, Mehrdeutigkeit, abgeschnittene Blöcke, falsche Typen
und den Versuch ab, einen Typ aus einem anderen Fenster zu übernehmen.
Das belegt den Parserfehler, weiterhin nicht die Identität des privaten Overlays.

**Zweiter realer Befund:** Lauf `43839543548` auf
`0b1508592701902a53a5a0202a0c7ba92bbbd2fa` konnte den Fenstertyp korrekt
lesen und den APK-Warntext laden. Die ELF-Warnressource fehlt in diesem Image
jedoch tatsächlich (`RESOURCE_NOT_FOUND`), auch im selben Root-Debug-Kontext
wie im geschützten Test. Deshalb werden die fest erlaubten Ressourcen jetzt
einzeln gelesen. Für einen Tap sind weiterhin ein exakt gelesener Warntext,
die passende positive Schaltfläche und alle Fenster-/Fokusprüfungen nötig.
Fehlende Varianten werden weder erfunden noch durch fest codierten Text ersetzt.
Der öffentliche Test qualifiziert ausdrücklich nur die vorhandenen Varianten.

Der Lauf auf `ae10e7a2456efe74371d0757aad13a748b5218cf` bestand Build,
Testsignierung einschließlich finaler 16-KiB-ZIP-Prüfung und vollständiger
Payload-Parität. Die Originalidentität lief im kurzen Beobachtungsfenster ohne
Zielabsturz, aber weiterhin ohne sichtbare OEM-Oberfläche: 16 Android-Knoten,
angelegter und ausgelegter DJ-Einstieg ohne Fensterfokus, kein DJ-Tap.
Clone und reiner Paketnamen-Kontrollbau reproduzierten die Null-Dereferenz.
Damit ist weder das verdeckende Fenster identifiziert noch die Classic-UI akzeptiert.

Die öffentliche No-OEM-Emulatorprüfung prüft jetzt zusätzlich echte Antworten
von `getconf`, WindowManager und den vier fest definierten Framework-Ressourcen.
Sie installiert keine App, signiert nichts und führt keine UI-Aktion aus.
Ein aus den echten Ressourcen erzeugter synthetischer Hierarchie-Test ist
ausdrücklich kein Nachweis eines sichtbaren Systemdialogs.

Der private Erkennungspfad nennt bei Ablehnung nun den tatsächlich gescheiterten
Prüfschritt: Seitengröße, Fokus/Fensterbindung, System-UID/Fenstertyp,
eindeutige Nachricht/Schaltfläche, exakter Ressourcentext, zusätzliche interaktive
Elemente oder Geometrie. Es werden nur feste Statuswerte, begrenzte Anzahlen
und Wahrheitswerte ausgegeben. Rohtexte, OEM-Bezeichner und Hierarchien bleiben
privat. Die Anforderungen für einen Tap werden nicht gelockert.

---

## 2026-11-16 — öffentlicher Emulator: keine Root-Voraussetzung

Der öffentliche API37-Lauf #43851581131 wurde schon vor der reinen
Warnungsprüfung mit `EMULATOR_ROOT_UNAVAILABLE` abgebrochen. Die temporär
hinzugefügte `adb root`-Bedingung war für die ausschließlich lesenden
öffentlichen `getconf`-, `dumpsys`- und `cmd overlay lookup`-Abfragen
nicht erforderlich. Die öffentliche Prüfung bleibt deshalb bewusst
**shell-only**, kontrolliert weiterhin QEMU und SDK 37, und erzwingt weiterhin
den vollständigen Read-only-Runtime-Contract. Die eigenständig geschützte
private Original-APK-Ausführung bleibt unverändert und eigentümergesteuert.

Die neue ELF32-/ELF64-Zählung aus `private_payload_parity.py` ist eine
zusätzliche private Aussage über **PT_LOAD-Ausrichtung**, keine ELF-Reparatur,
keine sichere Aussage zu RELRO und keine Funktionsabnahme. Es werden keine
Bibliotheksnamen oder Binärdaten exportiert.

## 2026-11-16 — AOSP-Lookup: Ressourcenreferenz ist kein Dialogtext

Die [AOSP-Implementierung von `cmd overlay lookup`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/om/OverlayManagerShellCommand.java) kann eine `TypedValue`-Referenz und einen getrennten, aufgelösten String ausgeben. Ein kurzer Shellwert ist daher **kein Beweis für einen tatsächlich kurzen `PageSizeMismatchDialog`-Text**. Der bislang rote öffentliche API37-No-OEM-Test #43852387856 hatte bei `APK_ALIGNMENT` `minimum_rendered_message_length=false` gemeldet. Er installierte keine App und zeigte keinen realen Dialog.

Die Ressourcenbehandlung akzeptiert nur eindeutig aufgelöste AOSP-Referenzen `@0x........ -> TEXT` beziehungsweise `@android:string/NAME -> TEXT`. Ein nacktes `@...`, unaufgelöste Alias-Ketten oder beliebige ` -> `-Zeichen bleiben unverifiziert und **können keinen Tap autorisieren**. Die ursprünglichen strengen Regeln über UID, fokussiertes Fenster, Nachrichtenlänge, echte UI-Hierarchie, Original-App, exakte Schaltfläche und Fokusrennen bleiben bestehen.

Wenn nur eine unaufgelöste oder kurze Framework-Ressource vorhanden ist, aber die echte QEMU-/SDK-/16-KiB-/WindowManager-Prüfung erfolgreich war, kann der **rein öffentliche Boot-/Parserlauf** den explizit eingeschränkten Status `PUBLIC_WARNING_SHELL_PROBE_SAFE_UNVERIFIED` mit nachgewiesener negativer Nicht-Aktionsprüfung erreichen. Dies ist **keine** Qualifikation der Dialogerkennung, keine private UI-Abnahme und kein Hardware-/OEM-Test. Wenn sämtliche Warnressourcen fehlen, die OK-Ressource fehlt oder die echten Fenster-/Seitengrößenprüfungen nicht bestehen, bleibt der öffentliche Lauf rot. Aus fehlender Laufzeit-Auflösung wird niemals eine feste Ersatz-Übersetzung oder ein automatisches OK konstruiert.


## 17.11.2026 – First-run UI attribution (source-only follow-up)

The owner-only Android17 candidate can resume its OEM Classic Activity without a
confirmed target-process fatal yet display no native entry on the first offline
frame. The normal clone only places the entry inside `activity_main_new.xml`;
this does not establish that the first resumed OEM Activity inflated that view.
The isolated UIAutomator classifier now records only allowlisted node-origin
categories (`CLONE_ONLY`, `CLONE_AND_OTHER`, `ANDROID_SYSTEM_ONLY`,
`OTHER_OR_UNVERIFIED`, `UNATTRIBUTED`), without exporting OEM strings,
package identities or XML. Positive entry/navigation recognition requires
controls attributed to the exact installed clone package and not disabled.
An absent entry remains blocked, no blind taps or new launches occur, and
OEM main initialization, MiOT binding, account/login and functional Classic
Music integration remain unverified. Synthetic positive/negative tests cover
OEM welcome, Android system UI, spoofed text, mixed surfaces and disabled
buttons. This source change cannot itself classify a page as a login screen,
fix 16-KiB OEM ELF compatibility or qualify the original Music UI. A fresh
owner-scoped offline emulator observation remains separately gated.

The owner-only original-identity A/B probe is a separate, explicitly allowed
`com.yeelight.cherry` diagnostic branch. Its caller passes that exact expected
package to UI classification and coordinate selection; the default clone
probe continues to require `io.lxsdd.yeelightdj`. Foreign package strings
are never accepted, and A/B observations remain non-qualifying for product
acceptance.


## 17.11.2026 – Strict original/clone 16-KiB system-warning transition

Protected run #44124269057, candidate `f3e4c4c1b6ea01a4898bc3f55f7ea51cf01778c9`, has valid
private OEM build, DEX graph and test-signing parity, but the original
Classic Activity is only resumed behind an `ANDROID_SYSTEM_ONLY` first UI
hierarchy; no target process fatal, MiOT/login or DJ entry has been proven.
The next source-only candidate reuses the already-qualified AOSP 16-KiB
warning matcher for the clone with an explicitly selected, exact app ID.
Only a unique, focused UID1000 `android` TYPE_PHONE warning, 16-KiB kernel,
exact device framework message/positive button and verified bounds can trigger
one compatibility acknowledgment per Activity. Focus and air-gapped network
state are rechecked before a tap; screenshots stay ephemeral. All unknown,
permission, account, ANR and other UI states remain blocked without action.
Acknowledgment cannot itself qualify Classic startup or the native DJ entry.
The identity-control original package remains the legacy default; no
physical Android, LAN, firmware, signing, account or user data are touched
by source-only development. New synthetic positive/negative tests cover
clone vs original package, resources, focus, system warning, permission,
page size, and absence of blind taps. Private runtime requires a separately
owner-dispatched exact-SHA workflow.


## 18.11.2026 – geschützter OEM-Lauf #44200821367, TYPE_PHONE-Hypothese widerlegt

Kandidat PR108 `04fffeb8308456a8d34a3cf6fb7f7581de01970e`; Owner-Gate,
alle drei öffentlichen CI-Jobs, OEM-Reassembly, Klassenparität und flüchtige
Testsignatur bestanden. Emulator nach erster 3-s-Beobachtung:
`SAME_CLONE_ORIGINAL_ACTIVITY` resumed, kein Target-Fatal, UI
`ANDROID_SYSTEM_ONLY`, `window_owner=ANDROID_SYSTEM`,
`focused_app=CLASSIC`, 16-KiB-Page-Kernel, aber
`window_type=OTHER` / `OWNER_TYPE_OR_SELECTED_APP_NOT_VERIFIED`.
Daher kein Warnungs-Tap, keine sichtbare DJ-Navigation, Exit 2.
AOSP `PageSizeMismatchDialog.java` und `DeprecatedTargetSdkVersionDialog.java`
setzen beide `TYPE_PHONE`; hier ist die Warnung **nicht** sicher zugeordnet.
Der nächste Quellstand ergänzt ausschließlich nicht-sensitive kategorisierte
Window-Type-/Focus-Diagnose und eine einzige passive 8-s-UI-Nachbeobachtung
bei Android-only-Systemoberfläche, auch nach Activity-Wechsel. Kein Dialog,
Permission, Account, LAN oder Gerät wird dadurch bedient; nur sichtbar echte
Classic-Klonelemente und Crash-freier Resumed-Zustand erlauben Navigation.
Fake-ADB-Regressions prüfen transienten und persistierenden System-Vordergrund
und verbieten Systemklicks. OEM-Laufzeit bleibt owner-only/unqualifiziert,
weitere Änderungen benötigen 3x exact-SHA öffentliche CI.


## 18.11.2026 – private Gradle bridge failure #44203013685

Owner-pinned candidate `4bb706f4c835d43a46e2496cf45e0a7e4ebd5ee6`:
source/owner/exact-SHA CI and private APK fetch passed, but private build stopped
at `ANDROID_BRIDGE` / `PROCESS_FAILURE`; the action summary says the
public-source-only `YeelightClassicPrivateBridge:stageBridgeInputs` failed.
The private build uses Gradle 9.8.2, while public `verify-and-build` uses
8.13. Both passed earlier for this bridge; the current exception is unknown,
not proof of compiler vs network vs daemon failure. OEM APK never enters Gradle.

The CHAT successor adds only private ephemeral-file Gradle output capture and
strict fixed-vocabulary diagnostics: temporary network, compiler, missing
dependency, daemon, timeout, or unclassified process failure. Only recognized
transient network I/O retries once (maximum 2 attempts). All raw Gradle output
is unconditionally deleted, never in stdout, public logs or job summaries.
No dependency pin, target SDK, class graph, sign/merge/release or device gate
is weakened. Public synthetic positive/negative regressions exercise retry,
fail-closed compiler/unknown errors, and disclosure prevention. The trusted
main protected workflow's differing Gradle version is not changed by this PR.
Real OEM/emulator qualification still requires separate owner-approved dispatch
and exact-SHA three-job public CI first.
