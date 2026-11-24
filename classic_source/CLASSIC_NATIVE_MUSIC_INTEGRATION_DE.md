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

## Rücknavigation aus der DJ-Benachrichtigung (Quellstand 18.11.2026)

Die Benachrichtigung erhält ihr Ziel nur nach tatsächlichem
`onActivityResumed` der OEM-`MusicActivity` im separaten Klon.
Die beobachtete Activity wird schwach referenziert und nach Zerstörung
widerrufen; ein neu erzeugter Host wird dabei nicht überschrieben.
Ohne qualifizierte Classic-Music-Activity wird kein Tap-Intent gesetzt,
insbesondere kein Rückfall auf die bisherige separate DJ-Activity oder
einen Login-/Welcome-Start. Die Stopp-Aktion bleibt verfügbar.
Ausführbare positive/negative synthetische Java-Regressionsfälle
ergänzen die Quelltests. Diese Änderung beansprucht **keine**
OEM-Laufzeit-, Session- oder Lampenabnahme.

## Classic-DJ-Session-Fence im echten Music-Produktpfad (18.11.2026)

Der Service akzeptiert `MIC` nur mit einer frischen, gültigen
sitzungsgebundenen UI-Kennung. Beide im APK vorhandenen Aufrufer (das
native `ClassicMusicInlineControls`-Panel und die allein für private
Diagnostik verbliebene Legacy-Activity) erzeugen beim expliziten
Start je eine neue Generation und geben sie bei Live-Änderungen und
Stopp weiter. `ACTION_CONFIG`, `ACTION_STOP` und die
Benachrichtigungs-Stoppaktion akzeptieren ausschließlich die
**aktuell zugeordnete** Generation; alte, fehlende und fremde Kennungen
werden abgewiesen. Ein zweiter fehlerhafter Start kann eine bestehende
Sitzung nicht mit `stopSelf()` beenden.

Beim Pausieren der originalen MusicActivity wird die eigene bestehende
Service-Instanz sofort generationstreu eingezäunt und asynchron beendet.
Wenn Android die direkte Serviceinstanz noch nicht bereitgestellt hat,
folgt ein generationstreuer Service-Stopp-Intent. Der vorherige
`stopService`-Fallback ohne Besitzprüfung wurde entfernt, um die
Musiksitzung einer neu gestarteten Activity nicht abzuschießen.
Regressionsprüfungen umfassen den echten Java-Gate-Typ, beide UI-Aufrufer,
Lebenszyklus, doppelte Generationen und ungültige Stopp-Intents.

Diese **UI-Sitzungskennung ist keine OEM-Lampenfreigabe**. Alle
tatsächlichen Ziele bleiben leer; Color4-, MiOT-, LAN- und reale
MusicActivity-Laufzeitabnahme sind weiterhin offen.

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

## Geschützter Clone-Test folgt jetzt der nativen MusicActivity, nicht Home

Der ausschließlich eigentümerautorisiert gestartete private Emulator-Runner
verlangt mit `--require-clone-music-ui` nun das tatsächliche Original-
`MusicActivity`-Fenster im **isolierten Klon**. Er akzeptiert zuerst nur einen
realen OEM-Launcher-Start, isoliert alle Netzwerkrouten und überprüft den
installierten privaten APK-SHA. Falls Welcome/Login/Home statt MusicActivity
angezeigt wird, meldet er differenziert
`PRIVATE_EMULATOR_OEM_START_ONLY` /`OEM_START_OBSERVED_MUSIC_NOT_ATTESTED`
ohne irgendeine fremde Schaltfläche anzutippen oder eine Anmeldung zu erfinden.
Das ist bewusst **kein** Feature- oder Login-PASS.

Nur beim tatsächlichen OEM-MusicActivity-Foreground mit exakt benanntem
`Loft DJ`-Toggle aus `ClassicMusicInlineControls` tippt der Test
diesen einen Schalter an. Anschließend müssen dieselbe originale
MusicActivity sowie die Status-, Analyse- und Stopp-Controls aus
dem Inline-Panel sichtbar sein; eine neue `ClassicDjActivity` wäre
ein Fail. Es gibt keinen Mikrofonstart, keine Gerätekommandos und
keinen Login-Bypass. Fremde, doppelte, versteckte oder falsche Buttons
qualifizieren nicht. Der ältere separierte Home-/Identitätskontroll-
Test bleibt als historische Diagnostik erhalten, aber ist nicht
mehr der reguläre private Clone-Akzeptanzpfad.

Diese Korrektur beseitigt den wiederholten **falschen erwarteten
Home-Button**, nicht den noch erforderlichen realen Classic-Erststart,
MiOT-Init/Account-State oder den 4-KiB- und Pixel-6a-Akzeptanztest.

## 24.11.2026 — LAN-/Quota-Diagnosen im nativen Original-Music-Pfad

Bestehender Music-Reverse-Transport unverändert der einzige Echtzeit-DJ-Pfad.
Normale LAN-RPC-Reads und -Writes zählen in einem prozessweiten, je Lampe
isolierten konservativen Sicherheitsbudget von 8 Versuchen je 60 Sekunden.
Reconnect, Fehlversuche oder unsichere Antworten setzen den Zähler nicht
zurück. Die monotone Zeitbasis unterstützt den gültigen signierten Ursprung
von `System.nanoTime()` und verhindert Fehler beim 64-bit-Überlauf.
Kein Timer, kein versteckter Replay oder automatischer Retry.

Nur ein zur aktuellen RPC-ID passender ausdrücklicher Gerätefehler
`client quota exceeded` wird als bestätigte Geräte-Quota behandelt.
Lokaler Limitstopp, andere Ablehnung, Timeout, Netzwerk-/OEM-Fehler,
unbekannter Music-Zustand, fremde Sitzung und unsicherer Cleanup bleiben
getrennte Ursachen und werden durch einen festen, privaten Daten gegenüber
sicheren Statusvorrat in der Original-Classic-UI angezeigt. Ein gesperrter
Start trägt keinen irreführenden laufenden Verbindungsstatus mehr.

Die vorhandenen Eigentümer-/Sitzungsgrenzen bleiben strikt: nur bestätigte
eigene Music-Verbindungen stoppen, nie fremde übernehmen, keine Wiederholung
nach unklarer Übertragung und keine scheinbare Hardware- oder Firmware-
Heilung. Regres­sionen prüfen echte Produktionsklassen (Transport/Coordinator,
Kotlin-Runtime, Java-UI) mit 14/32 virtuellen Teilnehmern, Quota, Timeout,
Fremdbesitz, negativen monotonic Zeiten, Überlauf und gesperrtem Start.
Öffentliche CI qualifiziert nur Quelle/SDK und kein Pixel-6a-/OEM- oder
Color4-Geräteverhalten. Keine Chroma-/HA-/Firmware-/Geräteschreibaktion.
