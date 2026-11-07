# Classic – Startabsturz der ersten privaten Test-APK

Beobachtung des Eigentümers (07.11.2026): Der geschützte Testbuild `42413144794`
auf dem main-Commit `b4419e1bddd16cc2b4638d10e5855c2b6b1bd4d7` wurde korrekt
gebaut, signiert und privat hinterlegt, **startet aber auf seinem Gerät nicht**.
Das ist ein echter Abnahmefehler: Bausignatur und statischer Preflight sind
kein Android-Starttest.

## Konkrete reproduzierbare Quellrisikostelle

Die erste Integration änderte das ursprüngliche Classic-Hauptlayout von
`LinearLayout` auf `FrameLayout` und schob das OEM-Layout darunter.
OEM-Code kann auf den ursprünglichen Root-Typ oder die direkte Hierarchie
angewiesen sein. Das kann beim Launch eine `ClassCastException` auslösen;
der Stacktrace fehlt, also **noch keine bestätigte Ursache**.

Die Korrektur behält Original-Root, alle Root-Attribute und die Reihenfolge
der fünf Original-Kindelemente unverändert. Der DJ-Einstieg kommt als letztes
Kind in dieselbe geprüfte `LinearLayout`. Abweichende Root-Typen und ein
zweiter Patch werden fail-closed verweigert. Synthetische Regressionstests
prüfen Root-Typ, Original-Attribute/-Kindreihenfolge und Negativfälle.

## Verbleibende reale Startdiagnose

Der korrigierte Quellstand ist **kein akzeptierter Android-Kandidat**, bis
ein neuer privater Owner-Build auf exakt seinem SHA und ein echter App-Start
erfolgen. Falls weiterhin ein Sofortabsturz auftritt, bitte nur den
`FATAL EXCEPTION`-Abschnitt von AndroidRuntime mit `Process:
io.lxsdd.yeelightdj` und circa 20 folgenden Stacktrace-Zeilen bereitstellen
(keine kompletten Logs, keine Kontodaten, privaten IPs oder Tokens).

Optional auf einem Windows-PC mit bereits eingerichteten Android Platform
Tools, USB-Debugging nur mit freiwilliger Gerätefreigabe:

```powershell
adb devices
adb logcat -c
# APK einmal auf dem Gerät starten, Absturz abwarten
adb logcat -d -v threadtime -s AndroidRuntime:E
```

Keine erneuten Lampensteuerungsversuche, kein firmwarebezogenes Experiment.
Bekannte Yeelight-Classic-Bluetooth-/Android-17-Korrelation ist ein separater
möglicher Pfad und **nicht** ohne konkreten Stacktrace bewiesen.

Die nächste signierte private APK benötigt ihren eigenen Owner-Freigabeweg;
der vorherige private Draft-Release bleibt unverändert und nicht öffentlich.

## Bestätigter Startfehler aus Eigentümer-ADB (07.11.2026)

Das zweite signierte private APK aus Workflow `42415686872` (main `73ead136`)
startet trotz vorheriger Root-Layout-Korrektur nicht. Der Stacktrace wiederholt
über zahlreiche Starts dieselbe `NoClassDefFoundError` /`ClassNotFoundException`
für `com.google.android.datatransport.runtime.dagger.internal.b`. Der Aufrufpfad
ist `CrashlyticsRegistrar` → `FirebaseInitProvider.onCreate` → Android
`ActivityThread.installContentProviders`, **vor** jeder Activity. Die vorherige
Layout-Hypothese erklärt den beobachteten Crash nicht.

**Zielgerichtete private Anpassung:** Entferne ausschließlich den früh startenden
`com.google.firebase.provider.FirebaseInitProvider` aus dem privaten Klon, nur bei
bekannter originaler Authority und genau einer nicht-exportierten Instanz. Alle
anderen sechs OEM-Provider, originalen ausführbaren DEX und Quell-Anker bleiben
unverändert. Der neu gebaute binäre Manifest-Baum wird nochmals geprüft:
sechs Provider, keinerlei Firebase-Autostart-Eintrag/Authority. Negative synthetische
Tests verhindern unbemerkte Erweiterung. Es gibt **keine** vorgetäuschte Ersatzklasse.

Dies verhindert den belegten *frühesten* Absturzpfad, beweist aber keinen vollständig
funktionierenden App-Start. Firebase-Crashlytics/Analytics sowie eventuell davon
abhängige Yeelight-Cloudfunktionen sind bei dieser isolierten LAN-first-Abnahme
nicht unterstützt; optionale Anmeldung braucht eigene explizite Init-Integration
und Hardware-Abnahme. Neue private APK nur nach bestehender manueller
Owner-Freigabekette, niemals öffentliche OEM-Artefakte.

**Getrennter Befund:** Couchtisch zeigte `music_on=1`, bis der Nutzer Music in
Yeelight Classic pausierte; danach zeigte Loft DJ `music_on=0`. Nach Yeelight-LAN-
Spezifikation beendet explizites `set_music([0])` oder das Schließen des
Reverse-TCP-Kanals die Session; während Music werden keine Property-Notifications
gesendet. Dieser Zustands-/Recovery-Fall gehört in die bestehende Color4-/RC16-
Entwicklung, nicht in den Firebase-Startfix. Kein Hardwarekommando aus CI.

## Dritter Pixel-6a-Startabsturz nach PR74 (07.11.2026, 23:46)

Der Owner-Build `42421751285` ist auf main `49b73cd18e34cf4776a9d2534b9ab9b02c6cbab2`
korrekt signiert und als PRIVATE DRAFT `437891624` abgelegt. Der neue
`AndroidRuntime`-Stacktrace nennt **nicht mehr** Firebase, sondern
`java.lang.ClassNotFoundException: com.yeelight.yeelib.data.DeviceInteractionProvider`
beim Installieren der Manifest-`ContentProvider` vor `Application.onCreate`.

Neue source-only Reparatur für den privaten LAN-first Klon: genau diesen
legacy Provider nur bei eindeutiger OEM-Klasse, einer einzigen Instanz,
keiner exportierten Komponente und einer original bekannten Authority
entfernen. Andere Provider und OEM DEX bleiben unverändert. Der
finale private APK-Build prüft nun **alle verbleibenden Provider und die
Application** gegen echte `class_def`-Deskriptoren **aller** DEX-Dateien in
der signaturfreien APK. Bei fehlender Klasse wird der PRIVATE Bau vor der
Signierung abgebrochen, statt erneut einen fehlerhaften APK-Kandidaten
zu erstellen. Positive/negative Testfixtures ergänzen die statischen CI-Gates.

Diese Vorsichtsmaßnahme kann die Yeelight-eigene `DeviceInteractionProvider`-
IPC-Schnittstelle einschränken; lokale Geräte-/Cloudfunktionen sind deshalb
nicht als funktionsfähig behauptet. Bei weiteren fehlenden DEX-Klassen ist
die Original-APK-/Split-Kompatibilität zu überprüfen. Ein grüner Build ist
immer noch kein realer Android-Start- oder LAN-Hardwaretest.
