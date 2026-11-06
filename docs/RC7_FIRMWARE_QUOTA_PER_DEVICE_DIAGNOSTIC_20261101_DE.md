# RC7: Firmware-Quota nur für betroffene Lampe anzeigen

User 01.11.2026: Couchtisch und Flur nach simultanem 10-s-Music-Test beide real in Ausgangszustand zurückgesetzt (DUAL RESTORE PASS). 159/159 Audioframes, 33 Akzente und 34 RGB-Schreibversuche (je 17), 0 Beats. Keine belastbare Beat-Synchronität.

Beim Wechsel auf eine Esstischlampe meldete deren Preflight client quota exceeded. Der Nutzer berichtete, dass dieselbe globale Fehlerzeile bei anderer/leer werdender Auswahl sichtbar blieb. Das ist nicht identisch mit einer globalen Lampen-Firmware-Quarantäne: DeviceCommandGate blockiert pro Geräte-IP; MainActivity zeigte aber bisher nur controller.status ohne IP.

Die Änderung attribuiert jeden Preflight-Fehler der wirklichen Gerätebezeichnung und IP-Endung. Beim lokalen Wechsel von Ziel oder Checkboxen wird die veraltete globale Statusmeldung aktualisiert. Ein getrennter persistenter Warntext listet tatsächlich pro IP quarantänisierte Geräte; andere bleiben auswählbar. Keine einzige zusätzliche LAN-Abfrage, kein automatischer Retry, kein Reset, keine Quarantäne- oder Recovery-Ausnahme.

Die ursprüngliche Firmwareursache (z.B. Anzahl aktiver Verbindungen, konkurrierende Apps, Firmware-Zustand oder lokale Kommandorate) lässt sich aus dem Screenshot allein noch nicht sicher bestimmen. Diese Änderung löst die fehlerhafte UI-Diagnose, nicht belegbar die physische LAN-Quota-Ursache. Bis gesonderter Freigabe kein neuer signierter Dual-/Esstisch-Release.