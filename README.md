# Yeelight Loft DJ

Android-first, local-first Yeelight-Steuerung mit Kotlin, Jetpack Compose und Material 3.

**Verbindliche und übergreifend nachgehaltene Anforderungen:**
[Feature- und UX-Backlog](docs/FEATURE_LEDGER_UX_ROADMAP_DE.md),
[Produktspezifikation](docs/PRODUCT_SPEC_DE.md),
[erweiterte Produktvision](docs/UNIQUE_FEATURE_VISION_DE.md),
[Befehlsaudit und Knight Rider](docs/COMMAND_AUDIT_KNIGHT_RIDER_025_DE.md).

## Stand 1. November 2026

- Protected `main`: **v0.2.3 / Build 14**, regulär signiert und in öffentlicher CI geprüft.
- [PR #23](https://github.com/lxsdd/yeelight-loft-dj/pull/23): v0.2.4, gezieltes manuelles Verwerfen eines alten Recovery-Eintrags; noch nicht gemergt.
- [PR #24](https://github.com/lxsdd/yeelight-loft-dj/pull/24): v0.2.5, echter AUS-vor-EIN-Knight-Rider-Scanner und Gerätebefehlskatalog; gestapelt auf #23.
- [PR #25](https://github.com/lxsdd/yeelight-loft-dj/pull/25): v0.2.6, gerätebezogene automatische Statusprüfung und klare, kompakte Material-3-UX; gestapelt auf #24.
- Diese PR-Stände sind **kein signierter Release**. Ein erfolgreicher Compiler- und Unit-Test ist kein Ersatz für die Hardware-Abnahme.

## Heute vorhanden und nutzbar (Hardware-Abnahme teilweise)

- Lokale Yeelight-Erkennung via Multicast, TCP und gezieltes UDP mit Geräte-SUPPORT-Feldern, persistenten Aliasen und Raum-/Gruppenzuordnungen.
- Lichtweg Drag-and-drop, individuelle Ein/Aus-, Farb-, HSV-, Helligkeits- und Weißtemperatursteuerung, Szenenkarten und editierbare Yeelight-native Lichtläufe.
- Defensives Recovery-Journal mit geräteweiser Isolation, gemessenen TCP/ACK/Power-Statusphasen, UDP-vs.-TCP-Diagnose.
- Musiklicht-MVP: Mikrofon, FFT/Beat/BPM und Android-Systemaudioaufnahme mit expliziter Permission, soweit die andere Audio-App das erlaubt. **Kein eigener MP3-Player.**
- Mi Desk Lamp 1S: read-only Connectivity-Diagnose, keine behauptete Schreibkompatibilität.

## Bekannte Lücken

- 6/13/7 Multicast-vs.-TCP-vs.-gerichtetes-UDP: Ursache der Multicast-Nichtantworter weiterhin nicht abschließend bewiesen.
- Lampe .134: Yeelight-Firmware meldete `client quota exceeded`; die App begrenzt Retries, kann diese Gerätebegrenzung aber nicht grundsätzlich verhindern.
- Native Schweif-Effekte dimmen angeblich inaktive Lampen bisher auf **1 %** – das ist sichtbar und **nicht AUS**. Nur der neue Scanner-PR verwendet explizit bestätigtes AUS.
- Echter Grundriss (Wände/Türen/x/y/z), Hintergrund-/Segmentsteuerung, Zeitpläne, weitere Musik-DJ-Funktionen, dauerhafte technische Quota-Ursachenbehebung und konsequentes modernes UI bleiben im verbindlichen Backlog.

## Entwicklung, Sicherheit, Release

- Öffentliche GitHub-Actions-Runner, Unit-Tests und Android-Builds im PR.
- Private Gerätelisten, IPs, Wohnungsgrundrisse, Audioinhalte, Zugangsdaten und Signierschlüssel nie in Public-Commits oder Logs.
- `main` ist geschützt. PR-Merges, Signierung und Releases erst nach ausdrücklicher Freigabe.
- Yeelight TCP 55443 nur lokal. Die Hersteller- und Google-Home-Apps bleiben nutzbar.
- Hardware-Abnahme in Stufen: 3 Esstischlampen, dann 5, anschließend 15 Color4 und ein expliziter Stop-/Recovery-Langzeittest.
