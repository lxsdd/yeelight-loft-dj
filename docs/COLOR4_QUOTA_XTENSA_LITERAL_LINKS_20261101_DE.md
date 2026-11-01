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