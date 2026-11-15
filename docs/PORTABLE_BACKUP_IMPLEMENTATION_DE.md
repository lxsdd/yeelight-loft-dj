# Lokale Konfigurationssicherung – isolierter CHAT-Draft #59

Die bestehende Szenensicherung bleibt erhalten. Unter Szenen übertragen erscheint eine weitere Material-3-Karte **Gesamte Konfiguration sichern**.

- Export: Geräte-Referenzen, Räume, Gruppen, Zuordnungen, Reihenfolge, Auswahl, eigene Farbverläufe/Chases und Restore-Präferenz. **Keine IP-Adressen, Kontotokens oder laufenden Recovery-Zustände.** Eine Hardware-ID nur bei bestätigtem stabilem ID-Format. Die Datei ist unverschlüsselt und enthält möglicherweise private Lampen-/Raumnamen.
- Import: Android-Systemdateiauswahl, Limit von 256 KiB, Format-/Versions-/Referenz-/Checksumprüfung vor nutzerbestätigtem Import. Import ist additiv und führt genau einen lokalen SharedPreferences-Commit aus.
- Priorität: bestehende lokale Räume, Gruppen, belegte Zuordnungen und Aliase gewinnen. Neue Szenen werden inhaltlich dedupliziert. Kein automatisches Überschreiben/Löschen.
- Nicht erkannte bzw. IP-only-Lampen werden nicht anhand alter IPs verknüpft, sondern als zur erneuten Zuordnung erforderlich gemeldet. Für sie sind Gruppen- und Raumzuordnungen nicht automatisch wiederhergestellt: nach Discovery ggf. erneut importieren.
- Null LAN-/Cloud-/Google-Home-/Music-/Recovery-Befehle im gesamten Import/Export. Keine laufenden Geräte, Socket-Sessions, Firmware- oder Kontodaten werden verändert.
- Die Yeelight-Classic-Kontosynchronisation ist hier absichtlich **nicht** implementiert. Bisher fehlt ein belastbarer API-Vertrag für eigene DJ-Konfigurationsdaten.

## Qualität und bekannte Grenzen

- Codec-Regressionen prüfen Feld- und Frame-Roundtrip, ungültige Formate, Checksummenmanipulation, falsche Referenzen, doppelte Hardware-IDs, IP-Lecks und Größenlimits.
- Exakte Android-CI (testDebugUnitTest, assembleDebug) ist erforderlich; Quellcode-CI ersetzt weder echten Android-SAF-UI-Test noch Color4-Hardware-Abnahme.
- Dieser Branch baut Funktionen in **Loft DJ** ein; er ist ausdrücklich kein modifizierter Yeelight-Classic-Fork. Das separat lokale verschlüsselte Python-Archiv hat ein anderes, noch nicht kompatibles Format.
- Kein Merge, Release, öffentliche APK, Nutzergerätetest oder Cloud-Write im Rahmen dieses Drafts.
