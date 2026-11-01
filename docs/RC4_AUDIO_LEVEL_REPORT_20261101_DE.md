# AUDIO-01 · Color4 RC4 – Hardwarebeleg und Pegeldarstellung

Stand 1. November 2026. Signierte, zuvor freigegebene RC4 (v0.2.8-rc4, Build 22; Source-SHA cea98c29aef124f6d8ee32271d84796507a008ea). Nutzer bestätigt auf **Couchtisch**, ausschließlich für die kurze Einzelgeräte-Session: sichtbar musikabhängige Farbwechsel und anschließend erfolgreiche Wiederherstellung des ursprünglichen Zustands. Weiterhin **nicht bestätigt** sind die erneute Yeelight-Classic-Steuerung unter RC4, genaue Beat-Synchronität, Langzeit-/Mehrlampen-Stabilität und Firmware-Quota-Verhalten unter höherer Last. Kein automatischer Folgetest an Wellness oder weiteren Lampen.

## UI-Befund

Die bisherige Kennzahl (reading.rms * 100).roundToInt() + " %" misst nur den Anteil des digitalen PCM-RMS am Vollpegel. Die beobachteten **3–4 %** entsprechen ungefähr **−30,5 bis −28,0 dBFS**. Diese Zahl ist **keine** Prozentangabe der subjektiven Lautstärke, keine kalibrierte Schalldruckmessung und kein Beweis eines Android-Mikrofonfehlers. Der reale Farbwechsel zeigt, dass die RC4-Audioaktivitäts-Policy den gemessenen Eingang in der getesteten Situation verarbeiten konnte.

## Korrektur ohne neues Lampenrisiko

Pure JVM-getestete AudioLevelDisplay-Abbildung: RMS/Peak in dBFS; relative logarithmische Signalanzeige zwischen −60 und −6 dBFS; explizite Erklärung direkt in der Musikansicht; korrekter Text bei ausbleibender Beat-Erkennung. Diese Änderung betrifft **ausschließlich die Anzeige**. PCM-Analyse, Beat-Schwellen, Color4-Rückkanal, RGB-Pacing, set_music, Quarantäne, Stop/Restore und alle persistierten Werte bleiben bytegenau unverändert.

## Weiteres Vorgehen

Vor einer Anpassung von Mikrofonverstärkung oder Beat-Schwellen zuerst reale Signal-/Beat-Zähler aus dem letzten RC4-Test prüfen. Kein neuer signierter Build und kein Hardwareversuch allein aufgrund dieser Anzeigeänderung; dafür weiterhin separate Freigabe.
