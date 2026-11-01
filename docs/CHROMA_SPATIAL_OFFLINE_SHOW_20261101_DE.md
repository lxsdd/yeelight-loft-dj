# Räumliche Chroma-Simulation: nonlineare Lichtwellen mit gemeinsamer Sicherheitsgrenze

Stand: 01.11.2026. Isolierter Forschungs-Draft auf PR #40; keinerlei reale Lampenkommunikation.

## Implementierter Funktionsbereich

- ChromaSpatialLamp: ausschließlich virtuelle x/y-Positionen von 0 bis 1000, Schlüssel eindeutig; 2 bis 16 Teilnehmer. Überlappende Lampen dürfen gemeinsame Koordinaten haben.
- ChromaSpatialRenderer: deterministische 2D-Radialwellen und horizontale Sweeps mit streng begrenzter Palette, Bandbreite, Ursprung, Geschwindigkeit und Helligkeit. Keine Threads oder Netzwerkanfragen.
- ChromaSpatialOfflineShow: gemeinsame Startprüfung ALLER bereits synthetisch token-aktivierten Teilnehmer. Ohne vollständige erfolgreiche Vorprüfung wird die Gruppe terminal geschlossen.
- Explizit gerufene tick(nowMs)-Funktion: berechnet einen aktuellen RGB-Zielwert je Lampe und nutzt ausschließlich ChromaOfflineLatestValue, ChromaUdpOfflineFleet und ChromaUdpOfflineSession. Alle bekannten Rate-, Frame-, Token- und Quarantänegrenzen bleiben wirksam.
- Eine ausgefallene, gesperrte oder abgelaufene Sitzung stoppt beim nächsten synchronen Tick alle Teilnehmer. Laufzeit absolut auf 15 Sekunden und höchstens 45 RGB-Frame-Versuche je Lampe begrenzt; kein Wiederaufbau, keine automatische Wiederholung.
- Globale Stop-Methode schließt ausschließlich in-memory Sitzungen und entfernt lokale Token. Kein echtes Power-Off, kein Restore, kein Side-Effect.

## Wichtiger Unterschied zu Knight Rider

Chroma-RGB-Set-Scene bei Helligkeit 1 bedeutet NICHT physisches AUS. Ein echtes Knight-Rider-Lauflicht, bei dem nur eine Lampe eingeschaltet ist, darf daher nicht als Option dieser Simulation verkauft werden. Es bedarf des bestehenden gesondert qualifizierten OFF-before-ON-Runners mit verifizierter Stromaus-Rücklesung und Hardwarefreigabe.

## Verifikation und Grenzen

JVM-Suites prüfen nichtlineare Abstände (auch y-Koordinate), horizontale Vergleichspositionen, 14 gemischte v1/v2-Lampen mit individuellen synthetischen Tokens, vollständige Gruppen-Vorprüfung, Quarantäneausbreitung, Tokenverlust, Uhrzeit-Rücksprung, 15-Sekunden-Frist und 45 Versuche pro Lampe.

Die Simulation beweist keine reale Color4-Unterstützung, keine optische Gleichzeitigkeit, kein Paketverlustverhalten und keine Umgehung von client quota exceeded. Keine private Grundrissdatei, Netzwerkadresse, Firmwaredatei, aktive UDP/TCP-Verbindung, Hardwareaktion, signierte APK, kein Merge oder Flash.
## Zusätzliche End-to-End-Sicherheitskorrektur

Die Startbereitschaft für eine räumliche Show verlangt jetzt **dreierlei**:
synthetisch aktives Sitzungstoken, nicht gesperrte Lane und exakt als SSDP
SUPPORT gespeichertes `set_scene`. Ein Token ohne Farbkommando ist **kein**
positiver Startnachweis. Der in-memory Letztwert-Koaleszierer prüft bei
**jedem** Angebot zusätzlich das aktuelle 15-Sekunden-Sitzungsbudget
vor dem Ablegen eines Farbwertes. Im abgelaufenen Zustand werden
eingehende Werte verworfen, statt trotz Ablauf eine wartende Farbe
anzuzeigen. Zusätzliche negative JVM-Regressionen prüfen den
bereits tokenisierten, aber RGB-unfähigen Teilnehmer und den
verfallenen Session-Zeitpunkt.
