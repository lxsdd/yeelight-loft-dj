# Yeelight Classic 3.5.4 × Loft DJ · private Classic-First-Integration

**NEUER FÜHRENDER ABSCHNITT:** `TOOLCHAIN_MODERNISIERUNG_DE.md` dokumentiert das am 06.11.2026 vorgeschlagene Toolchain-Update, neue Tests und die strikt getrennte Abnahme. Unten ist die vorherige, unverändert testbare Integrationshistorie beschrieben; frühere Versionsangaben dort gelten nur als Herkunftsbelege.

Stand: 6. November 2026. **Privater, originärer Quell-/Teststand, KEINE installierbare oder signierte APK.** Das Archiv enthält weder das Original-APK noch OEM-DEX/Smali/Herstellerbibliotheken, Kontodaten, Lampen-IP-Adressen oder Schlüssel.

## Was neu integriert wurde

- Die **exakten Originaldateien** wurden verifiziert: Classic `com.yeelight.cherry_3.5.4.apk` SHA-256 `abdcffbcf118e5d5c48088a478c8ea49f4c3fa911db1f1bc5013fed1d61fb7f2`; ursprünglicher Turn-14-Checkpoint SHA-256 `4437bef2f7526149694f5e70086a297836e6b1065ac3f785956fbea7186b8e22`.
- Die bestehende Kotlin-Engine (`ClassicBeatAnalyzer`, `ClassicPcmBands`, `ClassicPcmDjBridge`, `ClassicDjEngine`, `ClassicMusicCoordinator`, `ClassicPcmCaptureSession` und `ClassicAndroidAudioRecordInput`) ist nun über `CheckpointRuntime` tatsächlich an die Java-`DjRuntime`-Schnittstelle angeschlossen. In diesem Entwicklungsstand funktioniert **ausschließlich Mikrofonanalyse**; es sind **NULL Lampen registriert**, der Transport ist absichtlich fail-closed. Späterer Music-LAN-Transport, Gruppen und Audioausgabe sind nicht stillschweigend vorgetäuscht.
- Originale Classic-Activity-Oberfläche, eigener DJ-Einstieg im OEM-Hauptlayout, Android-Mikrofon-Permission, Foreground-Service, Notification-Tap zum DJ-Screen, eigener Stopp, Anzeige des BPM-/Pegelmeter-Stands im Screen auf Quellcodeebene verdrahtet. Kein Shortcut zur separat installierten Loft-DJ-App.
- Die echte **binäre Original-Manifeststruktur** wurde passiv gelesen: Package `com.yeelight.cherry`, 185 Activities, SDK min 19/target 33, **sieben Provider-Authorities**, Hauptlayout `res/Ms.xml` / `activity_main_new` mit `LinearLayout`-Wurzel und fünf Elementen. Für den privaten Koexistenztest wird ein eigenes Paket `io.lxsdd.yeelightdj` (gleiche Byte-Länge wie OEM-Paket) verwendet. Original-Application-/Activity-Klassen bleiben in ihrem OEM-Namespace.
- Entscheidend: Fünf harte OEM-Provider-Adressen kommen außerdem in **zehn DEX-String-Referenzen** vor. `dex_authority_remap.py` ersetzt exakt diese Literal-/`content://`-Werte **nur in privaten Kopien** und berechnet SHA-1/Adler32 korrekt neu. Unbeteiligte Stringdaten und die Original-APK werden nicht verändert. Das Skript verweigert fremde/beschädigte Eingaben und verändert die restlichen OEM-Klassen nicht. Ein vollständiger Android-Runtime-Test bleibt erforderlich, da weitere nicht dokumentierte Hersteller-Kopplungen möglich sind.
- `bridge/build_bridge.py` und `build_private_unsigned.py` sind plattformübergreifende **Offline-SDK-/Apktool-Buildvorbereitungen**. APK-Build, DEX-Toolchain-Kompatibilität, Hersteller-UI/Cloud/BLE, Signierung, Android-Gerät und Lampen sind in dieser Umgebung **nicht** abgenommen.

## Verifizierte Tests

Mit den beiden exakten Uploads:

```sh
export CLASSIC_ORIGINAL_APK=/PRIVATE/com.yeelight.cherry_3.5.4.apk
./run_all_offline_tests.sh
```

Die ursprüngliche Suite umfasste 28 Integrations-Python-Tests (mit echten Original-APK/DEX-Metadaten), 37 ursprüngliche Kotlin-Checkpoint-Python-Tests sowie 7 eigenständige Kotlin/JVM-Testprogramme, einschließlich Mic-Start/Stop, Bytegenauigkeit, Fremd-/Quota-Zuständen, Beat-130-BPM, PCM/FFT und Runtime-Abbruch. Das Java-Android-UI wurde separat gegen API-Stubs typgeprüft. **Diese JVM-Tests ersetzen keine Android-Tests.**

## Privater unsignierter Vollbuild auf einem Rechner MIT Toolchain

