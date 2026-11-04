# Color4 LAN-Quota-Fehlerhistorie (nur lokal, ohne weitere Netzwerkanfragen)

Die drei Esstischlampen waren weiterhin per WLAN/Google Home erreichbar, lokal aber nicht per Yeelight Classic. Die genauen Fehlerursachen bleiben ungeklärt.

Nach echtem Firmware-Quota-Reply speichert die App bis zu zwölf letzte gerätespezifische Diagnosebelege (IP nur in privaten Android-Einstellungen, LAN-Methode, gezählte App-Kommandos im 60-s-Fenster, Zeitstempel). Neuer Beleg für dieselbe IP ersetzt den alten. Das ist keine LAN-Abfrage, kein Retry und keine Freigabe. Die per-IP-Quarantäne hat unbedingten Vorrang; Diagnosespeicherung ist optional.

In den Gerätedetails wird der letzte Befund deutlich als historisch angezeigt, auch nachdem die Lampe per Stromreset wieder steuerbar wurde. Es werden keine Anfragen anderer Apps, interne Firmware-Slots oder Root-Cause vorgetäuscht. Keine privaten IPs in öffentlichen CI-Logs oder GitHub-Beispieldaten.

Negative/positive JVM-Tests prüfen gefälschte/defekte Daten, Längenbegrenzung, In-Memory-Deduplikation pro IP und persistierte Methode/Anzahl/Datum. Color4-Live-Musiksteuerung bleibt im Entwicklungszweig vollständig pausiert.