# Yeelight Classic – tatsächlicher MusicActivity-Integrationsstand

Stand: 18.11.2026. Getrennte Classic-Owner-Route; nicht Color4/Chroma.

## Verbindliche Produktentscheidung

Die normalen privaten Yeelight-Classic-Klon-Builds dürfen keinen zusätzlichen
Home-Shortcut zur separaten `ClassicDjActivity` mehr als primären Musik-Einstieg
einbauen. Die vorhandene Classic-`MusicActivity` mit dem OEM-`DiscoFragment`
bleibt bestehen. Die neue `ClassicMusicInlineControls` wird ausschließlich
nach einem echten `ActivityLifecycleCallbacks.onActivityResumed` in der
originalen MusicActivity als **eigenes Kind von android.R.id.content**
eingefügt. Ihr kompakter `Loft DJ`-Schalter ist zunächst zugeklappt, sodass
der bestehende Musikbildschirm nicht durch eine zweite Hauptansicht ersetzt wird.
Das ist eine echte, im APK-Manifest und der Bridge verdrahtete Produktionsroute,
nicht nur ein separater Intent/Shortcut.

`ClassicDjEntryProvider` wird im normalen Klon ausschließlich unter der
nicht exportierten Autorität `io.lxsdd.yeelightdj.loftdj.music` registriert.
Nur das Klonpaket und OEM-Klassen aus `com.yeelight.cherry.*.MusicActivity`
dürfen Inline-Steuerungen erhalten; Welcome/Login, Geräte-, Raum- und
Home-Activities nicht. Die bisherige ursprüngliche Paketidentitätsprobe
(`com.yeelight.cherry.loftdj.entry`) bleibt ausschließlich für geschützte
Offline-Diagnose erhalten. Das originale `activity_main_new.xml` des
normalen Klons bleibt **bytegleich** zur entschlüsselten Layoutquelle.
Kein OEM-Layout, Fragment, Klassen- oder Login-Handler wird ersetzt.

## Tatsächlich angebundene Funktionen

Ein expliziter Tastendruck auf `Analyse` startet den vorhandenen
`ClassicDjService`/Kotlin-DJ-Checkpoint **mit einer leeren Liste von
Lampen-Teilnehmern**. Dieser Modus analysiert das Mikrofon, ohne Lampen zu
schalten. Die UI erlaubt Live-Szene (Spektrum, Ruhig, Beat, Puls),
Mikrofon-Empfindlichkeit und unmittelbar asynchronen Stopp. Beim
`onActivityPaused` beendet ausschließlich das Inline-Panel seine eigene
Sitzung, ohne auf Netzwerk- oder Gerätedatensicherung zu warten.
Eine fehlende Mikrofonberechtigung führt zu einer klaren Statusmeldung und
**keinem** automatischen Start, Login oder OEM-Permission-Callback-Hook.
Die bisherige standalone `ClassicDjActivity` bleibt aus Kompatibilitäts-/
Diagnosegründen als nicht exportierte Klasse im DEX, ist aber **nicht**
der neue normale Music-Entry-Point.

**Nicht als erledigt behaupten:** OEM-Geräteselektion/-Gruppen/-Szenen/
Favoriten und das zugehörige MiOT-/LAN-Modell sind noch nicht anhand eines
realen laufenden Classic-Music-Screens gebunden. Keine neue Geräte-Datenbank,
keine versteckten Ziel-IP-Adressen, kein Zugriff auf OEM-private
Nutzerdateien und keine unautorisierten Chroma- oder Lampenbefehle.
Vollständige OEM-Music-/DiscoFragment-Ersetzung, Classic-Farbpicker,
Auto-Power der ausgewählten Lampen, Drag-and-Drop, Scene-Persistenz
sowie physische Color4-Abnahme sind **offen**.

## Nachweise und Akzeptanzgrenzen

Source-CI testet die private Manifestroute mit Eigentümer-/Scope-Gates,
die originalen Home-Layout-Bytes, die erforderlichen Bridge-DEX-Klassen,
positive/negative Lifecycle-Hostfilter, sicheres Mikrofon-Opt-in,
keine Lampen-IP-Writes und das echte Kompilieren aller Java-Quellen.
Der öffentliche Android-Job kompiliert die Bridge erneut gegen ein echtes
Android SDK und führt den vorhandenen Kotlin/DJ-JVM-Testblock aus.
Der öffentliche Android17-No-OEM-Job prüft den Plattformstart, aber keine
proprietäre APK, keine OEM-Musikansicht und keinen OEM-Gerätezustand.

**Die reale MusicActivity-Anhängung ist bis zum ersten echten privaten
OEM-/UI-Lauf weiterhin nicht abgenommen.** Der bestehende private
`android17_start_smoke.py` verlangt noch den inzwischen absichtlich
abgeschafften Home-DJ-Button und ist für die neue MusicActivity-Route
kein gültiger vollständiger Abnahmetest; nicht erneut unmodifiziert
dispatchen. Auch der bisher verwendete 16-KiB-API37-AVD ist wegen
`NATIVE_ELF_LOAD_16K_INCOMPATIBLE` keine unabhängige 4-KiB-OEM-Baseline.
Vor erneuter geschützter UI-Abnahme: 4-KiB-Android-Plattform **real
verifizieren**, First-Run/Cloud-Bind ohne Login-Bypass separat beschreiben,
die MusicActivity-Hostroute auf tatsächlichem OEM-/SDK-Bildschirm prüfen und
sicherstellen, dass der Test keine vollständige Integration vortäuscht.

Keine Merges, Releases, dauerhaftes Signing, physischen Geräteaktionen,
Cloud-Logins, OEM-APK-/Smali-Publikation, Kostenzusagen oder Launcher-Bypässe.

## Qualifikationsweg: 4-KiB-Android-17-Kandidaten nur lesend ermitteln

Die öffentliche Source-CI führt auf dem tatsächlichen GitHub-Host eine
**reine Katalogabfrage** aus. Die für 4-KiB infrage kommenden ungetaggten,
stabilen API37-x86_64-Imagevarianten werden ausschließlich durch einen
festen positiven/negativen Indikator gemeldet. Ein Katalogfund beweist noch
**keine** 4-KiB-Seitengröße: Diese muss in einer getrennt autorisierten,
vollständig isolierten Emulatorinstanz tatsächlich per `getconf PAGE_SIZE`
nachgewiesen werden. Bis dahin läuft der bisherige private 16-KiB-OEM-Test
nicht als neue MusicActivity-Produktabnahme. Die bestehende selektive
16-KiB-Imagezulassung und deren CI-Schranken sind unverändert; keine
Image-Installation, Lizenzannahme, APK-Publikation oder Gerätesteuerung.
