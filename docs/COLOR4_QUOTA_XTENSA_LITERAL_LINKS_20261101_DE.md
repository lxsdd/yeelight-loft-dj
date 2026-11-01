# Color4 2.1.7_0039: private Xtensa-L32R-Literalreferenzen für Quota-Fehlertabelle

Stand: 01.11.2026, isolierter Forschungszweig. Keine Änderungen an Android-Lampensteuerung.

## Konkreter Nutzen

Der zuvor exakt gehashte Herstellerstand 2.1.7_0039 enthält die zusammenhängende LAN-Fehlertabelle mit `client quota exceeded` an Index 5. Die Tabelle und die Speicheradressen der sieben Herstellerstrings sind durch `tools/color4_lan_quota_firmware_evidence.py` nachgewiesen. **Der aufrufende Xtensa-Code und die Ursache der Quota-Sperre sind noch nicht bekannt.**

Der neue rein lokale Analysator `tools/color4_quota_literal_xrefs.py` liefert ohne Binär-Export:

- Verifizierte ESP32-Segmentabbildung mit strengen Datei- und Adressgrenzen, ohne Header-/Footer-/Trailerbytes als Programmtext fehlzudeuten;
- Exakte ausgerichtete 32-bit-Speicherwörter, die auf den Hersteller-Fehlertabellenanfang oder den String `client quota exceeded` zeigen; das sind potentielle Datenreferenzen, **keine** bewiesenen Funktionsaufrufe;
- Mögliche Xtensa-L32R RI16 Instruktionsbytepositionen, die bei standardmäßiger PC-relativer Literalladeart genau solche 32-bit-Wörter lesen könnten. Das Werkzeug ist absichtlich kein vollwertiger Disassembler: Es erfasst auch mögliche zufällige Instruktionsmuster in Code-/Literalbereichen und kann in seltenen Fällen LITBASE-basierte Varianten nicht erklären;
- Nur Adressen, Dateioffsets, kurze Trefferlisten und Zählwerte. Keine Original-Binärbytes, privaten Gerätekennungen, Logs, Token, Verbindungsversuche, Dateiveränderungen oder GitHub-Artefakt-Binaries.

## Strikte Vertrauensgrenze

Die öffentliche CLI akzeptiert **nur** das katalogisierte `yeelink.light.color4`-Originalimage `2.1.7_0039` (1.502.532 Bytes, SHA-256 `67f075ca909ae9f5c08ff580a4847683d1899e8ba251a7cd75d275b9848f22aa`) und zusätzlich eine strukturell eindeutige siebenfache Fehlertabelle. Bei falscher Länge, Hash, Headerstruktur oder mehrdeutiger Tabelle wird die gesamte Analyse abgebrochen (Exitcode 2). Die synthetisch testbaren Parserfunktionen erlauben **keine** Umgehung dieser CLI-Vertrauensgrenze.

Die RI16-Opcode- und PC-Offset-Berechnung orientiert sich an der offiziellen Xtensa-ISA-L32R-Beschreibung (low nibble 0001, 16-bit signiert-negative Wortverschiebung ab `(PC+3)&~3`): https://github.com/espressif/xtensa-isa-doc/blob/main/InstructionsRI16.tex . Bei aktivem LITBASE-Adressierungsmodus gilt diese PC-relative Näherung nicht. Selbst ein echter L32R-Literaltreffer beweist keine Ausführungsreihenfolge, Quota-Zählerzugriffe oder Timer-Rearm-Fehler.

## Private Anwendung

Auf dem lokalen Rechner mit dem bereits vorhandenen Originalimage (niemals nach GitHub hochladen) aus der Projektwurzel:

```bash
python3 tools/color4_quota_literal_xrefs.py /privater/pfad/yeelink.light.color4-original-2.1.7_0039.bin
```

Der Ergebnis-JSON-Text enthält nur statische Adresshinweise. Er darf **nicht** als OEM-Fix, Testfreigabe, Kompatibilitätsnachweis für Chroma oder als Firmware-Patch interpretiert werden. Nur ein korrektes Xtensa-Disassembly mit gezielten Xrefs auf Speicherladen, LAN-Dispatch, Timer-Callback und Quota-Zähler kann die eigentliche Ursache weiter einkreisen. Es wird weder ein geänderter Firmware-Blob erzeugt noch ein Flash- oder OTA-Weg eingerichtet.

## Verifikation

Eine ausschließlich synthetische ESP32-Zweisegment-Fixture prüft echte RI16-negativ verschobene Literale, separate Fehler-Tabellen-/Quota-String-Adressen, Begrenzung auf Instruktionssegmente, unaligned Datenwörter, beschädigte Header, überlaufende/überlappende Segmente, falschen Hash, Datenschutz und sichere Nichtbehauptung eines Code-/Timer-Nachweises. Pflichttest: bestehender öffentlicher Android-CI-Lauf, der alle `test_color4_*.py` und JVM-Tests ausführt. Keine Hersteller-Images in CI.