Aktueller privater Build benötigt Python 3, ein **SHA-256-gepinntes Apktool-3.0.3-JAR**, JDK 17, Gradle 9.8.2/Kotlin 2.4.21 und Android SDK 36 samt Build Tools 36.0.0. Der Kotlin-CLI-Fallback ist nur für synthetische Tests vorgesehen. Alle müssen lokal vorhanden sein, das Tool lädt/installiert nichts nach und nimmt keine Nutzer- oder Geräteänderung vor.

```sh
python3 build_private_unsigned.py \
 --original /PRIVATE/com.yeelight.cherry_3.5.4.apk \
 --android-sdk /PFAD/ZUM/ANDROID_SDK \
 --output /PRIVATE/yeelight-classic-loftdj-UNSIGNED.apk
```

Unter Windows `python` statt `python3`; Pfade entsprechend ersetzen. Nur ein Verzeichnis angeben, in dem die neue Zieldatei **noch nicht existiert**. Der Build soll abweisen, wenn Toolchain, Originalhash, Hauptlayout, eigene Provider oder Sidecar-DEX den verifizierten Vertrag verletzen. Ohne echte Toolchain liegt **kein Build-PASS** vor.

## Noch fehlende Qualifikation

1. Privat-APK-Build mit echtem `apktool`, Android-SDK/D8; realer DEX-Duplikat-/Kotlin-stdlib-Kompatibilitätsnachweis; OEM-Resourcen/Intents/OAuth/Signatur-/BLE-Kopplung auf echter Android-Instanz verifizieren. Original-Classic-Installation **unangetastet** lassen.
2. Bewährten Music-Rückkanal aus RC16 und den unabhängigen Color4-Quota-Koordinator nur mit eigener freier Claim-/Source-Route, tatsächlicher Hardwarefähigkeit und überprüften Benutzerfreigaben integrieren. In diesem Paket **keine** LAN-Schreibbefehle und kein automatisches Session-Takeover.
3. Verbindliche vollständige lokale Szenen-/Raum-/Profil-/Gruppenmigration, eigene stabile Geräteidentität und Backup/Import; Classic-Cloud optional, nicht vorausgesetzt. Keine unbelegten Cloud- oder Google-Home-Schnittstellen.
4. Reale Tests auf Android (Audio/Notification/Standby/Bluetooth) und an Color4-Lampen: Quota, Recovery, 1/4/14 Geräte, Classic anschließend, Nichtteilnehmer OFF, kein Quota-Stressversuch ohne separates Sicherheitsgate.

Keine GitHub-Remote-Quellschreibaktion, kein Merge/Release/Signieren/Installieren oder Hardwareeingriff in diesem Entwicklungsblock. Der alte `checkpoint/src/classic_patch.py` ist zur Provenienz enthalten, aber **NICHT** die aktive Build-/Navigationsroute.


## Aktualisierung: vorhandene Loft-DJ-Buildumgebung statt Zweit-Toolchain

Die echte RC17-Android-CI verwendet **Temurin Java 17**, **Gradle 8.13**, **AGP 8.13.2**, **Kotlin 2.2.20**, **Android SDK 36** und `android-actions/setup-android@v4`.

Die neue `private_gradle_bridge/` verwendet inzwischen die für die Migration vorgesehene Kotlin-Version **2.4.21** (nicht die alte CI-Baseline), und baut
Java und Kotlin als eigenen, herstellerfreien JVM-Bridge-Quellstand.
`bridge/build_bridge.py` erzeugt daraus mit vorhandenem D8 eine DEX-Datei und
prüft vorher gegen die echten OEM-DEX-Klassendefinitionen, ob Kotlin-Klassen
doppelt wären. Nur OEM-bereits-vorhandene `kotlin-stdlib`-Klassen werden aus
**privaten Kopien** der zusätzlichen Laufzeit-JARs ausgefiltert. Alle sonstigen
Klassenkollisionen führen zum Abbruch, nicht zum stillen Überschreiben.

`private_ci/build_with_loft_toolchain.sh` kann auf einer vorhandenen
**vertrauenswürdigen privaten** Java17/Android36/Gradle9.8.2-Umgebung laufen,
wenn exakt diese Original-APK dort lokal erreichbar ist. Zusätzlich braucht
es lediglich Apktool 3.0.3 mit verifizierter SHA-256-Prüfsumme.

Die bestehende öffentliche Loft-DJ-GitHub-Action erhält **keine**
Hersteller-APK, kein angepasstes Hersteller-DEX und keine neue Release-Logik.
Das Beispiel `private_ci/source_ci_example.yml` ist für eine spätere
private Source-only-CI bestimmt und wurde NICHT auf GitHub ausgeführt.

**Stand dieser Ausführung:** Die erweiterte lokale Python-/Kotlin-Suite ist
getestet. Vollständiger Android-SDK/D8/Apktool-Build konnte in der aktuellen
Containerumgebung mangels Android-SDK und Gradle nicht laufen. Es gibt
weiterhin keine signierte oder installierbare Classic-APK und keine
Geräte-, UI-, Bluetooth- oder Kontoverifikation.
