# RC6: Zwei-Color4-Eignungsprüfung – sichtbarer Status

Quelle: Nutzerrückmeldung mit zwei RC6-Screenshots vom 01.11.2026. 15-s-Einzeltest: Audio 194/240, 23 Beats, 37 Akzente, 25 RGB-Versuche (18 Beat / 6 Akzent / 1 Aktivität), 0 gemeldete Übertragungsfehler. Mikrofon-DSP-Gain ×4 und angezeigter PCM-RMS ca. -20 dBFS nach Softwareverstärkung. Ein optisch bestätigter Restore nach RC6 wurde in dieser Meldung noch nicht separat berichtet.

In einem zweiten Schritt waren **Couchtisch und Flur** selektiert. Die lokale zwei-Lampen-Capability-Prüfung lieferte einen positiven Text, doch der Nutzer hielt sie für wirkungslos, da kein Licht schaltete und die Resultate ohne eindeutige Statusüberschrift mit vorherigen RGB-Einzeltestwerten vermischt waren.

Die UI unterscheidet jetzt Eignungsprüfung und Einzeltest mit getrennten Überschriften; enthält Status (bestanden/abgelehnt), gewählte Lampennamen und explizit **0 Lampenbefehle**. Ergebnis wird bei Änderung von Auswahl, SUPPORT-Metadaten, lokaler Quarantäne oder Recovery-Zustand invalidiert. Keine Test-/Netzwerk-Eskalation: tatsächlicher Zwei-Lampen-Betrieb bleibt verboten, bis beide Geräte einzeln sicher restauriert und ein separater zeitbegrenzter Dual-Test genehmigt und implementiert ist.

Die Einführung der getrennten statusorientierten UI ist unabhängig von jeder Veränderung des Yeelight-Music-Kanals, der Kommando-Pacing-Limits, des Stop/Restore-Pfads und der Color4-Firmware.