## Echtes Stock-Image diesmal lokal untersucht – konkrete Befunde

**Beweismaterial:** Die vom Nutzer als private Library-Datei in diesen Chat eingefügte
Originaldatei wurde am 01.11.2026 lokal und ausschließlich lesend verarbeitet;
sie wurde weder in GitHub hochgeladen noch als PR- oder CI-Artefakt abgelegt.
Das Ergebnis wird nur als Adressen, Längen, Trefferzahlen und Warnhinweise
öffentlich festgehalten; keinerlei proprietäre Instruktions-/Payload-Bytes.

- **Exaktes Stock-Match:** 1.502.532 Bytes; SHA-256
  `67f075ca909ae9f5c08ff580a4847683d1899e8ba251a7cd75d275b9848f22aa`;
  MD5 `f13ec12c9d53573748054b36a709e75a`.
- **Integrität:** 7 ESP32-Mappingsegmente, gültiges XOR-Checksum-Byte,
  gültige eingebettete SHA-256, gültige vier-Byte-Xiaomi-CRC.
  Keine Signatur-/Secure-Boot-/OTA-Freigabe daraus ableitbar.
- **Authentische, eindeutige 7-Zeiger-LAN-Fehlertabelle:** Datei-Offset
  `0x3E33C`, geladene Adresse `0x3FFC01B8`.
  Der OEM-Fehlerstring `client quota exceeded` ist der sechste
  Tabelleneintrag (Index 5), RAM-Zeigerwort `0x3FFC01CC`
  (Datei-Offset `0x3E350`).
- **Neue konkrete Verkettung:** Ein einziges ausgerichtetes 32-bit-Wort
  bei Datei-Offset `0x79B38` bzw. IROM-Adresse `0x40109B38`
  enthält als Literalwert die **Tabellenadresse** `0x3FFC01B8`.
  Ein bloßer Datenzeiger begründet noch keinen ausgeführten Codepfad.
- **Drei rohe Xtensa-L32R-Muster** berechnen PC-relativ exakt
  `0x40109B38` als Leseadresse:
  `0x4010AA3A` (Datei `0x7AA3A`),
  `0x40115D0B` (Datei `0x85D0B`) und
  `0x40122837` (Datei `0x92837`).
- **Beweiskritische Kontextunterscheidung:** Die erste Musterstelle liegt
  in einem Bereich mit **11 von 12 benachbarten ausgerichteten Worten,
  die wie gültige Segmentadressen aussehen** – mutmaßlich ein
  Datentabellen-Zufallstreffer. Die zweite und dritte Musterstelle
  folgen unmittelbar auf eine gemeinsame typische Xtensa-ENTRY-
  Prologsequenz, ohne solche Zeigerhäufung (0/12). Damit sind
  **zwei Stellen für eine gezielte Xtensa-Disassembly prioritär**,
  aber selbst diese sind bislang nur Byte-/Prolog-Hinweise und keine
  bestätigten, tatsächlich erreichbaren Funktionsaufrufe.
- **Kein separater direkter L32R-Treffer zum Quota-String**:
  Ein einziges Zeigerwort verweist dort auf den String; die 3
  Kandidaten hängen an der gesamten Fehlertabelle, nicht an
  einem nachgewiesenen Quota-Zähler oder Timer.

### Qualifikationsgrenze

Die echte Offlineauswertung beweist **eine statische Daten-/Literal-Kette**
bis zu zwei plausiblen Codebereichen. Sie identifiziert **nicht** den
numerischen LAN-JSON-Fehlercode, die Kontrollfluss-Erreichbarkeit der
Kandidaten, die per-Client/globalen Zähler, Refill-/Rearm-Callbacks
oder die behauptete 49,7-Tage-Ursache. Ein Power-Cycle als derzeitiger
Workaround erklärt die Ursache ebenfalls nicht.

Die neue Kontextklassifikation im Forschungsanalysator meldet
`pointer_dense_data_warning` und
`preceded_by_common_entry_bytes_hint` getrennt und erhält **alle**
rohen Kandidaten, statt unerwiesene Treffer automatisch zu verwerfen.
Zusätzliche synthetische Regressionen sichern insbesondere die
Xtensa-Sonderregel ab: Auch ein formal kleines 16-bit-Immediate
(0x0010) bezeichnet bei L32R eine **negative** Wortverschiebung,
denn die oberen 14 Offsetbits sind immer 1.
