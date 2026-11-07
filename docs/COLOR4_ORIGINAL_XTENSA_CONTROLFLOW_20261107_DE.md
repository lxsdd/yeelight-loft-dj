# Color4 2.1.7_0039 – private statische Kontrollfluss-Spur, 07.11.2026

**Basis:** isolierter Offline-Forschungszweig PR #54, HEAD `89e0f93b03ca4934af4d2bd342e998fac7fcd5db`. Dieses neue Werkzeug ist ein *UNCOMMITTED* Quellvorschlag, kein veröffentlichter PR-Commit, keine GitHub-CI und keine Hardware-Abnahme.

## Beweismaterial und Vertrauensgrenzen

Originalimage `yeelink.light.color4-original-2.1.7_0039.bin` privat: **1.502.532 Bytes**, SHA-256 `67f075ca909ae9f5c08ff580a4847683d1899e8ba251a7cd75d275b9848f22aa`, ESP32 mit sieben gemappten Segmenten. Das Prüftool weigert sich bei abweichender Größe, SHA, Header oder Segmentgrenzen; es sendet keinerlei Gerätepakete und veröffentlicht keinerlei OEM-Codebytes oder proprietäre Originaldatei.

Der Decoder implementiert nur für diese Untersuchung relevante Xtensa-LX-Opcodeklassen, Instruktionslängen, L32R, direkte CALLN- und bedingte/unbedingte Branch-Ziele. Er ist **kein vollständiger Xtensa-Disassembler oder Runtime-Emulator**. Beide Ausgänge bedingter Sprünge werden syntaktisch verfolgt, auch wenn ihre realen Bedingungen sich gegenseitig ausschließen können. Indirekte Sprünge/Callbacks und Laufzeitkonfiguration bleiben außerhalb des Beweises.

## Nachweis A: Ausgaberoutinen statt Quota-Zähler

- Die gemappte Fehlertabelle liegt bei `0x3FFC01B8` (sieben Einträge, einschließlich `client quota exceeded`).
- Die zwei tatsächlich inspizierten Funktionsanfänge `0x40115D08` und `0x40122834` lesen die **gleiche Tabelle**, indizieren sie mit einer um zwei Bits verschobenen Statusnummer und laden ein String-Element. Beide beziehen **dieselbe JSON-Fehlerformatvorlage** bei `0x3F40F90B` und rufen dieselbe Format-Hilfsfunktion bei `0x4013791C` auf. Die Reihenfolgen enden mit gültigem Xtensa-Rücksprung.
- `0x401163A1` und `0x40122B9D` sind direkte, syntaktisch gültige CALL8-Aufrufstellen. Der UDP-Dispatcher hat außerdem mehrere Sprungpfade, die einen Statuswert an den gemeinsamen Fehlerformatierer übergeben. Die Frage, **wo** Statusindex 5 als Fehler erzeugt wird, ist damit noch offen. Ein JSON-Formatter ist kein Quota-Refill-Zähler.

**Neue Forschungspriorität:** Nicht den Strings/Formattern weiter folgen, sondern den Ursprung des dynamischen Fehlerstatus im `lan_clnt_recv`-/LAN-Lifecycle und die vorangehende Quota-/Clientzustandsprüfung identifizieren. Weder die behauptete 49,7-Tage-Uptime-Hypothese noch ein numerischer Color4-Firmwarezähler sind bewiesen.

## Nachweis B: Chroma-Kommandozweig im UDP-Dispatcher

- **UDP-Dispatcher-Einstieg:** `0x40122964`.
- Syntaktisch verfolgter Instruktionsgraph: **501** Positionen, kein Decoder-Abbruch in den verfolgten Zweigen (nicht gleichbedeutend mit vollständig dekodierter ISA oder tatsächlich erreichbaren Laufzeitbedingungen).
- Der Code lädt den Originalmethodennamen `udp_sess_new` bei `0x40122CA4`, vergleicht ihn im Aufrufpfad, und anschließend `udp_chroma_sess_new` bei `0x40122CBB`. Ein weiterer Chroma-Methodenzweig lädt denselben Namen bei `0x40122E2F`.
- `udp_sess_keep_alive` wird bei `0x40122D85` aus dem selben Kontrollfluss referenziert. Die Fehlerausgabe im UDP-Dispatcher ruft die eigene Fehlerformatterfunktion `0x40122834` auf.
- **LAN-Initialisierung:** Von Funktionsbeginn `0x40115E58` führt eine gültige direkte CALL8-Instruktion bei `0x40115F75` zur UDP-Startfunktion `0x40122ED4`. Dort wird bei `0x40122FD4` der Funktionszeiger auf `0x40122964` geladen und bei `0x40122FDA` in die UDP-Kontextstruktur an Offset +132 gespeichert. Dieser Verwendungszusammenhang ist stärker als eine bloße Symbol-/Pointer-Suche.

**Wichtig:** Daraus folgt eine tatsächlich im Firmwarecode vorhandene Chroma-Methodenverarbeitung, **keine** Aussage über zur Laufzeit geöffneten UDP-Port, Token-Handshake-Akzeptanz, Hardware-Kompatibilität, v1/v2-Fähigkeitsanzeige oder Quota-Umgehung. Ein Live-Hardwaretest bleibt ausdrücklich gesondert freizugeben.

## Offline-Regression

`python3 -m unittest discover -s tools/tests -p 'test_color4_*.py' -v`

Die 10 synthetischen Tests können in öffentlicher CI ohne Firmware laufen. Ein elfter Test prüft die reale private Datei **nur** bei vorhandenem Environment `COLOR4_STOCK_IMAGE`; er wird sonst übersprungen. Bei privat bereitgestelltem Original:

`COLOR4_STOCK_IMAGE=/privater/pfad/original.bin python3 -m unittest discover -s tools/tests -p 'test_color4_*.py' -v`

`python3 tools/color4_xtensa_controlflow_audit.py /privater/pfad/original.bin`

Ergebnis beim lokalen Quellblock: **11/11 bestanden gegen Originalimage; 10 bestanden + 1 sicher übersprungen ohne Originalimage.** Keine Lampe, kein Router und kein GitHub-Produktzweig wurden verändert.

## Getrennte weitere Abnahme

1. Quota: volle Xtensa-Kontrollfluss-/Datenflussanalyse bis zur *erzeugenden* Fehlerstatus-Bedingung, einschließlich möglicher Zähler-, Verbindungs- und Zeitvariablen. Die jetzige Ausgabe beweist diese Ursache nicht.
2. Chroma: Nach unabhängiger Prüfung und ausdrücklich erteilter Hardwarefreigabe ein einzelner qualifikationsfähiger Versuch mit einer gesunden Color4 (nur explizite Aktion, kein Flood, kein Auto-Fallback). Erst authentische erfolgreiche Sitzung **und** akzeptierter, beobachtbarer Farbframe sind ein praktischer Hardware-Nachweis.
3. Integration: neue Quelldateien dürfen nur nach der bestehenden echten eigenen regulären TASK-Ausführung, exaktem privaten CAS-Claim, Quell-Ref-Fencing und SHA-spezifischer Pflicht-CI auf PR #54 veröffentlicht werden. Dieses lokale Ergebnis ist nicht produktiv verdrahtet.

**Opcode-Recherchegrundlage:** Xtensa-ISA-Dokumentation und unabhängig veröffentlichte Xtensa-LX-Dekoder-Testvektoren (z. B. `github.com/loov/disasm/xtensaasm`). Der Quellvorschlag verwendet keine kopierten OEM-Instruktionsbytes und keine fremden Pakete als Laufzeitabhängigkeit.
