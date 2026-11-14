# Yeelight Classic – verbindliche native UI-Integrationsarchitektur
Stand: 14. November 2026. Quelle: bereits archivierter lesender Classic-3.5.4-APK-Befund
sowie reproduzierbare Source-/Private-Emulator-Tests. **Kein fertiger nativer Music-UI-Nachweis.**

## Produktregel

Die originale Classic-Navigation, Bedienbegriffe, Original-Dialoge, Farbwahl,
Gerätekarten, Räume, Gruppen und Szeneneditor sind die Basis. Zusätzliche
Features erweitern zuerst deren vorhandenen Daten- und Bedienpfade. Wo
Classic technisch unzureichend ist, darf die Implementierung hinter denselben
UI-Verträgen ersetzt werden, **ohne deren etablierte Benutzung zu brechen**.

Die separate `ClassicDjActivity` mit manueller IP-Eingabe ist ausschließlich
eine **vorübergehende Debug-/Integrationsoberfläche**, keine Endprodukt-UI.
Der derzeit globale `ClassicDjEntryProvider` ist eine private QA-Hilfe und
**keine akzeptierte Navigationsintegration**. Nur ein fehlerfreier Android-Build
beweist weder originale Musiknavigation noch OEM-Verhalten.

## Kandidaten aus dem ersten ausschließlich statischen Original-Audit

| Bereich | Statisches Vorwissen | Verbindliche beabsichtigte Einbindung | Vor dem Hook zwingend offen |
|---|---|---|---|
| Navigation | Hauptlayout, Bottom-Tab-Struktur | Originale Tabs und Fragment-Navigation behalten | Reale Tab→Fragment-Aufrufkette und aktuelle Host-Activity |
| Musik | Music-Activity, Gruppen-Musik-Manager, Notification-Service | Vorhandenen Musikscreen/Service aufwerten, nicht zweiten Musikscreen etablieren | Start/Stop, Mic/Audioquelle, OEM-Intent-/Session-Zustand, Freigabepunkte |
| Szenen | Szenenfragment, Editor und Bundles | Puls, Knight Rider, Komet in vorhandenen Szeneneditor | Modell-/Speicherrouten, Ausführung/Import und Beschränkungen |
| Farben | Originale Farb- und Colorflow-Auswahl | Colorpicker und Zustandsrückmeldung wiederverwenden | Listener- und Ergebnisvertrag, Szenenfarbe vs. Gerätefarbe |
| Räume | Raumübersicht und Raum-Gerätezuordnung | Drag-and-drop in bestehenden Listen samt lokalen Daten | List-Adapter, stabile Geräte-ID, Persistenz und Cloud-Sync |
| Gruppen | Gruppenverwaltung und Gruppeneffekte | DJ-Teilnehmer direkt aus vorhandenen Geräte-/Gruppenmodellen | Auswahl, Ownership, Online-/Offline-Zustand und Quota |
| Gerätesteuerung | Originale LAN-/MiIO-/Cloud-Schichten | Vorhandene Abstraktion nach Prüfung bündeln, asynchronisieren | Durchgängige Button→Controller→Transport→ACK→UI-Kette |

**Statische Namen sind noch keine nachgewiesenen funktionsfähigen Bindungen.**
Die technische UI-Struktur, die tatsächlichen OEM-Handler und die sichtbare
Bedienoberfläche sind separat abzunehmen.

## Reproduzierbare originale Evidence-Gates (ohne neues Tooling)

