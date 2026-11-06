# RC9: abgesicherter Einzel-Color4-Musikrückkanal

Isolierter Draft-PR #61 auf RC8 #58. Keine Änderungen an main, #54, #57, #58, #59 oder #60.

## Ablauf in der neuen Android-Testoberfläche

1. Eine intakte, zuvor über Yeelight Classic eingeschaltete Color4 wählen. SSDP muss Modell color4 sowie SUPPORT für set_music UND set_rgb tatsächlich bestätigt haben; keine unbekannte Lampe. Alle anderen Yeelight-Controller müssen den LAN-Port freigeben.
2. Mikrofonanalyse oder zulässige interne Audioanalyse starten. Auf der Musikseite **Color4-Transporttest · 8 Sekunden · Music-Rückkanal** wählen und den einmaligen Warnhinweis ausdrücklich bestätigen.
3. Die App führt nur ein get_prop einschließlich music_on aus; power=on und music_on=0 müssen bestätigt sein. **Vor** set_music(1) wird ein haltbarer unbestätigter Marker geschrieben. set_music(1) wird über normalen TCP ausgeführt, anschließend wird dieser TCP-Slot freigegeben.
4. Nur bei wirklich aufgebautem Rückkanal und real gemessenem Audio dürfen bis acht RGB-Nachrichten über diesen separaten Music-Socket gesendet werden, höchstens einmal pro Sekunde. Kein normaler TCP-RGB-Fallback, kein Einschalten, keine parallel gestartete Gruppe.
5. Ende durch Schließen des Music-Rücksockets **ohne zusätzlichen automatischen set_music(0)**. Nach kurzer Pause prüft ein einzelnes get_prop einschließlich music_on, ob die Firmware music_on=0 meldet. Das ist kein Farb-/Funktionsbeweis. Erst bei bestätigter 0 wird der Marker mit dauerhafter Commit-Rückmeldung gelöscht. Sonst bleibt der Test gesperrt; ein separater Button erlaubt **eine ausdrücklich angeforderte lesende** erneute Prüfung.
6. Die vorherige Farbe wird bei dieser bewusst minimalen Transportdiagnose nicht automatisch wiederhergestellt; sie kann mit der Original-App normal gesetzt werden. Sollte Classic die Lampe nicht mehr steuern können: keine Wiederholungen oder Tests an anderen Lampen starten; Zustand und Firmwarefehler melden.

Der bisherige 15-Sekunden-Color4-Test und die Dual-Lampenfreigabe bleiben deaktiviert. Dieser 8-Sekunden-Test ist eine neue, schmale Hardware-Diagnose, kein Beweis der vollständigen DJ-Chroma-Freigabe oder eines Firmware-Quota-Fixes. Das gesonderte Windows-Tool testet das gleiche Transportprinzip mit simulierten Lampen.

## Qualifikation und Kandidat

Quellcode/Teständerungen ausschließlich auf #61. Android CI: testDebugUnitTest + assembleDebug auf exaktem Branch-HEAD erforderlich. Kein automatisch signierter PR-Build, kein Release, kein OTA/Account-Zugriff und keine Geräteaktion durch CI.

**Signierung (nur Eigentümer):** Bestehenden Android-CI-Workflow auf Branch chat/color4-minimal-music-probe-20261105 manuell als lxsdd auslösen, ausschließlich nachdem exakte PR-CI grün ist. Der private Schlüssel bleibt in GitHub-Secrets; PR-Läufe können ihn nicht lesen. Der explizite RC9-Dispatch prüft PR #61, SHA, Versionsnummer 0.2.8-rc9 / Build 27 und einen erfolgreichen PR-Android-CI-Lauf auf exakt diesem SHA, bevor ein temporäres signed Actions-Artefakt entsteht. Die Release-Publikation bleibt für diesen Zweig **deaktiviert**. Das originale bestehende Signierzertifikat muss exakt stimmen.

Eine erfolgreiche GitHub-CI kann keinen Hardware-Test ersetzen. Test nach Installation mit realer Color4 erforderlich.
