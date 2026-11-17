# Color4: Quota-Statusursprung und reale Chroma-Evidenz – 07.11.2026

**Privates Hardwarefeedback / öffentlich möglicher Quellbefund, keine Geräteaktion durch diese Analyse.**

## Exakter GitHub-Bezug

- Repo `lxsdd/yeelight-loft-dj`, **offener Draft PR #54**, HEAD
  `89e0f93b03ca4934af4d2bd342e998fac7fcd5db` bei dieser Prüfung.
- Der vorherige originale `verify-and-build`-CI-Lauf `42371648098` war
  erfolgreich, ohne veröffentlichte Artefakte. Er **prüft diesen neuen Patch
  nicht**. Der Patch ist UNCOMMITTED und lokal, ohne TASK-Claim und ohne
  GitHub-Quelländerung.
- Die zuvor nicht eingespielte Xtensa-Analyse ist im **kumulativen Patch
  enthalten**, nicht als scheinbar neue GitHub-Funktion neu implementiert.

## 1. Reale Chroma-Beobachtung – genau die belegte Bedeutung

Nutzer hat mit **einer** Yeelight Color4 und dem separat zuvor bereitgestellten
Windows-Einmaltest gemeldet:

- **Option 6:** `udp_chroma_sess_new`, eine UDP-Anfrage ohne Farbframe;
  das Tool meldete eine zur Anfrage-ID passende *tokenförmige* Antwort. Die
  tatsächlich geantwortete Methodenzeichenkette / Rohantwort wurde nicht
  übertragen, daher nicht nachträglich erfinden.
- **Option 4:** sichtbarer Farbwechsel wie beabsichtigt. Der untersuchte
  PowerShell-Code sendet dafür erst einen `udp_sess_new`-Handshake und nach
  gültigem Token genau **einen** `set_scene`-UDP-Farbframe. V2 ist sein
  **Default**; ob die Nutzereingabe den Parameter `-Protocol V1` verwendete,
  ist mangels vollständigem Option-4-Protokoll nicht unabhängig belegt.
- Hardwaremodell `color4` laut Eigentümertest; **Firmware laut ausdrücklicher Eigentümerbestätigung `v2.1.7_0039`**; die zuvor erfolglose gerichtete SSDP-Abfrage hat diese Version nicht selbst geliefert. Keine geheimen Original-IP-Adressen,
  Token, Antwortpakete oder Geräteseriennummern in diesem Bericht.

**Beweisstatus:** Damit ist *mindestens ein* praktischer UDP-Sitzungs- und
Farbsteuerungspfad auf einer echten Color4 bestätigt, zusammen mit einer
separaten akzeptierten Chroma-Methodenanfrage. Daraus folgt **nicht**, dass
`udp_chroma_sess_new` denselben Farbframe-Transport garantiert, dass beide
Protokollversionen laufen, dass mehr als eine Lampe qualifiziert ist oder dass
Chroma die LAN-Quota umgeht.

## 2. Neues Ergebnis auf der echten originalen Firmware

Exakte private Originaldatei: **1.502.532 Bytes**, SHA-256
`67f075ca909ae9f5c08ff580a4847683d1899e8ba251a7cd75d275b9848f22aa`.

Die bisherige Xtensa-Spur führte zu zwei JSON-Fehler-Ausgaberoutinen. Die
weitergehende *CALL8-Registerargument*-Prüfung ergab:

| Codepfad | Belegte direkte Aufrufstelle | Status im Argument |
|---|---|---|
| Klassischer LAN-TCP-Fehlerformatter `0x40115D08` | `0x401163A1` | **1**, konstantes MOVI in `a11` |
| UDP-Fehlerformatter `0x40122834` | `0x40122B9D` | **1**, konstantes MOVI.N in `a11` |

Xtensa CALL8 übergibt die Argumente des aufrufenden Fensters (`a10,a11,a12`)
an die neuen Register (`a2,a3,a4`). Beide Funktionskörper indizieren ihre
Fehlertabelle über `a3`, sodass diese **beiden direkten Aufrufstellen**
`invalid command` (Index 1) anfordern, *nicht* `client quota exceeded`
(Index 5). Dazwischen steht nur eine nachgewiesene Instruktion, die `a10`
und **nicht** `a11` beschreibt. Der Offline-Scanner findet je einen direkten
CALL8-Kandidaten zu den beiden Formatierern, und beide Stellen werden aus
bekannten Eingangsadressen syntaktisch verfolgt.

**Das widerlegt die bisher naheliegende Abkürzung, aus diesen beiden
Fehler-Ausgabepfaden die Quota-Auslösung abzuleiten.** Ein Quota-Fehler kann
weiterhin aus einem **indirekten** Aufruf, einer anderen Kommandoschicht oder
einem bislang nicht rekonstruierten OEM-Prüfpfad kommen. Es wurde noch **kein**
Zähler, Refill-Timer, 49,7-Tage-Fehler, globales Clientbudget oder
funktionsfähiger Firmwarefix identifiziert. Rohes Byte-Suchen ohne echte
Instruktionsgrenzen ist keine vollständige Callgraph-Rekonstruktion.