Der existierende geschützte Besitzer-Workflow dekodiert bereits exakt die
gehashte originale OEM-APK mit Apktool 3.0.3 in zwei temporäre Verzeichnisse:
`-s` für Ressourcen/Manifest und `-r` für Original-Smali zur notwendigen
DEX-Reassemblierung. `private_ci/oem_ui_reuse_audit.py` untersucht diese
**beiden originalen, unveränderten** Verzeichnisse vor OEM-Reassemblierung:
- ausschließlich bekannte Navigation-/UI-Rollen als Kandidaten;
- Vorhandensein von Layout-Varianten und interaktiven Views;
- eindeutige originale Klassen und ihre Manifest-Activity-Zuordnung;
- reine Referenzzählung von Lifecycle, Click, Inflation, Navigation und Dispatch;
- Negativstatus für nur vorhandene Layouts, mehrdeutige Klassen und fehlenden Code.

Nur **abgeschlossene, auf derselben APK ausgeführte** Smali-Handler-Nachweise
dürfen später zu einem konkreten Patch-Hook führen. Ein bloßer Aufrufzähler
oder Dateiname erlaubt keinen Produktions-Hook.

Beim bereits vorhandenen, ohnehin nötigen Vergleich des unveränderten
Originals nach einem fehlgeschlagenen Klonstart klassifiziert
`private_ci/oem_ui_surface.py` **nur lesend** die aktuell angezeigte
Android-UI-Hierarchie. Keine Klicks, keine Kontoanmeldung, kein LAN, keine
Cloud, keine Änderungen an Original-APK und keine Ausgabe von OEM-Klassen,
Resource-IDs, Texten, Zugangsdaten oder UI-XML. Ein sichtbarer Screen allein
qualifiziert noch keine MusicActivity-Navigation.

## Abnahme- und Qualitätsregeln

1. Original-App unverändert und identifizierbar; sämtliche bestehenden
   Basisfunktionen weiterhin verfügbar. Keine verschobenen Root-View-Typen,
   Login-/Provider-Abkürzungen oder ungeprüften OEM-Activity-Replacements.
2. Music-Mode mit natürlichem Ein-/Ausstieg über die **Originalnavigation**,
   ausgewählte Geräte aus dem Originaldatenmodell, sichere Session-Fence,
   stoppbarer Zustand und freier UI-Thread.
3. Bestehende Farbpicker, Szenen-/Gruppenauswahl, Rücknavigation und
   Notification-Reopen nachgewiesen, nicht nur eigene Dummy-Buttons.
4. Originalräume/-gruppen/-szenen auch bei fehlendem Internet lesbar; lokale
   Änderung und Export/Import ohne unbemerkte Cloud-Doppelwrites.
5. Keine regulatorische Lampen-Quote aus reiner Firmware-/XML-Analyse ableiten;
   Chroma nur nach bestätigtem Capability-/Verbindungsnachweis.
6. Vor Merge: nativer Android-Build/Regressionen und danach identische
   private Offline-Emulator-A/B-Abnahme von Original, Kandidat und DJ-UI
   auf **exakter SHA**. Ein PUBLIC-no-OEM-Emulator reicht niemals.
7. Keine automatische Änderung an Produktions-Signing, Physical Devices,
   öffentlich veröffentlichten OEM-Dateien, fremden Claims oder Kosten.

## Exakter Stand dieses Entwicklungsblocks

Die gesicherten **Originalnamen** aus dem ersten APK-Befund sowie die
bereits existierenden geschützten Quell-/Runner-Skripte sind geprüft.
Der Rohbytezugriff auf die Originaldatei in der ChatGPT-Library ist
derzeit nicht autorisiert. Deshalb wurde **kein neuer originaler
MusicActivity-Methoden-/GUI-Befund behauptet**. Die neue UI-Inventur
ist tatsächlicher ausführbarer und synthetisch getesteter Code im
geschützten Buildpfad, aber ihr OEM-Befund erfordert den nächsten
Besitzer-gegate-ten Runnerlauf.

Die aktuelle eigenständige Classic DJ Activity bleibt **interimistisch**.
Es wurden keine unbewiesenen Originalmethoden überschrieben, keine neue
unkontrollierte Auswahl-/Geräte-UI installiert und kein kompletter
OEM-Bedienfluss als abgenommen behauptet.
