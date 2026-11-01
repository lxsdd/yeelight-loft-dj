# Yeelight Chroma UDP: geprüfter Original-Firmware-Forschungsansatz

Stand: 2026-11-01. In Forschungs-Draft #30, neben `FIRMWARE_RESEARCH_FEASIBILITY_DE.md`.
**Nur Quell- und Capability-Auswertung, keine Netzwerk- oder Hardwareabnahme.**

## Herstellerquelle und relevante Hinweise

Yeelight veröffentlicht seinen offiziellen **Yeelight Chroma Connector** mitsamt C++-Quellcode unter **BSD-3-Clause**:
https://github.com/Yeelight/Yeelight-Chroma-Connector

- `ChromaBroadcastSampleApplication/ChromaBroadcastSampleApplicationDlg.cpp`, Funktion `IsSupportUDP`: Die Anwendung prüft, ob die vom Gerät bei Discovery gelieferten Methoden `chroma` enthalten. Sie bevorzugt `udp_sess_new` (Protokoll v2), sonst `udp_new` (v1).
- `ChromaBroadcastSampleApplication/CUdpLight.cpp`, `AcquireToken`: UDP-Sitzung wird mit `udp_sess_new` bzw. `udp_new` begonnen; Geräteantwort stellt ein **separates Sitzungstoken** bereit (`udp_sess_token`/ `udp_token`).
- `SendKplMsg`: Keepalive über `udp_sess_keep_alive`/ `udp_keep_alive` (mit Token).
- `SendCtrlMsgSetScene`: Im etablierten UDP-Kanal sind `set_scene`, `bg_set_rgb` oder `bg_set_scene` modellabhängig vorgesehen.
- Der Quellcode arbeitet mit Discovery-SUPPORT, nicht mit einer statischen Modell-Whitelist. Es gibt **keine Gewähr** für die Unterstützung auf jedem `color4`/Firmwarestand.
- Yeelight bewirbt die **1S Color** für Chroma ab Firmware **2.0.6_0030** und den **1S Lightstrip** ab **2.0.6_0013**. Das ist eine Hersteller-Kompatibilitätsangabe, **kein** Beleg für einen bestimmten UDP-SUPPORT-Header unserer Geräte: https://www.yeelight.com/pages/razer.html
- Alter Hersteller-Originalcode enthält unzureichend robuste String-/Token- und Response-Prüfungen. Er ist eine **Protokollquelle**, nicht unbesehen als sicherer Parser zu übernehmen.

## Verhältnis zu LAN TCP und Music Mode

Yeelight LAN TCP `55443` und `set_music` sind ein **separater Mechanismus**: Music Mode öffnet einen Reverse-TCP-Kanal; Chroma UDP etabliert eine eigene tokenisierte UDP-Sitzung. Ein TCP-`set_music`-SUPPORT ist **kein** Chroma-UDP-Nachweis. Umgekehrt liefert ein Chroma-Header kein Garantieversprechen zur unbegrenzten UDP-Rate, Latenz, Zuverlässigkeit oder vollen Google-/Yeelight-App-Koexistenz. Priorität behält der im laufenden App-Quellstand implementierte Quota-sichere TCP-Kanal.

## Neuer rein lesender Validator

`ChromaUdpResearch.fromSupport(methods)` ordnet vom Gerät **tatsächlich beobachtete** SSDP-SUPPORT-Methoden ein:

- keine Liste: `NO_SSDP_SUPPORT_EVIDENCE`
- ohne `chroma`: `NOT_ADVERTISED`
- `chroma` aber kein `udp_new`/`udp_sess_new`: `CHROMA_ADVERTISED_BUT_NO_SESSION_METHOD`
- `chroma` + `udp_new`: `UDP_SESSION_V1_ADVERTISED`
- `chroma` + `udp_sess_new`: `UDP_SESSION_V2_ADVERTISED`

`mayOpenUdpSessionForResearch()` gibt **immer false** zurück: Es ist ausschließlich ein Forschungs-Gate, **kein** aktiver UDP-Client oder verbindlicher Installationsschalter. Die 5 neuen JVM-Regressionen bestätigen insbesondere, dass `color4`-Name, `set_music` oder Chroma-Marketing **keine** UDP-Sendeerlaubnis erzeugen.

## Hersteller-/Altbefunde für color4 und Netzwerkausfälle

- Bereits 2020 wurden auf YLDP13YL / `color4` **spontane TCP-Verbindungsabbrüche und zeitweise fehlgeschlagene Sprachsteuerung** mit Originalfirmware berichtet. Dies stützt eine alternative OEM-Firmware-/Client-Interaktion als Diagnosehypothese, beweist aber nicht die Ursache unserer aktuellen LAN-Quota-Lockouts: https://github.com/home-assistant/core/issues/35202
- Hersteller-Mapping von `color4` auf u. a. YLDP13YL: https://www.home-assistant.io/integrations/yeelight/
- **Kein** aus anderen älteren Yeelight-Teardowns übertragener MCU-Nachweis für YLDP13YL. Auch andere alternative Plattformen wie LibreTiny oder OpenBeken setzen eine **wirkliche identifizierte Chipfamilie** voraus. Kein generisches Flashrezept.

## Noch erforderliche Abnahme vor aktiver UDP-Implementierung

1. Ein **bereits ohnehin vorhandener**, vertrauenswürdiger SSDP-`support`-Beleg eines `color4`-Geräts muss die Fähigkeiten zeigen (im privaten Gerätelog; keine IP/MAC/Token ins öffentliche Repo).
2. Wenn bestätigt: Den Herstellerdialog mit einem **simulierten UDP-Server** und robusten JSON-/Timeout-/Absenderprüfungen nachbauen, ohne produktive Lampe. Token in Logs nie offenlegen.
3. Danach gesondert freigegebene, stark begrenzte Hardwareprobe mit STOP, Reservezustand und Yeelight-/Google-/Music-Mode-Koexistenz prüfen.
4. Erst nach erfolgreichem Durchlauf ein optionaler Adapter; zunächst bleibt Original-LAN TCP der Standard. Keine automatische UDP-Firmware- oder Netzwerkänderung.