## 3. Verbesserte nächste Diagnose statt Quota-Stresstest

Das separat beigepackte private Windows-Programm `Color4-Quota-ReadOnce.ps1`
ist **nicht Teil des für PR #54 vorgeschlagenen Patches**. Es dient erst bei
expliziter manueller Nutzung als **einmalige read-only** klassische
`get_prop(power)`-Anfrage über TCP 55443 an **genau eine eingegebene private
Unicast-IP**. Keine Entdeckung, kein Music, kein RGB, kein UDP,
keine Wiederholung, kein Socket-Fallback, keine Speicherung von Token/IP.

Bei künftig spontanem `client quota exceeded` ist ein einziger echter
`QUOTA_REPORTED_BY_DEVICE`-Befund bereits bei normaler Leselast geeignet,
hohe *eigene* Kommandoanzahl als notwendige Ursache auszuschließen. Er trennt
aber Fremdclients, Firmware-Timer und fehlerhafte Sessionverwaltung noch nicht
vollständig. **Kein absichtliches Erzeugen einer Quota-Sperre** und keine
hintereinander wiederholten Kontrollen.

## 4. Qualifikation

- Kumulativer privater Offline-Quellblock für PR #54:
  `python3 -m unittest discover -s tools/tests -p 'test_color4_*.py' -q`
  => **19 Testfälle: 16 PASS, 3 SKIP ohne private Firmware**.
- Mit `COLOR4_STOCK_IMAGE=/privater/stock.bin` => **19/19 PASS**.
- Windows-Hilfsprogramm nur **statisch** getestet:
  `python3 -m unittest discover -s windows-quota-once/tests -p 'test_*.py' -q`
  => **6/6 PASS**; PowerShell 5.1 und echter Gerätezugriff noch nicht
  getestet. Statische Tests alleine qualifizieren keine Laufzeitkommunikation.
- Keine APK, kein Merge/Release, keine Firmwareschreiboperation, keine
  automatische LAN-/Chroma-Steuerung und kein GitHub-CAS-Claim.

## Nächste zulässige Fortsetzung

Für echte Quota-Ursache in Firmware: indirekte CALL-/Callback-Referenzen,
Fehlerstatus-Provenienz und LAN-Eingangs-/Socketzustand mit umfassender
Xtensa-Disassemblierung weiter rückverfolgen. Für die Produktseite sind
Hardware-Capability und Sicherheits-Timeouts als **separate**, ausdrücklich
autorisierte Android-Integration zu behandeln, nicht als Beweis aus PR #54.

## 5. Folgeblock: direkte CALLN-Vollständigkeit und getrennte Chroma-Nachweisführung

Der weitere `color4_quota_indirect_dispatch_audit.py`-Prüfer inventarisiert
sämtliche direkt kodierten CALL0/4/8/12-Potenzialstellen und ausgerichteten
32-Bit-Funktionszeiger der **zwei bisher bekannten** Fehlerformatter im
exakt gehashten Original. Je eine einzige CALL8-Stelle vorhanden, beide
Status 1; **keine** statisch gespeicherten absoluten Funktionszeiger zu diesen
zwei Formatteradressen. Die eigentliche Quota-Auslösung und dynamische
CALLX-Ziele bleiben offen. Der Prüfer bricht bei neuen/abweichenden
Aufrufkandidaten ab statt voreilig Vollständigkeit zu behaupten.

Eine eigenständige in-memory Kotlin-Komponente
`Color4ChromaEvidenceGate` dokumentiert die beiden unterschiedlichen
bereits gemeldeten Hardwarevorgänge (Option 4 und Option 6), verbietet
den Schluss von einer Lampe auf 14 sowie den Schluss von
`udp_chroma_sess_new` auf einen akzeptierten Farbframe. Einzelheiten in
`COLOR4_CHROMA_REAL_ONE_BULB_GATED_EVIDENCE_20261107_DE.md`.

**Neuer Teststand:** 25 Python-Unittests mit privatem Original und 17
lokal ausgeführte Kotlin-JVM-Tests; Quell-CI bleibt für den noch
nicht eingecheckten Gesamtblock weiterhin offen.

## 6. Korrektur nach dem Hardware-Firmware-Abgleich

Eigentümer bestätigte ausdrücklich die Firmwareversion `v2.1.7_0039` auf
**derselben** erfolgreich per Option 4 getesteten Color4. Der Firmwareversions-
Abgleich ist damit auf Ebene der Eigentümerangabe positiv und nicht mehr
"Version unbekannt". Eine unabhängige `fw_ver`-Netzwerkantwort lag nicht vor.

Das neue `Color4ChromaEvidenceGate` unterscheidet jetzt streng zwischen
*echten korrelierten Gerätefehlern* (auch nach Sitzungsende noch relevant)
und verspäteten/ungültigen *Timeout-Rückrufen* (dürfen einen abgeschlossenen
positiven Farbtest nicht widerrufen). Diese Unterscheidung ist kein Firmware-
 oder Quota-Fix und öffnet keine neue Hardware-LAN-Verbindung.
