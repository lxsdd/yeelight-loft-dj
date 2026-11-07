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
